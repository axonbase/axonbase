#!/usr/bin/env bash
# Portões 1 a 3 da fase E2 em máquinas separadas: três nós e um gerador de carga (o portão 3 usa ainda a
# máquina do nó novo, que pode ser o próprio gerador).
#
#   1. Na máquina com o código:   scripts/raft-gates/run.sh bundle
#      Copie target/raft-gates/axon-gates.tar.gz para todas as máquinas (Java 21) e extraia.
#   2. Em cada nó (e no nó novo): AXON_GATE_TOKEN=<segredo> axon-gates/run.sh node --id n1 \
#                                   --advertise <ip interno> --data /mnt/ssd/axon-gates
#      (o --id precisa ser diferente em cada máquina)
#   3. No gerador de carga:       AXON_GATE_TOKEN=<segredo> axon-gates/run.sh gate1 --nodes ip1,ip2,ip3
#                                 ... gate2 --nodes ip1,ip2,ip3
#                                 ... gate3 --nodes ip1,ip2,ip3 --new ip4
#
# Parâmetros de cada portão: veja a classe com.axonbase.server.RaftRemoteGates. As linhas de resultado começam
# com GATE1, GATE2 ou GATE3; o veredito está em "GATEn RESULT ... PASS|FAIL" e o comando sai com 1 se reprovar.
#
# Portas em cada nó: 8100 (controle), 8000 (HTTP) e 9000 (Raft). Abra-as só na rede interna do benchmark: o
# agente recria a base quando mandado e o servidor sobe sem autenticação. Cada subida usa um diretório novo
# dentro de --data, e nada é apagado.
#
# JAVA_OPTS (padrão -Xmx4g) vale para o agente e para o gerador.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_OPTS=${JAVA_OPTS:--Xmx4g}

# Empacota as dependências e as classes (inclusive as de teste, onde estão o agente e o controlador).
bundle() {
    local root out stage name module classes
    root=$(cd "$HERE/../.." && pwd)
    out="$root/target/raft-gates"
    mkdir -p "$out"
    # Diretório novo a cada vez, para um jar antigo nunca entrar no pacote.
    stage=$(mktemp -d "$out/stage.XXXXXX")
    mkdir -p "$stage/axon-gates/lib"
    (cd "$root" && mvn -B -q -pl axonbase-server -am test-compile \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile=target/classpath.txt >/dev/null)
    tr ':' '\n' < "$root/axonbase-server/target/classpath.txt" | grep '\.jar$' | grep -v '/com/axonbase/' \
        | while read -r dependency; do cp "$dependency" "$stage/axon-gates/lib/"; done
    for module in "$root"/axonbase-*/; do
        name=$(basename "$module")
        for classes in classes test-classes; do
            if [ -d "$module/target/$classes" ]; then
                jar --create --file "$stage/axon-gates/lib/$name-$classes.jar" -C "$module/target/$classes" .
            fi
        done
    done
    cp "$HERE/run.sh" "$stage/axon-gates/run.sh"
    tar -C "$stage" -czf "$out/axon-gates.tar.gz" axon-gates
    echo "pacote: $out/axon-gates.tar.gz (conteúdo também em $stage/axon-gates)"
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

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
    bundle) bundle ;;
    node) run_java com.axonbase.server.RaftGateNode "$@" ;;
    gate1 | gate2 | gate3) run_java com.axonbase.server.RaftRemoteGates "$command" "$@" ;;
    *)
        awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
        exit 2
        ;;
esac
