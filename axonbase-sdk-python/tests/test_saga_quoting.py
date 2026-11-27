import asyncio

from axonbase.saga import SagaTransaction


class FakeAxon:
    def __init__(self):
        self.queries = []

    async def query(self, sql):
        self.queries.append(sql)
        return {"status": "COMMITTED" if sql.startswith("COMMIT") else "RUNNING"}


def run_saga(name, correlation_id):
    axon = FakeAxon()

    async def scenario():
        saga = SagaTransaction(axon, name, correlation_id)
        await saga.begin()
        await saga.step("RETURN 1")
        await saga.describe()
        await saga.commit()

    asyncio.run(scenario())
    return axon.queries


def test_quote_and_backslash_are_escaped_with_backslash():
    queries = run_saga("order's", "corr\\id'")

    assert queries[:4] == [
        "BEGIN SAGA 'order\\'s' WITH CORRELATION 'corr\\\\id\\''",
        "LET $saga_corr = 'corr\\\\id\\''",
        "RETURN 1",
        "SHOW SAGA TRANSACTION 'order\\'s' 'corr\\\\id\\''",
    ]


def test_name_outside_identifier_format_is_quoted():
    queries = run_saga("z; DELETE alvo; --\n", "k\n")

    assert queries[0] == "BEGIN SAGA 'z; DELETE alvo; --\\n' WITH CORRELATION 'k\\n'"


def test_simple_name_stays_bare():
    assert run_saga("pedido", "corr-1")[0] == "BEGIN SAGA pedido WITH CORRELATION 'corr-1'"
