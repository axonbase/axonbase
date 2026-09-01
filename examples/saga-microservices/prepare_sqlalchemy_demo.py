from sqlalchemy_common import engine, execute_statement

ORCHESTRATOR = engine(8000, "orchestrator")

for database, port, table in (
    ("payments", 8011, "payment"),
    ("invoices", 8012, "invoice"),
    ("stock", 8013, "stock"),
    ("stock", 8013, "stock_reservation"),
):
    participant = engine(port, database)
    execute_statement(participant, f"DEFINE TABLE {table} SCHEMAFULL")
    execute_statement(participant, f"DELETE {table}")

execute_statement(engine(8013, "stock"), "CREATE stock:main CONTENT {quantity: 10}")

for database, port in (("payments", 8011), ("invoices", 8012), ("stock", 8013)):
    execute_statement(
        ORCHESTRATOR,
        f'DEFINE DATABASE LINK "{database}" CONNECT BY "ws://127.0.0.1:{port}/rpc/ws" '
        f'WITH ns="test" db="{database}" user="" password=""',
    )

execute_statement(ORCHESTRATOR, "CREATE SAGA pedido WITH DATABASES 'payments', 'invoices', 'stock'")
print("SAGA demo is ready.")
