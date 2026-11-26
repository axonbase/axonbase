#!/usr/bin/env bash
# Portão de carga da fase E3, em máquinas separadas: três shards de três nós precisam ingerir pelo menos
# 2,4 vezes o que um shard só ingere. Opt-in: nada disto roda na suíte.
#
# Máquinas: 9 nós de shard (para a linha de base bastam os 3 do primeiro), uma ou mais para o gateway e uma
# para o gerador de carga. Todas com Java 21. O gateway não guarda estado: se ele for o gargalo, suba mais de
# um e passe todos em --gateways.
#
#   1. Na máquina com o código:   scripts/shard-gates/run.sh bundle
#      Copie target/shard-gates/axon-shard-gates.tar.gz para todas as máquinas e extraia.
#
#   2. Em cada nó de shard (SHARD=s0, s1 ou s2; NODE=n1, n2 ou n3; PEERS são os outros dois, porta 9000):
#        SHARD=s0 NODE=n1 IP=<ip interno> PEERS=<ip2>:9000,<ip3>:9000 DATA=/mnt/ssd/axon \
#        AXON_SECRET=<segredo longo, o mesmo em todos> AXON_ROOT_PASSWORD=<senha> \
#        axon-shard-gates/run.sh node
#
#   3. Linha de base, um shard. Na máquina do gateway:
#        AXON_GATEWAY_SHARDS='s0=http://a1:8000,http://a2:8000,http://a3:8000' \
#        AXON_GATEWAY_SHARD_USER=root AXON_GATEWAY_SHARD_PASS=<senha> axon-shard-gates/run.sh gateway
#      No gerador:
#        axon-shard-gates/run.sh measure --gateways http://<gateway>:8080 --label um-shard --out um.json \
#          --user root --pass <senha>
#
#   4. Três shards. Pare o gateway, apague os dados dos nós (diretórios novos em DATA), suba os nove nós e o
#      gateway com os três shards em AXON_GATEWAY_SHARDS ('s0=...;s1=...;s2=...'). No gerador:
#        axon-shard-gates/run.sh measure --gateways http://<gateway>:8080 --label tres-shards --out tres.json \
#          --user root --pass <senha>
#
#   5. Veredito (sai com 1 se reprovar):
#        axon-shard-gates/run.sh compare --baseline um.json --sharded tres.json
#
# Opções de measure: --threads 64 --seconds 60 --warmup 15 --batch 1 --payload 200. Use as mesmas nas duas
# medidas. Como o gerador pode ser o gargalo, aumente --threads até a vazão parar de subir nas duas.
# As linhas de resultado começam com GATE_E3.
#
# Portas: 8000 (HTTP dos nós), 9000 (Raft) e 8080 (gateway). Abra-as só na rede interna do benchmark.
# JAVA_OPTS (padrão -Xmx4g) vale para todos os processos.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_OPTS=${JAVA_OPTS:--Xmx4g}

# Empacota as dependências e as classes (inclusive as de teste, onde está o gerador).
bundle() {
    local root out stage name module classes
    root=$(cd "$HERE/../.." && pwd)
    out="$root/target/shard-gates"
    mkdir -p "$out"
    # Diretório novo a cada vez, para um jar antigo nunca entrar no pacote.
    stage=$(mktemp -d "$out/stage.XXXXXX")
    mkdir -p "$stage/axon-shard-gates/lib"
    (cd "$root" && mvn -B -q -pl axonbase-server -am test-compile \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile=target/classpath.txt >/dev/null)
    tr ':' '\n' < "$root/axonbase-server/target/classpath.txt" | grep '\.jar$' | grep -v '/com/axonbase/' \
        | while read -r dependency; do cp "$dependency" "$stage/axon-shard-gates/lib/"; done
    for module in "$root"/axonbase-*/; do
        name=$(basename "$module")
        for classes in classes test-classes; do
            if [ -d "$module/target/$classes" ]; then
                jar --create --file "$stage/axon-shard-gates/lib/$name-$classes.jar" -C "$module/target/$classes" .
            fi
        done
    done
    cp "$HERE/run.sh" "$stage/axon-shard-gates/run.sh"
    tar -C "$stage" -czf "$out/axon-shard-gates.tar.gz" axon-shard-gates
    echo "pacote: $out/axon-shard-gates.tar.gz (conteúdo também em $stage/axon-shard-gates)"
}

run_java() {
    local class=$1
    shift
    if [ ! -d "$HERE/lib" ]; then
        echo "rode este comando de dentro do pacote extraído (veja o passo 1 no começo do arquivo)" >&2
        exit 2
    fi
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -cp "$HERE/lib/*" "$class" "$@"
}

node() {
    : "${SHARD:?defina SHARD}" "${NODE:?defina NODE}" "${IP:?defina IP}" "${PEERS:?defina PEERS}"
    : "${DATA:?defina DATA}" "${AXON_SECRET:?defina AXON_SECRET}" "${AXON_ROOT_PASSWORD:?defina AXON_ROOT_PASSWORD}"
    # um diretório novo a cada subida; nada é apagado
    mkdir -p "$DATA"
    local data
    data=$(mktemp -d "$DATA/$SHARD-$NODE.XXXXXX")
    export AXON_PATH="$data/db" AXON_PORT="${AXON_PORT:-8000}" AXON_BIND="$IP" AXON_REQUIRE_AUTH=true
    export AXON_CLUSTER_ID="$SHARD" AXON_NODE_ID="$NODE" AXON_RAFT_BIND="$IP:9000" AXON_RAFT_PEERS="$PEERS"
    export AXON_ROOT_USER="${AXON_ROOT_USER:-root}" AXON_RATE_LIMIT="${AXON_RATE_LIMIT:-0}"
    run_java com.axonbase.server.Main start
}

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
    bundle) bundle ;;
    node) node ;;
    gateway)
        export AXON_GATEWAY_BIND="${AXON_GATEWAY_BIND:-0.0.0.0}"
        run_java com.axonbase.server.Main gateway
        ;;
    measure | compare) run_java com.axonbase.server.ShardIngestGate "$command" "$@" ;;
    *)
        awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
        exit 2
        ;;
esac
