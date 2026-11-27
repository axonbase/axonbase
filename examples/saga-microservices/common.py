import json
import re
from urllib.request import Request, urlopen

from fastapi import HTTPException

_IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,128}")


def identifier(value: str) -> str:
    """Recusa o dado de fora antes de ele entrar no texto de um comando."""
    if not _IDENTIFIER.fullmatch(value):
        raise HTTPException(status_code=422, detail="only letters, digits, underscores and hyphens are accepted")
    return value


def sql(url: str, statement: str, database: str, correlation_id: str | None = None):
    headers = {
        "Content-Type": "text/plain",
        "Axon-Ns": "test",
        "Axon-Db": database,
    }
    if correlation_id:
        headers["X-Correlation-Id"] = correlation_id
    request = Request(f"{url}/sql", statement.encode(), headers, method="POST")
    with urlopen(request, timeout=10) as response:
        result = json.loads(response.read().decode())
    if isinstance(result, dict) and result.get("status") == "ERR":
        raise RuntimeError(result["detail"])
    return result
