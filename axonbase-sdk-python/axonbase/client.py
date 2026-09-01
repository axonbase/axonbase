import asyncio
import json
import logging
import ssl
from typing import Any, Callable

import websockets

from .protocol import CertificateChallenge, CertificateCompletion, PROTOCOL_VERSION
from .errors import AxonSdkError, NotLeaderError, from_rpc_error
from .codec import encode_typed

logger = logging.getLogger("axonbase")

LiveHandler = Callable[[str, str, Any], None]


class Axon:
    def __init__(self, url: str, *,
                 timeout: float = 30.0,
                  reconnect: bool = True,
                  reconnect_interval: float = 1.0,
                  max_reconnect_attempts: int = 10,
                  ssl_context: ssl.SSLContext | None = None):
        self.url = url
        self.timeout = timeout
        self.reconnect = reconnect
        self.reconnect_interval = reconnect_interval
        self.max_reconnect_attempts = max_reconnect_attempts
        self.ssl_context = ssl_context

        self._ws = None
        self._next_id = 1
        self._pending = {}
        self._live_handlers = {}
        self._handshake_received = False
        self._namespace = ""
        self._database = ""
        self._auth: str | None = None
        self._hello = None
        self._closed = False
        self._loop = None

    async def _open_ws(self):
        self._ws = await websockets.connect(self.url, ssl=self.ssl_context)
        hello = await asyncio.wait_for(self._ws.recv(), self.timeout)
        parsed = json.loads(hello)
        if "hello" not in parsed:
            raise AxonSdkError("handshake hello esperado como primeiro frame")
        self._hello = parsed["hello"]
        self._handshake_received = True
        self._loop = asyncio.get_running_loop()
        asyncio.ensure_future(self._listen())

    @classmethod
    async def connect(cls, url: str, **kwargs):
        axon = cls(url, **kwargs)
        await axon._open_ws()
        return axon

    async def _send_recv(self, method: str, params: list) -> Any:
        if not self._ws:
            raise AxonSdkError("conexão não aberta")
        _id = self._next_id
        self._next_id += 1
        request = {
            "id": _id,
            "method": method,
            "params": [encode_typed(p) for p in params],
            "version": PROTOCOL_VERSION,
        }
        fut = self._loop.create_future()
        self._pending[_id] = fut
        try:
            await self._ws.send(json.dumps(request))
            return await asyncio.wait_for(fut, self.timeout)
        finally:
            self._pending.pop(_id, None)

    async def _call(self, method: str, params: list, retries: int = 3) -> Any:
        for attempt in range(retries):
            try:
                response = await self._send_recv(method, params)
                if "error" in response:
                    error = response["error"]
                    exc = from_rpc_error(error)
                    if isinstance(exc, NotLeaderError) and exc.leader_address and attempt < retries - 1:
                        new_url = f"ws://{exc.leader_address}/rpc/ws"
                        await self._reconnect_to(new_url)
                        continue
                    raise exc
                return response.get("result")
            except (websockets.ConnectionClosed, BrokenPipeError) as e:
                if attempt < retries - 1 and self.reconnect:
                    await asyncio.sleep(self.reconnect_interval)
                    await self._open_ws()
                    if self._namespace:
                        await self._send_recv("use", [self._namespace, self._database])
                    if self._auth:
                        await self._send_recv("authenticate", [self._auth])
                    continue
                raise AxonSdkError(f"conexão perdida: {e}") from e
        raise AxonSdkError("falha após tentativas de reconexão")

    async def _reconnect_to(self, new_url: str):
        if self._ws:
            await self._ws.close()
        self._handshake_received = False
        self._hello = None
        self.url = new_url
        await self._open_ws()
        if self._namespace:
            await self._send_recv("use", [self._namespace, self._database])
        if self._auth:
            await self._send_recv("authenticate", [self._auth])

    async def _listen(self):
        while not self._closed and self._ws:
            try:
                msg = await self._ws.recv()
            except websockets.ConnectionClosed:
                break
            try:
                parsed = json.loads(msg)
            except json.JSONDecodeError:
                continue

            if isinstance(parsed, dict):
                if "notification" in parsed:
                    n = parsed["notification"]
                    handler = self._live_handlers.get(n["id"])
                    if handler:
                        handler(n["id"], n["action"], n["result"])
                    continue
                rpc_id = parsed.get("id")
                if rpc_id is not None and rpc_id in self._pending:
                    fut = self._pending.pop(rpc_id)
                    if not fut.done():
                        fut.set_result(parsed)

    # ============================================================
    # RPC methods
    # ============================================================

    async def ping(self) -> bool:
        return await self._call("ping", [])

    async def use(self, ns: str, db: str) -> dict:
        result = await self._call("use", [ns, db])
        self._namespace = result.get("namespace", ns)
        self._database = result.get("database", db)
        return result

    async def query(self, sql: str, vars: dict | None = None) -> Any:
        params = [sql, vars] if vars else [sql]
        return await self._call("query", params)

    async def signin(self, user: str, passwd: str, access: str | None = None) -> str:
        cred = {"user": user, "pass": passwd}
        if access:
            cred["access"] = access
        token = await self._call("signin", [cred])
        self._auth = token
        return token

    async def authenticate(self, token: str):
        await self._call("authenticate", [token])
        self._auth = token

    async def signup(self, user: str, passwd: str) -> str:
        token = await self._call("signup", [{"user": user, "pass": passwd}])
        self._auth = token
        return token

    async def invalidate(self):
        await self._call("invalidate", [])
        self._auth = None

    async def certificate_begin(self, store: str) -> CertificateChallenge:
        result = await self._call("certificate.begin", [{"store": store}])
        return CertificateChallenge(
            id=result["id"],
            challenge=result["challenge"],
            expires_at=result["expires_at"],
        )

    async def certificate_complete(self, completion: CertificateCompletion) -> str:
        return await self._call("certificate.complete", [{
            "id": completion.id,
            "challenge": completion.challenge,
            "store": completion.store,
            "user": completion.user,
            "chain": completion.chain,
            "signature": completion.signature,
        }])

    async def version(self) -> str:
        return await self._call("version", [])

    async def select(self, what: str) -> Any:
        return await self._call("select", [what])

    async def create(self, what: str, data: dict) -> Any:
        return await self._call("create", [what, data])

    async def update(self, what: str, data: dict) -> Any:
        return await self._call("update", [what, data])

    async def upsert(self, what: str, data: dict) -> Any:
        return await self._call("upsert", [what, data])

    async def delete(self, what: str) -> Any:
        return await self._call("delete", [what])

    async def insert(self, table: str, data: dict) -> Any:
        return await self._call("insert", [table, data])

    async def relate(self, from_: str, kind: str, to: str, data: dict | None = None) -> Any:
        return await self._call("relate", [from_, kind, to, data or {}])

    async def let_var(self, name: str, value: Any):
        clean = name[1:] if name.startswith("$") else name
        await self._send_recv("let", [clean, value])

    async def unset(self, name: str):
        clean = name[1:] if name.startswith("$") else name
        await self._send_recv("unset", [clean])

    async def begin(self):
        await self._call("begin", [])

    async def commit(self):
        await self._call("commit", [])

    async def cancel(self):
        await self._call("cancel", [])

    async def kv_get(self, ns: str, db: str, key: str) -> Any:
        return await self._call("kv_get", [ns, db, key])

    async def kv_set(self, ns: str, db: str, key: str, value: Any, ttl: int | None = None) -> Any:
        return await self._call("kv_set", [ns, db, key, value, ttl])

    async def kv_del(self, ns: str, db: str, key: str) -> bool:
        return await self._call("kv_del", [ns, db, key])

    async def kv_scan(self, ns: str, db: str, prefix: str) -> list:
        return await self._call("kv_scan", [ns, db, prefix])

    async def live(self, table: str, handler: LiveHandler, diff: bool = False) -> str:
        id_ = await self._call("live", [table, diff])
        self._live_handlers[id_] = handler
        return id_

    async def kill(self, id_: str) -> bool:
        ok = await self._call("kill", [id_])
        if ok:
            self._live_handlers.pop(id_, None)
        return ok

    # ============================================================
    # Properties
    # ============================================================

    @property
    def namespace(self) -> str:
        return self._namespace

    @property
    def database(self) -> str:
        return self._database

    @property
    def is_connected(self) -> bool:
        return self._ws is not None and not self._ws.closed

    @property
    def hello_info(self) -> dict | None:
        return self._hello

    async def close(self):
        self._closed = True
        self._live_handlers.clear()
        if self._ws:
            await self._ws.close()
            self._ws = None
