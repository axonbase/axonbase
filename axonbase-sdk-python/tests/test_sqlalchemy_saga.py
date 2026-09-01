from unittest.mock import patch

from axonbase.sqlalchemy import SagaExecuteTransaction


class Connection:
    def __init__(self, statements):
        self.statements = statements
        self.options = {}

    def execution_options(self, **options):
        self.options.update(options)
        return self

    def execute(self, statement):
        self.statements.append(str(statement))


class Engine:
    def __init__(self):
        self.statements = []

    def begin(self):
        connection = Connection(self.statements)

        class Context:
            def __enter__(self):
                return connection

            def __exit__(self, exc_type, exc, traceback):
                return False

        return Context()


class LocalSession:
    def __init__(self, engine):
        self.connection_value = Connection([])
        self.added = []
        self.deleted = []
        self.committed = False
        self.rolled_back = False
        self.closed = False

    def connection(self):
        return self.connection_value

    def add(self, entity):
        self.added.append(entity)

    def merge(self, entity):
        return entity

    def delete(self, entity):
        self.deleted.append(entity)

    def flush(self):
        pass

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True


def test_commit_flushes_local_entities_and_completes_saga():
    orchestrator = Engine()
    local = LocalSession(None)
    with patch("axonbase.sqlalchemy.Session", return_value=local):
        transaction = SagaExecuteTransaction(orchestrator, object(), "pedido")
        transaction.begin("corr_1")
        transaction.add(object())
        transaction.commit()

    assert local.committed
    assert local.connection_value.options["axonbase_saga_correlation_id"] == "corr_1"
    assert orchestrator.statements == [
        "BEGIN SAGA pedido WITH CORRELATION 'corr_1'",
        "COMMIT SAGA pedido WITH CORRELATION 'corr_1'",
    ]


def test_context_manager_cancels_saga_after_error():
    orchestrator = Engine()
    local = LocalSession(None)
    with patch("axonbase.sqlalchemy.Session", return_value=local):
        try:
            with SagaExecuteTransaction(orchestrator, object(), "pedido") as transaction:
                transaction.begin("corr_2")
                transaction.add(object())
                raise RuntimeError("invoice failed")
        except RuntimeError:
            pass

    assert local.rolled_back
    assert orchestrator.statements[-1] == "CANCEL SAGA pedido WITH CORRELATION 'corr_2'"


def test_participant_commits_only_its_local_session():
    local = LocalSession(None)
    with patch("axonbase.sqlalchemy.Session", return_value=local):
        transaction = SagaExecuteTransaction(object(), "pedido")
        transaction.begin("corr_3")
        transaction.add(object())
        transaction.commit()

    assert local.committed
    assert local.connection_value.options["axonbase_saga_correlation_id"] == "corr_3"
