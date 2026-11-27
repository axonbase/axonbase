#!/usr/bin/env bash
# Verifica numa máquina Linux DESCARTÁVEL (Docker Engine, plugin compose, curl e python3) que o estado do Raft e os
# truststores ficam no volume e sobrevivem à recriação dos contêineres, em instalação nova e vinda de uma versão
# antiga. Compila a imagem do código local. Cria e apaga contêineres e volumes com o prefixo axv-.
#
# Uso: scripts/verificar-instalacao.sh [tag antiga]     (padrão 1.0.0-rc1, da imagem axonbase/axonbase)
set -uo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root_dir"
old_tag="${1:-1.0.0-rc1}"
export AXON_REQUIRE_AUTH=false
unset AXONBASE_IMAGE_TAG AXON_PUBLISH_ADDRESS

new_image=axonbase/axonbase:cluster-test
base=(docker compose -p axv-cluster -f docker-compose.cluster.yml)
new=("${base[@]}" -f tests/cluster/docker-compose.build.yml)
ports=(18001 18002 18003)
headers=(-H 'Axon-Ns: app' -H 'Axon-Db: main')
password="senha-$(openssl rand -hex 8)"
secret="$(openssl rand -hex 32)"
work=$(mktemp -d)
failures=0

ok() { echo "OK    $*"; }
fail() { echo "FALHA $*"; failures=$((failures + 1)); }
die() { echo "interrompido: $*" >&2; exit 1; }

cleanup() {
  docker rm -f axv-single >/dev/null 2>&1
  docker volume rm axv-single >/dev/null 2>&1
  "${base[@]}" down -v --remove-orphans >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

wait_http() {
  for _ in $(seq 1 90); do
    curl -fsS "$1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

leader_port() {
  local p s
  for p in "${ports[@]}"; do
    s=$(curl -fsS "http://127.0.0.1:$p/status" 2>/dev/null) || continue
    if printf '%s' "$s" | python3 -c 'import json,sys; s=json.load(sys.stdin); sys.exit(0 if s.get("node_id") and s.get("node_id")==s.get("leader") else 1)' 2>/dev/null; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

wait_leader() {
  for _ in $(seq 1 90); do
    leader_port && return 0
    sleep 1
  done
  return 1
}

query() { curl -fsS -X POST "http://127.0.0.1:$1/sql" "${headers[@]}" --data "$2"; }

has_raft_state() { docker cp "$1:/data/.raft" - 2>/dev/null | tar -t 2>/dev/null | grep -q 'raft-log'; }

echo "== compilando a imagem do código local"
docker build -q -t "$new_image" . >/dev/null || die "docker build"

echo "== nó único: truststore enviado sobrevive à recriação"
run_single() {
  docker run -d --name axv-single -p 127.0.0.1:18010:8000 -v axv-single:/data \
    -e AXON_ROOT_PASSWORD="$password" -e AXON_SECRET="$secret" "$new_image" >/dev/null \
    && wait_http http://127.0.0.1:18010/ready
}
token() {
  curl -fsS -X POST http://127.0.0.1:18010/signin -H 'Content-Type: application/json' \
    --data "{\"user\":\"root\",\"pass\":\"$password\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}
run_single || die "o nó único não subiu"
docker exec axv-single sh -c 'keytool -genkeypair -alias v -keyalg RSA -dname CN=verifica -storetype JKS \
  -keystore /tmp/v.jks -storepass senha123 -keypass senha123 >/dev/null 2>&1' || die "keytool"
docker cp axv-single:/tmp/v.jks "$work/v.jks" >/dev/null
curl -fsS -X POST http://127.0.0.1:18010/admin/v1/truststores -H "Authorization: Bearer $(token)" \
  -F name=verifica -F password=senha123 -F "file=@$work/v.jks" >/dev/null || die "envio do truststore"
docker exec axv-single test -f /data/.truststores/verifica.jks && ok "truststore dentro do volume" \
  || fail "truststore fora de /data/.truststores"
docker rm -f axv-single >/dev/null
run_single || die "o nó único não voltou"
curl -fsS http://127.0.0.1:18010/admin/v1/truststores -H "Authorization: Bearer $(token)" | grep -q '"verifica"' \
  && ok "truststore carregado depois de recriar" || fail "truststore perdido ao recriar"
docker rm -f axv-single >/dev/null

echo "== cluster novo: estado do Raft no volume"
"${new[@]}" up -d --build >/dev/null || die "subida do cluster novo"
leader=$(wait_leader) || die "sem líder no cluster novo"
query "$leader" 'CREATE person:ana CONTENT {name:"Ana"}' >/dev/null || fail "escrita no cluster novo"
sleep 2
for n in n1 n2 n3; do
  has_raft_state "axv-cluster-$n-1" && ok "$n grava o Raft em /data/.raft" || fail "$n sem estado em /data/.raft"
done
"${new[@]}" up -d --force-recreate >/dev/null || die "recriação"
leader=$(wait_leader) || die "sem líder depois de recriar"
query "$leader" 'SELECT * FROM person' | grep -q '"Ana"' && ok "dado presente depois de recriar" || fail "dado perdido"
"${new[@]}" logs 2>/dev/null | grep -q 'raft_state_missing' && fail "um nó subiu sem o estado do Raft" \
  || ok "nenhum nó subiu sem o estado do Raft"
"${base[@]}" down -v --remove-orphans >/dev/null

echo "== atualização a partir da $old_tag"
AXONBASE_IMAGE_TAG="$old_tag" "${base[@]}" up -d >/dev/null || die "subida da versão antiga"
leader=$(wait_leader) || die "sem líder na versão antiga"
query "$leader" 'CREATE person:bia CONTENT {name:"Bia"}' >/dev/null || fail "escrita na versão antiga"
sleep 2
AXONBASE_IMAGE_TAG="$old_tag" "${base[@]}" stop >/dev/null
for n in n1 n2 n3; do
  scripts/migrar-estado-docker.sh "axv-cluster-$n-1" || fail "migração de $n"
  has_raft_state "axv-cluster-$n-1" && ok "$n: estado copiado para o volume" || fail "$n: cópia ausente"
done
"${new[@]}" up -d >/dev/null || die "subida da versão nova"
leader=$(wait_leader) || die "sem líder depois de atualizar"
query "$leader" 'SELECT * FROM person' | grep -q '"Bia"' && ok "dado antigo presente" || fail "dado antigo perdido"
query "$leader" 'CREATE person:caio CONTENT {name:"Caio"}' >/dev/null && ok "escrita depois de atualizar" \
  || fail "escrita depois de atualizar"
"${new[@]}" logs 2>/dev/null | grep -q 'raft_state_missing' && fail "um nó subiu sem o estado do Raft" \
  || ok "todos os nós subiram com o estado antigo"

echo
[[ $failures -eq 0 ]] && echo "Tudo certo." || echo "$failures falha(s)."
exit "$failures"
