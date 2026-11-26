#!/usr/bin/env bash
# Portões da fase E5 em máquinas separadas: três nós e um gerador de carga.
#
#   failover  líder morto sob carga: nenhuma escrita confirmada perdida e eleição em menos de 10 s
#   upgrade   atualização de um nó de cada vez sem erro visível ao cliente
#   qos       carga analítica sem piorar o p99 transacional em mais de 20%
#   dr        perda total do cluster: restaurar 10 GB do MinIO em menos de 10 minutos e restaurar a um instante
#
#   1. Na máquina com o código:  scripts/resilience-gates/run.sh bundle
#      Copie target/resilience-gates/axon-e5.tar.gz para todas as máquinas (Java 21) e extraia.
#   2. Em cada nó (ids diferentes, mesmo token):
#        AXON_GATE_TOKEN=<segredo> axon-e5/run.sh node --id n1 --advertise <ip interno> --data /mnt/ssd/axon-e5
#      Para o portão upgrade, extraia também o pacote da versão nova e acrescente --new-lib <dir>/axon-e5/lib.
#      Para o portão dr, defina antes AXON_S3_ENDPOINT, AXON_S3_BUCKET, AXON_S3_ACCESS e AXON_S3_SECRET (e,
#      se quiser cifrar, AXON_S3_ENCRYPT_KEY). O balde precisa existir.
#   3. No gerador de carga:
#        AXON_GATE_TOKEN=<segredo> axon-e5/run.sh failover --nodes ip1,ip2,ip3
#        ... upgrade --nodes ip1,ip2,ip3
#        ... qos --nodes ip1,ip2,ip3            (repita com --qos off para comparar)
#        ... dr --nodes ip1,ip2,ip3 --store s3 --gb 10
#
# Parâmetros de cada portão: veja a classe com.axonbase.server.ResilienceGates. As linhas de resultado começam com
# "E5"; o veredito está em "E5 <portão> RESULT PASS|FAIL" e o comando sai com 1 se reprovar.
#
# Portas em cada nó: 8100 (controle), 8000 (HTTP) e 9000 (Raft). Abra-as só na rede interna do benchmark: o agente
# sobe processos quando mandado e o servidor sobe sem autenticação. Cada base nova usa um diretório novo dentro de
# --data, e nada é apagado. Os relógios das máquinas precisam estar sincronizados (chrony).
#
# JAVA_OPTS (padrão -Xmx4g) vale para o agente, para o servidor, para a ferramenta de DR e para o gerador.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
export JAVA_OPTS=${JAVA_OPTS:--Xmx4g}

# Empacota as dependências e as classes (inclusive as de teste, onde estão o agente e o controlador).
bundle() {
    local root out stage name module classes
    root=$(cd "$HERE/../.." && pwd)
    out="$root/target/resilience-gates"
    mkdir -p "$out"
    # Diretório novo a cada vez, para um jar antigo nunca entrar no pacote.
    stage=$(mktemp -d "$out/stage.XXXXXX")
    mkdir -p "$stage/axon-e5/lib"
    (cd "$root" && mvn -B -q -pl axonbase-server -am test-compile \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile=target/classpath.txt >/dev/null)
    tr ':' '\n' < "$root/axonbase-server/target/classpath.txt" | grep '\.jar$' | grep -v '/com/axonbase/' \
        | while read -r dependency; do cp "$dependency" "$stage/axon-e5/lib/"; done
    for module in "$root"/axonbase-*/; do
        name=$(basename "$module")
        for classes in classes test-classes; do
            if [ -d "$module/target/$classes" ]; then
                jar --create --file "$stage/axon-e5/lib/$name-$classes.jar" -C "$module/target/$classes" .
            fi
        done
    done
    cp "$HERE/run.sh" "$stage/axon-e5/run.sh"
    tar -C "$stage" -czf "$out/axon-e5.tar.gz" axon-e5
    echo "pacote: $out/axon-e5.tar.gz (conteúdo também em $stage/axon-e5)"
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
    node)
        # sem --lib, o agente sobe o servidor com as mesmas bibliotecas dele
        run_java com.axonbase.server.ResilienceNode --lib "$HERE/lib" "$@"
        ;;
    failover | upgrade | qos | dr) run_java com.axonbase.server.ResilienceGates "$command" "$@" ;;
    *)
        awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
        exit 2
        ;;
esac
