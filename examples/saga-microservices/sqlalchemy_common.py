from sqlalchemy import create_engine, text

import axonbase.sqlalchemy_dialect  # Registers the axonbase dialect.
from axonbase.sqlalchemy_dialect import with_saga_correlation


def engine(port: int, database: str):
    return create_engine(
        f"axonbase://127.0.0.1:{port}/test?db={database}&sql_translate=true"
    )


def execute_statement(db_engine, statement: str, correlation_id: str | None = None):
    with db_engine.begin() as connection:
        if correlation_id:
            connection = with_saga_correlation(connection, correlation_id)
        connection.execute(text(statement))
