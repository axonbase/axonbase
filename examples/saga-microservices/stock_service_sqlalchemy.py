from fastapi import FastAPI, Header, HTTPException
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from axonbase.sqlalchemy import SagaExecuteTransaction
from sqlalchemy_common import engine

app = FastAPI(title="stock-service-sqlalchemy")
STOCK = engine(8013, "stock")


class Base(DeclarativeBase):
    pass


class StockReservation(Base):
    __tablename__ = "stock_reservation"

    id: Mapped[str] = mapped_column(primary_key=True)
    order_id: Mapped[str]
    status: Mapped[str]


@app.post("/reservations")
def reserve_stock(
    order_id: str,
    fail_at: str | None = None,
    x_correlation_id: str = Header(),
):
    try:
        with SagaExecuteTransaction(STOCK, "pedido") as transaction:
            transaction.begin(x_correlation_id)
            transaction.add(StockReservation(id=order_id, order_id=order_id, status="RESERVED"))
            if fail_at == "stock":
                raise RuntimeError("simulated stock failure")
            transaction.commit()
        return {"order_id": order_id, "status": "RESERVED"}
    except Exception as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
