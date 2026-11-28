#!/usr/bin/env bash
# Compara resoluções H3 de armazenamento do índice GEO: para cada uma, copia o repositório, troca a constante na cópia
# e roda o portão de latência. Só para máquina de teste dedicada. O repositório de origem não é alterado.
#
# uso: scripts/geo-comparar-resolucoes.sh <diretório de saída, novo e fora do repositório> [-Daxon.gate.geo.points=...]
#   RESOLUCOES="9 10 12" (padrão)   MVN=mvn (padrão)
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "uso: $0 <diretório de saída> [-D...]" >&2
  exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd -P)
mkdir -p "$1"
out=$(cd "$1" && pwd -P)
shift
case "$out/" in
  "$root"/*) echo "o diretório de saída precisa ficar fora do repositório" >&2; exit 2 ;;
esac
constant=axonbase-core/src/main/java/com/axonbase/core/engine/H3Cells.java

for resolution in ${RESOLUCOES:-9 10 12}; do
  work="$out/r$resolution"
  if [ -e "$work" ]; then
    echo "$work já existe: use um diretório de saída novo" >&2
    exit 2
  fi
  mkdir -p "$work/fonte"
  (cd "$root" && tar --exclude=./.git --exclude=./.claude --exclude=target -cf - .) | tar -xf - -C "$work/fonte"
  sed "s/STORAGE_RESOLUTION = [0-9]*;/STORAGE_RESOLUTION = $resolution;/" "$root/$constant" > "$work/fonte/$constant"
  if ! grep -q "STORAGE_RESOLUTION = $resolution;" "$work/fonte/$constant"; then
    echo "constante STORAGE_RESOLUTION não encontrada em $constant" >&2
    exit 1
  fi
  status=0
  # o diretório da base vai por último: cada resolução precisa carregar a própria base
  (cd "$work/fonte" && "${MVN:-mvn}" -B -pl axonbase-core -am test -Dtest=GeoLatencyGateTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Daxon.gate.geo=true "$@" "-Daxon.gate.geo.dir=$work/base") \
    > "$work/maven.log" 2>&1 || status=$?
  echo "== resolução $resolution (mvn saiu com $status)" | tee -a "$out/resumo.txt"
  grep -E "^GEO |Tests run:|p99 d" "$work/maven.log" | tee -a "$out/resumo.txt" || true
done
