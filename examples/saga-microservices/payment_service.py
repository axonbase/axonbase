import os
import uuid
from urllib.request import Request, urlopen

from fastapi import FastAPI, Header, HTTPException

from common import sql

app = FastAPI(title="payment-service")
ORCHESTRATOR = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8000")
PAYMENT_DB = os.getenv("PAYMENT_DB_URL", "http://127.0.0.1:8011")
INVOICE_SERVICE = os.getenv("INVOICE_SERVICE_URL", "http://127.0.0.1:9002")


@app.post("/orders")
def create_order(order_id: str, x_correlation_id: str | None = Header(default=None)):
    correlation_id = x_correlation_id or f"order_{uuid.uuid4().hex}"
    try:
        sql(ORCHESTRATOR, f"BEGIN SAGA pedido WITH CORRELATION '{correlation_id}'", "orchestrator", correlation_id)
        sql(PAYMENT_DB, f"CREATE payment:{order_id} CONTENT {{order_id: '{order_id}', status: 'PAID'}}", "payments", correlation_id)
        request = Request(f"{INVOICE_SERVICE}/invoices?order_id={order_id}", headers={"X-Correlation-Id": correlation_id}, method="POST")
        with urlopen(request, timeout=10) as response:
            response.read()
        return {"order_id": order_id, "correlation_id": correlation_id, "status": "COMMITTED"}
    except Exception as error:
        try:
            sql(ORCHESTRATOR, f"CANCEL SAGA pedido WITH CORRELATION '{correlation_id}'", "orchestrator", correlation_id)
        except Exception:
            pass
        raise HTTPException(status_code=409, detail=str(error)) from error
