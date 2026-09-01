import os

from fastapi import FastAPI, Header, HTTPException

from common import sql

app = FastAPI(title="stock-service")
ORCHESTRATOR = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8000")
STOCK_DB = os.getenv("STOCK_DB_URL", "http://127.0.0.1:8013")


@app.post("/reservations")
def reserve_stock(order_id: str, x_correlation_id: str = Header()):
    try:
        sql(STOCK_DB, "UPDATE stock:main SET quantity = quantity - 1", "stock", x_correlation_id)
        sql(ORCHESTRATOR, f"COMMIT SAGA pedido WITH CORRELATION '{x_correlation_id}'", "orchestrator", x_correlation_id)
        return {"order_id": order_id, "status": "RESERVED"}
    except Exception as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
