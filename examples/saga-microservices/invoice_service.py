import os
from urllib.request import Request, urlopen

from fastapi import FastAPI, Header, HTTPException

from common import sql

app = FastAPI(title="invoice-service")
ORCHESTRATOR = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8000")
INVOICE_DB = os.getenv("INVOICE_DB_URL", "http://127.0.0.1:8012")
STOCK_SERVICE = os.getenv("STOCK_SERVICE_URL", "http://127.0.0.1:9003")


@app.post("/invoices")
def create_invoice(order_id: str, x_correlation_id: str = Header()):
    try:
        sql(INVOICE_DB, f"CREATE invoice:{order_id} CONTENT {{order_id: '{order_id}', status: 'ISSUED'}}", "invoices", x_correlation_id)
        request = Request(f"{STOCK_SERVICE}/reservations?order_id={order_id}", headers={"X-Correlation-Id": x_correlation_id}, method="POST")
        with urlopen(request, timeout=10) as response:
            response.read()
        return {"order_id": order_id, "status": "ISSUED"}
    except Exception as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
