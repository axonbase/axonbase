"""
AxonBase SagaTransaction — transações distribuídas via SAGA.

Uso::

    from axonbase.saga import SagaTransaction

    saga = SagaTransaction(axon, "pedido", "corr-123")
    await saga.begin()

    # step 1: banco 1
    await saga.step("UPDATE orders:o1 SET total = 200")

    # step 2: banco 2 (via DATABASE LINK)
    await saga.step('UPDATE "bank2"."stock":s1 SET qty = 9')

    # Confirma
    await saga.commit()

    # Ou em caso de falha:
    # await saga.rollback()
"""

import json


class SagaTransaction:
    """Transação SAGA sobre AxonBase.

    O ledger fica em ``system.saga`` (orquestrador) e a captura do
    estado anterior (before) é feita automaticamente pelo servidor
    quando a variável de sessão ``$saga_corr`` está definida.
    """

    def __init__(self, axon, saga_name: str, correlation_id: str):
        self.axon = axon
        self.saga_name = saga_name
        self.correlation_id = correlation_id
        self.begun = False
        self.finished = False

    async def begin(self):
        if self.begun:
            raise RuntimeError("Saga already begun")
        result = await self.axon.query(
            f"BEGIN SAGA {_esc(self.saga_name)} WITH CORRELATION "
            f"'{_esc(self.correlation_id)}'"
        )
        if isinstance(result, dict) and result.get("status") == "RUNNING":
            self.begun = True
        else:
            raise RuntimeError(f"Failed to begin saga: {result}")

    async def step(self, axonql: str):
        """Executa uma operação dentro da saga.

        Define ``$saga_corr`` na sessão para que o servidor capture
        automaticamente o estado anterior (before) do registro.

        Args:
            axonql: sentença AxonQL (ex: ``UPDATE orders:o1 SET total = 200``)
        """
        if not self.begun:
            raise RuntimeError("Saga not begun")
        if self.finished:
            raise RuntimeError("Saga already finished")
        # A variável de sessão faz o servidor capturar o before automaticamente
        await self.axon.query(
            f"LET $saga_corr = '{_esc(self.correlation_id)}'"
        )
        return await self.axon.query(axonql)

    async def commit(self):
        if not self.begun:
            raise RuntimeError("Saga not begun")
        if self.finished:
            raise RuntimeError("Saga already finished")
        result = await self.axon.query(
            f"COMMIT SAGA {_esc(self.saga_name)} WITH CORRELATION "
            f"'{_esc(self.correlation_id)}'"
        )
        if not isinstance(result, dict) or result.get("status") != "COMMITTED":
            raise RuntimeError(f"Saga commit failed: {result}")
        self.finished = True
        await self.axon.query("LET $saga_corr = NULL")

    async def rollback(self):
        """Cancela a saga: dispara compensação reversa automática."""
        if not self.begun or self.finished:
            return
        result = await self.axon.query(
            f"CANCEL SAGA {_esc(self.saga_name)} WITH CORRELATION "
            f"'{_esc(self.correlation_id)}'"
        )
        if not isinstance(result, dict) or result.get("status") not in {"CANCELLED", "FAILED"}:
            raise RuntimeError(f"Saga rollback failed: {result}")
        self.finished = True
        await self.axon.query("LET $saga_corr = NULL")

    async def describe(self):
        return await self.axon.query(
            f"SHOW SAGA TRANSACTION {_esc(self.saga_name)} "
            f"'{_esc(self.correlation_id)}'"
        )

    async def __aenter__(self):
        await self.begin()
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        if exc_type is None:
            await self.commit()
        else:
            await self.rollback()
        return False


def _esc(s: str) -> str:
    return s.replace("'", "''").replace("\\", "\\\\")
