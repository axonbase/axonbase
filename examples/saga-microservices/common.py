import json
from urllib.request import Request, urlopen


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
