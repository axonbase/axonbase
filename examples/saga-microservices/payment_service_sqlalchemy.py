import os
import uuid
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from fastapi import FastAPI, Header, HTTPException
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from axonbase.sqlalchemy import SagaExecuteTransaction
from sqlalchemy_common import engine

app = FastAPI(title="payment-service-sqlalchemy")
INVOICE_SERVICE = os.getenv("INVOICE_SERVICE_URL", "http://127.0.0.1:9102")
PAYMENTS = engine(8011, "payments")
ORCHESTRATOR_DB = engine(8000, "orchestrator")


class Base(DeclarativeBase):
    pass


class Payment(Base):
    __tablename__ = "payment"

    id: Mapped[str] = mapped_column(primary_key=True)
    order_id: Mapped[str]
    status: Mapped[str]


@app.post("/orders")
def create_order(
    order_id: str,
    fail_at: str | None = None,
    x_correlation_id: str | None = Header(default=None),
):
    correlation_id = x_correlation_id or f"order_{uuid.uuid4().hex}"
    if fail_at not in {None, "invoice", "stock"}:
        raise HTTPException(status_code=422, detail="fail_at must be invoice or stock")
    try:
        with SagaExecuteTransaction(ORCHESTRATOR_DB, PAYMENTS, "pedido") as transaction:
            transaction.begin(correlation_id)
            transaction.add(Payment(id=order_id, order_id=order_id, status="PAID"))
            query = urlencode({"order_id": order_id, **({"fail_at": fail_at} if fail_at else {})})
            request = Request(f"{INVOICE_SERVICE}/invoices?{query}", headers={"X-Correlation-Id": correlation_id}, method="POST")
            with urlopen(request, timeout=10) as response:
                response.read()
            transaction.commit()
        return {"order_id": order_id, "correlation_id": correlation_id, "status": "COMMITTED"}
    except Exception as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
