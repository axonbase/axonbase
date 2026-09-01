"""
AxonBase DBAPI 2.0 driver (PEP-249).

Usage::

    import axonbase.dbapi as dbapi
    conn = dbapi.connect(url="ws://127.0.0.1:8000/rpc/ws", ns="test", db="dev")
    cur = conn.cursor()
    cur.execute("SELECT name FROM person WHERE age > %(min)s", {"min": 25})
    for row in cur.fetchall():
        print(row)
"""

import asyncio
import threading
from collections.abc import Callable
from typing import Any

from .client import Axon

apilevel = "2.0"
threadsafety = 2
paramstyle = "pyformat"


class Error(Exception):
    pass


class Warning(Exception):
    pass


class InterfaceError(Error):
    pass


class DatabaseError(Error):
    pass


class OperationalError(DatabaseError):
    pass


class IntegrityError(DatabaseError):
    pass


class InternalError(DatabaseError):
    pass


class ProgrammingError(DatabaseError):
    pass


class NotSupportedError(DatabaseError):
    pass


class DataError(DatabaseError):
    pass


# ─── Sync Bridge ───────────────────────────────────────────────────────────


class _SyncBridge:
    """Runs an asyncio event loop in a background thread."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def call(self, coro) -> Any:
        """Run a coroutine in the background loop and block for the result."""
        future = asyncio.run_coroutine_threadsafe(coro, self.loop)
        return future.result()

    def stop(self) -> None:
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=5)


# ─── Connection ────────────────────────────────────────────────────────────


class Connection:
    """AxonBase DBAPI connection."""

    def __init__(
        self,
        url: str,
        ns: str = "axonbase",
        db: str = "main",
        sql_translate: bool = False,
        user: str | None = None,
        password: str | None = None,
        token: str | None = None,
        **kwargs,
    ):
        self._bridge = _SyncBridge()
        self._closed = False
        self._axon = self._bridge.call(Axon.connect(url))
        if token and (user is not None or password is not None):
            self.close()
            raise InterfaceError("Configure either token or user credentials, not both")
        if token:
            self._bridge.call(self._axon.authenticate(token))
        elif user is not None or password is not None:
            if not user or password is None:
                self.close()
                raise InterfaceError("Both user and password are required for authentication")
            self._bridge.call(self._axon.signin(user, password))
        self._bridge.call(self._axon.use(ns, db))
        self._sql_translate = sql_translate
        self._pending_results: list[list[dict]] = []

    @property
    def axon(self) -> Axon:
        return self._axon

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._bridge.call(self._axon.close())
        self._bridge.stop()

    def begin(self) -> None:
        self._bridge.call(self._axon.begin())

    def commit(self) -> None:
        self._bridge.call(self._axon.commit())

    def rollback(self) -> None:
        self._bridge.call(self._axon.cancel())

    def set_saga_correlation(self, correlation_id: str | None) -> None:
        """Sets the SAGA correlation for subsequent statements on this connection."""
        if correlation_id:
            escaped = correlation_id.replace("'", "''")
            self._bridge.call(self._axon.query(f"LET $saga_corr = '{escaped}'"))
        else:
            self._bridge.call(self._axon.query("LET $saga_corr = NULL"))

    def cursor(self) -> "Cursor":
        return Cursor(self)

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        self.close()


# ─── Cursor ────────────────────────────────────────────────────────────────


class Cursor:
    """AxonBase DBAPI cursor."""

    def __init__(self, connection: Connection):
        self.connection = connection
        self._results: list[dict] = []
        self._index = 0
        self._description: list[tuple] = []
        self._rowcount = -1
        self.arraysize = 1
        self._closed = False

    @property
    def description(self):
        if not self._description:
            return None
        return self._description

    @property
    def rowcount(self) -> int:
        return self._rowcount

    def close(self) -> None:
        self._closed = True
        self._results = []

    def execute(self, operation: str, parameters: dict | None = None) -> None:
        if self._closed:
            raise ProgrammingError("Cursor is closed")
        sql = self._translate(operation)
        if parameters:
            sql = sql % {k: self._escape(v) for k, v in parameters.items()}
        raw = self.connection._bridge.call(
            self.connection.axon.query(sql)
        )
        self._index = 0
        if isinstance(raw, list) and raw:
            self._results = raw
            self._rowcount = len(raw)
            if self._results:
                self._description = [
                    (str(k), None, None, None, None, None, None)
                    for k in self._results[0].keys()
                ]
            else:
                self._description = []
        elif isinstance(raw, dict):
            self._results = [raw]
            self._rowcount = 1
            self._description = [
                (str(k), None, None, None, None, None, None)
                for k in raw.keys()
            ]
        else:
            self._results = []
            self._rowcount = 0
            self._description = []

    def executemany(self, operation: str, seq_params: list[dict]) -> None:
        for params in seq_params:
            self.execute(operation, params)

    def fetchone(self) -> tuple | None:
        if self._index >= len(self._results):
            return None
        row = self._results[self._index]
        self._index += 1
        return tuple(row.values()) if isinstance(row, dict) else row

    def fetchmany(self, size: int | None = None) -> list[tuple]:
        if size is None:
            size = self.arraysize
        start = self._index
        end = min(start + size, len(self._results))
        rows = [
            tuple(r.values()) if isinstance(r, dict) else r
            for r in self._results[start:end]
        ]
        self._index = end
        return rows

    def fetchall(self) -> list[tuple]:
        rows = [
            tuple(r.values()) if isinstance(r, dict) else r
            for r in self._results[self._index:]
        ]
        self._index = len(self._results)
        return rows

    def setinputsizes(self, sizes) -> None:
        pass

    def setoutputsizes(self, sizes) -> None:
        pass

    def __iter__(self):
        return iter(self.fetchall())

    # ─── SQL translation (if sql_translate=True) ──────────────────────────

    _TYPE_MAP = {
        "bigint": "int",
        "integer": "int",
        "int": "int",
        "smallint": "int",
        "tinyint": "int",
        "numeric": "decimal",
        "decimal": "decimal",
        "real": "float",
        "double": "float",
        "float": "float",
        "varchar": "string",
        "char": "string",
        "text": "string",
        "clob": "string",
        "timestamp": "datetime",
        "datetime": "datetime",
        "date": "datetime",
        "boolean": "bool",
        "bool": "bool",
        "blob": "bytes",
        "bytes": "bytes",
    }

    def _translate(self, sql: str) -> str:
        if not self.connection._sql_translate:
            return sql
        trimmed = sql.strip()
        if not trimmed:
            return ""

        first = trimmed.split(None, 1)[0].upper() if trimmed else ""

        handlers = {
            "CREATE": self._t_create,
            "INSERT": self._t_insert,
            "SELECT": self._t_select,
            "UPDATE": self._t_update,
            "DELETE": self._t_delete,
            "DROP": self._t_drop,
            "ALTER": self._t_alter,
            "TRUNCATE": self._t_truncate,
        }
        handler = handlers.get(first)
        if handler:
            return handler(trimmed)
        return sql

    def _t_create(self, sql: str) -> str:
        import re

        m = re.match(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.+)\)\s*;?\s*$",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if not m:
            return sql
        table = m.group(1)
        raw_cols = m.group(2)
        cols = self._parse_cols(raw_cols)
        parts = [f"DEFINE TABLE {table} SCHEMAFULL;"]
        for name, typ in cols:
            if name.lower() == "id":
                continue
            at = self._TYPE_MAP.get(typ.lower(), typ.lower())
            parts.append(f"DEFINE FIELD {name} ON TABLE {table} TYPE {at};")
        return " ".join(parts)

    def _parse_cols(self, raw: str) -> list[tuple[str, str]]:
        import re

        cols = []
        depth = 0
        cur = []
        in_str = False
        qc = ""
        for c in raw:
            if in_str:
                cur.append(c)
                if c == qc:
                    in_str = False
                continue
            if c in ("'", '"'):
                in_str = True
                qc = c
                cur.append(c)
                continue
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            if c == "," and depth == 0:
                parts = self._col_def("".join(cur))
                if parts:
                    cols.append(parts)
                cur = []
            else:
                cur.append(c)
        last = self._col_def("".join(cur))
        if last:
            cols.append(last)
        return cols

    def _col_def(self, s: str) -> tuple[str, str] | None:
        import re

        s = re.sub(r"\s+GENERATED\s+BY\s+DEFAULT\s+AS\s+IDENTITY", "", s, flags=re.I)
        s = re.sub(r"\s+GENERATED\s+ALWAYS\s+AS\s+IDENTITY", "", s, flags=re.I)
        s = re.sub(r"\s+AS\s+IDENTITY", "", s, flags=re.I)
        s = re.sub(r"\s+(NOT\s+)?NULL\b", "", s, flags=re.I)
        s = re.sub(r"\s+DEFAULT\s+\S+", "", s, flags=re.I)
        s = re.sub(r"\s+UNIQUE\b", "", s, flags=re.I)
        s = s.strip()
        if not s or s.upper().startswith(("PRIMARY", "UNIQUE", "FOREIGN", "CONSTRAINT", "CHECK", "INDEX")):
            return None
        parts = s.split(None, 1)
        if not parts:
            return None
        name = parts[0]
        typ = (parts[1] if len(parts) > 1 else "string").split("(")[0]
        return name, typ.strip().upper()

    def _t_insert(self, sql: str) -> str:
        import re

        m = re.match(
            r"INSERT\s+INTO\s+(\w+)\s*\(([^)]+)\)\s*VALUES\s*\((.+)\)\s*;?\s*$",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if not m:
            return sql
        table = m.group(1)
        col_names = [c.strip() for c in m.group(2).split(",")]
        vals = self._split_values(m.group(3))
        parts = [f"{n}: {v}" for n, v in zip(col_names, vals) if n.strip() != ""]
        return f"CREATE {table} CONTENT {{{', '.join(parts)}}}"

    def _split_values(self, s: str) -> list[str]:
        depth = 0
        in_str = False
        qc = ""
        cur = []
        out = []
        for c in s:
            if in_str:
                cur.append(c)
                if c == qc:
                    in_str = False
                continue
            if c in ("'", '"'):
                in_str = True
                qc = c
                cur.append(c)
                continue
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            if c == "," and depth == 0:
                out.append("".join(cur).strip())
                cur = []
            else:
                cur.append(c)
        last = "".join(cur).strip()
        if last:
            out.append(last)
        return out

    def _t_select(self, sql: str) -> str:
        import re

        if not re.search(r"\bFROM\b", sql, re.I):
            return sql
        sql = re.sub(r"COUNT\s*\(\s*\*\s*\)", "count()", sql, flags=re.I)
        sql = re.sub(r"COUNT\s*\(\s*[^)]+\s*\)", "count()", sql, flags=re.I)
        sql = re.sub(r"\b(\w+)\.", "", sql)
        sql = re.sub(r"\bFROM\s+(\w+)\s+(?!WHERE|ORDER|LIMIT|GROUP|HAVING|INNER|LEFT|RIGHT|CROSS|JOIN|AS)(\w+)", r"FROM \1", sql, flags=re.I)
        return sql

    def _t_update(self, sql: str) -> str:
        import re

        m = re.match(
            r"UPDATE\s+(\w+)\s+SET\s+(.+?)(?:\s+WHERE\s+(.+?))?\s*;?\s*$",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if not m:
            return sql
        table = m.group(1)
        set_clause = m.group(2)
        where = m.group(3)
        result = f"UPDATE {table} SET {set_clause}"
        if where:
            result += f" WHERE {where}"
        return result

    def _t_delete(self, sql: str) -> str:
        import re

        m = re.match(
            r"DELETE\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+?))?\s*;?\s*$",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if not m:
            return sql
        table = m.group(1)
        where = m.group(2)
        result = f"DELETE {table}"
        if where:
            result += f" WHERE {where}"
        return result

    def _t_drop(self, sql: str) -> str:
        import re

        m = re.match(
            r"DROP\s+TABLE(?:\s+IF\s+EXISTS)?\s+(\w+)(?:\s+IF\s+EXISTS)?\s*;?\s*$",
            sql,
            re.IGNORECASE,
        )
        if not m:
            return sql
        return f"DELETE {m.group(1)}"

    def _t_alter(self, sql: str) -> str:
        import re

        m = re.match(
            r"ALTER\s+TABLE\s+(\w+)\s+ADD\s+(?:COLUMN\s+)?(.+?)\s*;?\s*$",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if not m:
            return sql
        table = m.group(1)
        raw = m.group(2)
        parts = raw.split(None, 1)
        name = parts[0]
        typ = (parts[1] if len(parts) > 1 else "string").split("(")[0]
        at = self._TYPE_MAP.get(typ.strip().lower(), typ.strip().lower())
        return f"DEFINE FIELD {name} ON TABLE {table} TYPE {at}"

    def _t_truncate(self, sql: str) -> str:
        import re

        m = re.match(
            r"TRUNCATE\s+TABLE\s+(\w+)\s*;?\s*$", sql, re.IGNORECASE
        )
        if not m:
            return sql
        return f"DELETE {m.group(1)}"

    @staticmethod
    def _escape(v: Any) -> str:
        if v is None:
            return "NULL"
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        escaped = str(v).replace("'", "''")
        return f"'{escaped}'"


# ─── Module-level helpers ──────────────────────────────────────────────────


def connect(
    url: str = "ws://127.0.0.1:8000/rpc/ws",
    ns: str = "axonbase",
    db: str = "main",
    sql_translate: bool = False,
    user: str | None = None,
    password: str | None = None,
    token: str | None = None,
    **kwargs,
) -> Connection:
    """Create a new DBAPI connection."""
    return Connection(url=url, ns=ns, db=db, sql_translate=sql_translate, user=user,
                      password=password, token=token, **kwargs)
