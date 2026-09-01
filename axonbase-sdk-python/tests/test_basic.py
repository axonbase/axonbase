import asyncio
from unittest.mock import AsyncMock, patch, ANY
import json
import pytest

from axonbase import Axon
from axonbase.errors import NotLeaderError, NoQuorumError, AuthError, ConflictError, from_rpc_error


def test_dbapi_authenticates_with_token_before_selecting_database(monkeypatch):
    from axonbase import dbapi

    calls = []

    class Bridge:
        def call(self, coroutine):
            return asyncio.run(coroutine)

        def stop(self):
            pass

    class Client:
        async def authenticate(self, token):
            calls.append(("authenticate", token))

        async def use(self, ns, db):
            calls.append(("use", ns, db))

        async def close(self):
            pass

    monkeypatch.setattr(dbapi, "_SyncBridge", Bridge)
    monkeypatch.setattr(dbapi.Axon, "connect", AsyncMock(return_value=Client()))

    connection = dbapi.connect(token="jwt-token", ns="private", db="records")

    assert calls == [("authenticate", "jwt-token"), ("use", "private", "records")]
    connection.close()


def test_dbapi_authenticates_with_credentials_before_selecting_database(monkeypatch):
    from axonbase import dbapi

    calls = []

    class Bridge:
        def call(self, coroutine):
            return asyncio.run(coroutine)

        def stop(self):
            pass

    class Client:
        async def signin(self, user, password):
            calls.append(("signin", user, password))

        async def use(self, ns, db):
            calls.append(("use", ns, db))

        async def close(self):
            pass

    monkeypatch.setattr(dbapi, "_SyncBridge", Bridge)
    monkeypatch.setattr(dbapi.Axon, "connect", AsyncMock(return_value=Client()))

    connection = dbapi.connect(user="alice", password="secret")

    assert calls == [("signin", "alice", "secret"), ("use", "axonbase", "main")]
    connection.close()


def test_sqlalchemy_url_passes_token_to_dbapi():
    from sqlalchemy.engine import make_url
    from axonbase.sqlalchemy_dialect import AxonBaseDialect

    _, kwargs = AxonBaseDialect().create_connect_args(
        make_url("axonbase://localhost/test?db=private&token=jwt-token")
    )

    assert kwargs["token"] == "jwt-token"


def test_protocol_constants():
    from axonbase.protocol import (
        PROTOCOL_VERSION, ERR_NOT_LEADER, ERR_NO_QUORUM,
        ERR_AUTH, ERR_TXN_CONFLICT, ERR_INVALID_REQ,
        METHODS,
    )
    assert PROTOCOL_VERSION == 1
    assert ERR_NOT_LEADER == -32010
    assert ERR_NO_QUORUM == -32011
    assert ERR_AUTH == -32002
    assert ERR_TXN_CONFLICT == -32009
    assert "ping" in METHODS
    assert "query" in METHODS
    assert "live" in METHODS
    assert "kv_get" in METHODS


def test_errors():
    err = NotLeaderError("not leader", "n1", "10.0.0.1:8000")
    assert err.code == -32010
    assert err.leader == "n1"
    assert err.leader_address == "10.0.0.1:8000"

    err2 = NoQuorumError("no quorum")
    assert err2.code == -32011

    err3 = AuthError("bad credentials")
    assert err3.code == -32002

    err4 = ConflictError("txn conflict")
    assert err4.code == -32009


def test_codec():
    from axonbase.codec import encode_typed, is_typed_value

    assert encode_typed(None) is None
    assert encode_typed(42) == 42
    assert encode_typed("hello") == "hello"
    assert encode_typed([1, "a"]) == [1, "a"]
    assert is_typed_value({"$datetime": "2026-01-01T00:00:00Z"})
    assert not is_typed_value({"name": "Ana"})
    assert encode_typed({"$decimal": "99.90"}) == {"$decimal": "99.90"}


@pytest.mark.asyncio
async def test_connect_handshake():
    saved = {}
    async def fake_send_recv(method, params):
        if method == "version":
            return {"result": "0.1.0"}
        return {"result": None}

    async def fake_open_ws(self):
        self._hello = {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping", "query"]}
        self._handshake_received = True
        self._loop = asyncio.get_running_loop()
        self._ws = AsyncMock()

    with patch.object(Axon, "_send_recv", fake_send_recv), \
         patch.object(Axon, "_open_ws", fake_open_ws), \
         patch.object(Axon, "_listen", AsyncMock()):
        ax = await Axon.connect("ws://127.0.0.1:8000/rpc/ws")
        assert ax.hello_info is not None
        assert ax.hello_info["protocol"] == 1
        await ax.close()


@pytest.mark.asyncio
async def test_ping():
    async def fake_send_recv(_, _method, params):
        return {"result": True}

    async def fake_open_ws(self):
        self._hello = {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping"]}
        self._handshake_received = True
        self._loop = asyncio.get_running_loop()
        self._ws = AsyncMock()

    with patch.object(Axon, "_send_recv", fake_send_recv), \
         patch.object(Axon, "_open_ws", fake_open_ws), \
         patch.object(Axon, "_listen", AsyncMock()):
        ax = await Axon.connect("ws://127.0.0.1:8000/rpc/ws")
        result = await ax.ping()
        assert result is True
        await ax.close()


@pytest.mark.asyncio
async def test_not_leader_reconnect():
    call_count = [0]
    async def fake_send_recv(_, method, params):
        call_count[0] += 1
        if call_count[0] == 1:
            return {"error": {"code": -32010, "message": "not leader", "kind": "NOT_LEADER", "leader": "n1", "leader_address": "10.0.0.2:8000"}}
        return {"result": "ok"}

    async def fake_open_ws(self):
        self._hello = {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["kv_set"]}
        self._handshake_received = True
        self._loop = asyncio.get_running_loop()
        self._ws = AsyncMock()

    with patch.object(Axon, "_send_recv", fake_send_recv), \
         patch.object(Axon, "_open_ws", fake_open_ws), \
         patch.object(Axon, "_listen", AsyncMock()), \
         patch.object(Axon, "_reconnect_to", AsyncMock()) as mock_reconnect:
        ax = await Axon.connect("ws://127.0.0.1:8000/rpc/ws", reconnect=True, reconnect_interval=0.01)
        try:
            await ax.kv_set("ns", "db", "k", "v")
        except Exception:
            pass
        await ax.close()
    assert mock_reconnect.called


def test_errors_from_rpc():
    e = from_rpc_error({"code": -32010, "message": "not leader", "leader": "n1", "leader_address": "addr"})
    assert isinstance(e, NotLeaderError)

    e = from_rpc_error({"code": -32011, "message": "no quorum"})
    assert isinstance(e, NoQuorumError)

    e = from_rpc_error({"code": -32002, "message": "auth"})
    assert isinstance(e, AuthError)

    e = from_rpc_error({"code": -32009, "message": "txn conflict"})
    assert isinstance(e, ConflictError)
