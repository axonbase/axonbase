import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from fastapi import FastAPI, Header, HTTPException
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from axonbase.sqlalchemy import SagaExecuteTransaction
from sqlalchemy_common import engine

app = FastAPI(title="invoice-service-sqlalchemy")
STOCK_SERVICE = os.getenv("STOCK_SERVICE_URL", "http://127.0.0.1:9103")
INVOICES = engine(8012, "invoices")


class Base(DeclarativeBase):
    pass


class Invoice(Base):
    __tablename__ = "invoice"

    id: Mapped[str] = mapped_column(primary_key=True)
    order_id: Mapped[str]
    status: Mapped[str]


@app.post("/invoices")
def create_invoice(
    order_id: str,
    fail_at: str | None = None,
    x_correlation_id: str = Header(),
):
    try:
        with SagaExecuteTransaction(INVOICES, "pedido") as transaction:
            transaction.begin(x_correlation_id)
            transaction.add(Invoice(id=order_id, order_id=order_id, status="ISSUED"))
            if fail_at == "invoice":
                raise RuntimeError("simulated invoice failure")
            query = urlencode({"order_id": order_id, **({"fail_at": fail_at} if fail_at else {})})
            request = Request(f"{STOCK_SERVICE}/reservations?{query}", headers={"X-Correlation-Id": x_correlation_id}, method="POST")
            with urlopen(request, timeout=10) as response:
                response.read()
            transaction.commit()
        return {"order_id": order_id, "status": "ISSUED"}
    except Exception as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
