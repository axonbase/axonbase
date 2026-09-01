"""
SQLAlchemy dialect for AxonBase.

Usage::

    from sqlalchemy import create_engine
    engine = create_engine("axonbase://test:dev@127.0.0.1:8000?sql_translate=true")
    conn = engine.connect()
    result = conn.execute(text("SELECT name FROM person WHERE age > %(min)s"), {"min": 25})
"""

from sqlalchemy.engine.default import DefaultDialect
from sqlalchemy.engine.url import URL
from sqlalchemy.sql import compiler
from sqlalchemy import types as sa_types, util

SAGA_CORRELATION_OPTION = "axonbase_saga_correlation_id"


def with_saga_correlation(connection, correlation_id: str):
    """Returns a SQLAlchemy connection whose statements participate in a SAGA."""
    return connection.execution_options(**{SAGA_CORRELATION_OPTION: correlation_id})


class AxonBaseDialect(DefaultDialect):
    name = "axonbase"
    driver = "dbapi"

    statement_compiler = compiler.SQLCompiler
    ddl_compiler = compiler.DDLCompiler
    preparer = compiler.IdentifierPreparer

    supports_alter = False
    supports_pk = False
    supports_sequences = False
    supports_identity_columns = False
    supports_native_autoinc = False
    supports_comments = False
    supports_constraints = False
    supports_check_constraints = False
    supports_foreign_keys = False
    supports_indexes = False
    supports_unique_constraints = False
    supports_native_enum = False
    supports_native_boolean = False
    supports_native_uuid = False
    supports_statement_cache = True

    default_schema_name = None

    max_identifier_length = 255
    colspecs = {}

    @classmethod
    def import_dbapi(cls):
        from . import dbapi

        return dbapi

    def create_connect_args(self, url: URL):
        kwargs = {
            "url": f"ws://{url.host}:{url.port or 8000}/rpc/ws",
            "ns": url.database or "axonbase",
            "db": url.query.get("db", "main"),
            "sql_translate": url.query.get("sql_translate", "false").lower()
            in ("true", "1", "yes"),
        }
        if url.username:
            kwargs["user"] = url.username
        if url.password:
            kwargs["password"] = url.password
        if url.query.get("token"):
            kwargs["token"] = url.query["token"]
        return [], kwargs

    def get_columns(self, connection, table_name, schema=None, **kw):
        return []

    def get_table_names(self, connection, schema=None, **kw):
        return []

    def get_view_names(self, connection, schema=None, **kw):
        return []

    def get_foreign_keys(self, connection, table_name, schema=None, **kw):
        return []

    def get_pk_constraint(self, connection, table_name, schema=None, **kw):
        return []

    def get_indexes(self, connection, table_name, schema=None, **kw):
        return []

    def get_unique_constraints(self, connection, table_name, schema=None, **kw):
        return []

    def get_check_constraints(self, connection, table_name, schema=None, **kw):
        return []

    def get_table_comment(self, connection, table_name, schema=None, **kw):
        return {"text": None}

    def has_table(self, connection, table_name, schema=None):
        return True

    def has_sequence(self, connection, sequence_name, schema=None):
        return False

    def get_isolation_level(self, connection):
        return "SERIALIZABLE"

    def get_default_isolation_level(self, connection=None) -> str:
        return "SERIALIZABLE"

    def do_begin(self, dbapi_connection):
        dbapi_connection.begin()

    def do_rollback(self, dbapi_connection):
        dbapi_connection.rollback()

    def do_commit(self, dbapi_connection):
        dbapi_connection.commit()

    def do_close(self, dbapi_connection):
        dbapi_connection.close()

    def do_execute(self, cursor, statement, parameters, context=None):
        correlation_id = None if context is None else context.execution_options.get(
            SAGA_CORRELATION_OPTION
        )
        cursor.connection.set_saga_correlation(correlation_id)
        super().do_execute(cursor, statement, parameters, context)

    def do_executemany(self, cursor, statement, parameters, context=None):
        correlation_id = None if context is None else context.execution_options.get(
            SAGA_CORRELATION_OPTION
        )
        cursor.connection.set_saga_correlation(correlation_id)
        super().do_executemany(cursor, statement, parameters, context)


# Register via sqlalchemy.dialects registry
from sqlalchemy.dialects import registry

registry.register("axonbase", __name__, "AxonBaseDialect")
