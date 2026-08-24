#!/usr/bin/env bash
set -euo pipefail

compose="docker compose -f docker-compose.cluster.yml"
ports=(18001 18002 18003)
headers=(-H 'Axon-Ns: app' -H 'Axon-Db: main')

cleanup() {
  code=$?
  if [[ $code -ne 0 ]]; then
    echo "Teste falhou; containers preservados para diagnóstico." >&2
    $compose ps >&2 || true
    $compose logs --tail=100 >&2 || true
    return
  fi
  $compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

ready() { curl -fsS "http://127.0.0.1:$1/ready" >/dev/null; }
status() { curl -fsS "http://127.0.0.1:$1/status"; }

leader_port() {
  for p in "${ports[@]}"; do
    local s
    s=$(status "$p" 2>/dev/null || true)
    local node leader
    node=$(printf '%s' "$s" | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("node_id", ""))' 2>/dev/null || true)
    leader=$(printf '%s' "$s" | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("leader", ""))' 2>/dev/null || true)
    if [[ -n "$node" && "$node" == "$leader" ]]; then printf '%s' "$p"; return; fi
  done
  return 1
}

wait_for_leader() {
  for _ in $(seq 1 60); do
    if leader_port >/dev/null; then return; fi
    sleep 1
  done
  echo "leader não eleito" >&2
  $compose logs >&2
  return 1
}

query() { curl -fsS -X POST "http://127.0.0.1:$1/sql" "${headers[@]}" --data "$2"; }

echo 'Subindo três nós...'
$compose up --build -d >/dev/null
wait_for_leader
leader=$(leader_port)
echo "Líder inicial: $leader"

echo 'Criando schema, identidade e dado no líder...'
query "$leader" 'DEFINE TABLE person SCHEMAFULL; DEFINE FIELD name ON TABLE person TYPE string; DEFINE USER test ON DATABASE PASSWORD "senha"; CREATE person:ana CONTENT {name:"Ana"}' >/dev/null

echo 'Verificando replicação dos dados nos seguidores...'
for p in "${ports[@]}"; do
  for _ in $(seq 1 20); do
    result=$(query "$p" 'SELECT * FROM person' 2>/dev/null || true)
    [[ "$result" == *'"Ana"'* ]] && break
    sleep 1
  done
  [[ "$result" == *'"Ana"'* ]] || { echo "nó $p não replicou Ana: $result" >&2; exit 1; }
done

echo 'Verificando DDL replicado...'
for p in "${ports[@]}"; do
  info=$(query "$p" 'INFO FOR TABLE person')
  [[ "$info" == *'"SCHEMAFULL"'* && "$info" == *'"name"'* ]] || {
    echo "nó $p não reconstruiu catálogo: $info" >&2; exit 1;
  }
done

echo 'Verificando identidade replicada sem expor a senha...'
for p in "${ports[@]}"; do
  root_info=$(query "$p" 'INFO FOR ROOT')
  [[ "$root_info" == *'"test@DATABASE"'* && "$root_info" != *'"senha"'* ]] || {
    echo "nó $p não replicou identidade com segurança: $root_info" >&2; exit 1;
  }
done

echo 'Verificando live query em seguidor após quorum...'
observer=18001
[[ "$observer" == "$leader" ]] && observer=18002
node tests/cluster/live-query.mjs "ws://127.0.0.1:$observer/rpc/ws" "ws://127.0.0.1:$leader/rpc/ws"

echo 'Derrubando líder e esperando failover...'
case "$leader" in 18001) old=n1;; 18002) old=n2;; *) old=n3;; esac
$compose stop "$old"
sleep 2
wait_for_leader
new_leader=$(leader_port)
[[ "$new_leader" != "$leader" ]] || { echo 'líder não mudou' >&2; exit 1; }
echo "Novo líder: $new_leader"
query "$new_leader" 'CREATE person:bob CONTENT {name:"Bob"}' >/dev/null

echo 'Recuperando líder antigo e verificando catch-up...'
$compose start "$old"
for _ in $(seq 1 30); do
  result=$(query "$leader" 'SELECT * FROM person' 2>/dev/null || true)
  [[ "$result" == *'"Bob"'* ]] && break
  sleep 1
done
[[ "$result" == *'"Bob"'* ]] || { echo "nó recuperado sem catch-up: $result" >&2; exit 1; }

echo 'Cluster validado: eleição, replicação, failover e recovery.'
