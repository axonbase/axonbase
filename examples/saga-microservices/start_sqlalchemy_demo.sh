#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXAMPLE_DIR="$ROOT/examples/saga-microservices"
DATA_DIR="${AXONBASE_SAGA_DEMO_DATA_DIR:-/tmp/axonbase-saga-demo}"

cd "$ROOT"
mvn -pl axonbase-server -am -DskipTests compile -q

CLASSPATH=""
for module in axonbase-common axonbase-value axonbase-parser axonbase-core axonbase-server; do
  CLASSPATH="$CLASSPATH:$ROOT/$module/target/classes"
done
CLASSPATH="$CLASSPATH:$(find "$HOME/.m2/repository/org/eclipse/jetty" "$HOME/.m2/repository/org/slf4j" -name '*.jar' | tr '\n' ':')"

start_database() {
  local name="$1"
  local port="$2"
  nohup java -cp "$CLASSPATH" com.axonbase.server.Main start \
    --config "$ROOT/axonbase.conf.example" \
    --path "$DATA_DIR/$name" \
    --port "$port" \
    --no-auth >"$DATA_DIR/$name.log" 2>&1 </dev/null &
}

mkdir -p "$DATA_DIR"
start_database orchestrator 8000
start_database payments 8011
start_database invoices 8012
start_database stock 8013

PYTHONPATH="$ROOT/axonbase-sdk-python" nohup python3 -m uvicorn payment_service_sqlalchemy:app --app-dir "$EXAMPLE_DIR" --port 9101 >"$DATA_DIR/payment-service.log" 2>&1 </dev/null &
PYTHONPATH="$ROOT/axonbase-sdk-python" nohup python3 -m uvicorn invoice_service_sqlalchemy:app --app-dir "$EXAMPLE_DIR" --port 9102 >"$DATA_DIR/invoice-service.log" 2>&1 </dev/null &
PYTHONPATH="$ROOT/axonbase-sdk-python" nohup python3 -m uvicorn stock_service_sqlalchemy:app --app-dir "$EXAMPLE_DIR" --port 9103 >"$DATA_DIR/stock-service.log" 2>&1 </dev/null &

sleep 3
PYTHONPATH="$ROOT/axonbase-sdk-python" python3 "$EXAMPLE_DIR/prepare_sqlalchemy_demo.py"
printf 'Services are ready at http://127.0.0.1:9101. Logs: %s\n' "$DATA_DIR"
