"""SQLAlchemy ORM helpers for AxonBase SAGAs."""

from sqlalchemy import text
from sqlalchemy.orm import Session

from .sqlalchemy_dialect import with_saga_correlation


class SagaExecuteTransaction:
    """Coordinates an orchestrator SAGA and writes ORM entities to one local engine.

    The local session is flushed after every mutation so AxonBase records the
    compensation step before another microservice is called.
    """

    def __init__(self, orchestrator_engine, local_engine=None, saga_name: str | None = None):
        self._participant = saga_name is None
        if self._participant:
            self.orchestrator_engine = None
            self.local_engine = orchestrator_engine
            self.saga_name = local_engine
        else:
            self.orchestrator_engine = orchestrator_engine
            self.local_engine = local_engine
            self.saga_name = saga_name
        if not self.saga_name:
            raise ValueError("saga_name is required")
        self.correlation_id = None
        self.session = None
        self._started = False
        self._finished = False

    def begin(self, correlation_id: str):
        if self._started:
            raise RuntimeError("Saga transaction already begun")

        self.correlation_id = correlation_id
        if not self._participant:
            self._execute_orchestrator(
                f"BEGIN SAGA {self.saga_name} WITH CORRELATION '{correlation_id}'"
            )
        self.session = Session(self.local_engine)
        with_saga_correlation(self.session.connection(), correlation_id)
        self._started = True
        return self

    def add(self, entity):
        self._require_active()
        self.session.add(entity)
        self.session.flush()
        return entity

    def update(self, entity):
        self._require_active()
        merged = self.session.merge(entity)
        self.session.flush()
        return merged

    def delete(self, entity):
        self._require_active()
        self.session.delete(entity)
        self.session.flush()

    def flush(self):
        self._require_active()
        self.session.flush()

    def commit(self):
        self._require_active()
        try:
            self.session.commit()
            if not self._participant:
                self._execute_orchestrator(
                    f"COMMIT SAGA {self.saga_name} WITH CORRELATION '{self.correlation_id}'"
                )
            self._finished = True
        except Exception:
            self.rollback()
            raise
        finally:
            if self._finished:
                self.session.close()

    def rollback(self):
        if not self._started or self._finished:
            return
        try:
            self.session.rollback()
            if not self._participant:
                self._execute_orchestrator(
                    f"CANCEL SAGA {self.saga_name} WITH CORRELATION '{self.correlation_id}'"
                )
        finally:
            self._finished = True
            self.session.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        if not self._finished:
            self.rollback()
        return False

    def _execute_orchestrator(self, statement: str):
        with self.orchestrator_engine.begin() as connection:
            with_saga_correlation(connection, self.correlation_id).execute(text(statement))

    def _require_active(self):
        if not self._started:
            raise RuntimeError("Saga transaction not begun")
        if self._finished:
            raise RuntimeError("Saga transaction already finished")
