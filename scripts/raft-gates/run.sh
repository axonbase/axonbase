#!/usr/bin/env bash
# Portões 1 a 3 da fase E2 (grupo de escritas, leituras em seguidores e entrada de um nó novo).
#
# Uso, na raiz do repositório:
#   scripts/raft-gates/run.sh gate1                      # Linux (contêiner), always contra batch
#   scripts/raft-gates/run.sh gate2                      # Linux (contêineres), 1 nó contra 3 nós
#   scripts/raft-gates/run.sh gate3 [GB] [votantes]      # no host
#
# Os arquivos de trabalho ficam em target/raft-gates (um `mvn clean` os remove).
# Variáveis: WRITERS (8,64,256), ROUNDS (3), SECONDS_PER_RUN (20), NODE_CPUS (2), LOAD_CPUS (5), CONNECTIONS (96),
# BENCH_DIR (diretório de dados do portão 3).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
IMAGE=${IMAGE:-maven:3.9-eclipse-temurin-21}
WORK="$ROOT/target/raft-gates"
WRITERS=${WRITERS:-8,64,256}
ROUNDS=${ROUNDS:-3}
SECONDS_PER_RUN=${SECONDS_PER_RUN:-20}
NODE_CPUS=${NODE_CPUS:-2}
LOAD_CPUS=${LOAD_CPUS:-5}
CONNECTIONS=${CONNECTIONS:-96}

# Compila e junta as dependências e as classes de cada módulo num lugar que o contêiner monta.
prepare() {
    mkdir -p "$WORK/lib"
    (cd "$ROOT" && mvn -B -q -pl axonbase-server -am test-compile \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile=target/classpath.txt >/dev/null)
    tr ':' '\n' < "$ROOT/axonbase-server/target/classpath.txt" | grep '\.jar$' | grep -v '/com/axonbase/' \
        | while read -r jar; do cp "$jar" "$WORK/lib/"; done
    for module in "$ROOT"/axonbase-*/; do
        name=$(basename "$module")
        for classes in classes test-classes; do
            if [ -d "$module/target/$classes" ]; then
                mkdir -p "$WORK/cp/$name-$classes"
                cp -R "$module/target/$classes/." "$WORK/cp/$name-$classes/"
            fi
        done
    done
}

classpath() {
    local base=$1
    printf '%s' "$(cd "$WORK" && ls -d cp/*/ | sed "s|^|$base/|" | tr '\n' ':')$base/lib/*"
}

host_load() {
    echo "HOST $(uname -sm) $(uptime)"
}

gate1() {
    prepare
    docker volume create axon-e2-gate1 >/dev/null
    trap 'docker volume rm -f axon-e2-gate1 >/dev/null' EXIT
    host_load
    for round in $(seq "$ROUNDS"); do
        for mode in always batch; do
            echo "# rodada $round modo $mode"
            docker run --rm --pull never -v "$WORK":/bench:ro -v axon-e2-gate1:/data \
                -e AXON_STORAGE_SYNC="$mode" "$IMAGE" java -cp "$(classpath /bench)" \
                com.axonbase.core.cluster.RaftWriteGateBenchmark /data "$WRITERS" "$SECONDS_PER_RUN" 2>&1 \
                | grep -E '^GATE1|Exception' || true
        done
    done
    host_load
}

NETWORK=axon-e2-gate2
SUBNET=172.31.77

stop_gate2() {
    docker rm -f axon-g2-solo axon-g2-n1 axon-g2-n2 axon-g2-n3 >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}

server() {
    local name=$1 ip=$2
    shift 2
    docker run -d --pull never --name "$name" --network "$NETWORK" --ip "$ip" --cpus "$NODE_CPUS" --memory 1500m \
        -v "$WORK":/bench:ro "$@" "$IMAGE" java -cp "$(classpath /bench)" com.axonbase.server.Main start \
        --path rocksdb:/tmp/db --bind 0.0.0.0 --port 8000 --no-auth >/dev/null
}

generator() {
    docker run --rm --pull never --network "$NETWORK" --cpus "$LOAD_CPUS" -v "$WORK":/bench:ro "$IMAGE" \
        java -cp "$(classpath /bench)" com.axonbase.server.RaftReadGateBenchmark "$@" | grep -E '^GATE2|^load'
}

gate2() {
    prepare
    stop_gate2
    trap stop_gate2 EXIT
    docker network create --subnet "$SUBNET.0/24" "$NETWORK" >/dev/null
    host_load
    local rows=10000
    server axon-g2-solo "$SUBNET.10"
    local solo="http://$SUBNET.10:8000"
    local cluster="" i other peers
    for i in 1 2 3; do
        peers=""
        for other in 1 2 3; do
            [ "$other" = "$i" ] || peers="$peers${peers:+,}$SUBNET.1$other:9000"
        done
        server "axon-g2-n$i" "$SUBNET.1$i" -e AXON_CLUSTER_ID=g2 -e AXON_NODE_ID="n$i" \
            -e AXON_RAFT_BIND="$SUBNET.1$i:9000" -e AXON_RAFT_PEERS="$peers"
        cluster="$cluster${cluster:+,}http://$SUBNET.1$i:8000"
    done
    sleep 15
    generator load "$solo" "$rows"
    generator load "$cluster" "$rows"
    for round in $(seq "$ROUNDS"); do
        echo "# rodada $round"
        generator read "$solo" "$rows" "$CONNECTIONS" "$SECONDS_PER_RUN"
        generator read "$cluster" "$rows" "$CONNECTIONS" "$SECONDS_PER_RUN"
        generator read "$cluster" "$rows" "$CONNECTIONS" "$SECONDS_PER_RUN" max-lag=1000
        generator read "$cluster" "$rows" "$CONNECTIONS" "$SECONDS_PER_RUN" linearizable
    done
    host_load
}

gate3() {
    prepare
    local gigabytes=${1:-10} voters=${2:-1}
    local dir=${BENCH_DIR:-$WORK/data}
    mkdir -p "$dir"
    local need free
    # Cada votante, o backup e o learner guardam uma cópia da base; mais 5 GB de folga.
    need=$(awk -v g="$gigabytes" -v v="$voters" 'BEGIN { printf "%d", g * (v + 2) * 1.15 + 5 }')
    free=$(df -Pk "$dir" | awk 'NR == 2 { printf "%d", $4 / 1048576 }')
    if [ "$free" -lt "$need" ]; then
        echo "disco insuficiente em $dir: $free GB livres, o teste precisa de cerca de $need GB" >&2
        exit 1
    fi
    host_load
    java -cp "$(classpath "$WORK")" com.axonbase.core.cluster.RaftJoinGateBenchmark "$dir" "$gigabytes" \
        "$voters" 2>&1 | grep -E '^GATE3|Exception'
    host_load
}

"$@"
