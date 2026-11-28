#!/usr/bin/env bash
# Copia o estado do Raft e os truststores de um contêiner da 1.0.0-rc1 ou anterior (comando padrão, --path /data)
# para dentro do volume em /data, antes de recriar o contêiner. Veja docs/cluster-raft.md.
#
# Uso: scripts/migrar-estado-docker.sh <contêiner>
# O contêiner é parado e continua parado. Nada é apagado.
set -euo pipefail

[[ $# -eq 1 ]] || { echo "uso: $0 <contêiner>" >&2; exit 2; }
container="$1"
docker inspect "$container" >/dev/null

volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Destination}}{{end}}{{end}}' "$container")
[[ "$volume" == "/data" ]] || { echo "$container não tem volume em /data: nada seria preservado ao recriar" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

docker stop "$container" >/dev/null
echo "contêiner $container parado"

for kind in raft truststores; do
  if ! docker cp "$container:/data.$kind" "$work/$kind" 2>"$work/erro"; then
    if grep -qiE 'no such|could not find' "$work/erro"; then
      echo "sem /data.$kind: nada a copiar"
      continue
    fi
    cat "$work/erro" >&2
    exit 1
  fi
  if docker cp "$container:/data/.$kind" "$work/ja-existe-$kind" >/dev/null 2>&1; then
    echo "/data/.$kind já existe no volume: não sobrescrevo. Confira qual é o atual antes de continuar." >&2
    exit 1
  fi
  docker cp "$work/$kind" "$container:/data/.$kind"
  echo "/data.$kind copiado para /data/.$kind"
done

echo "Pronto. Agora troque a imagem e recrie o contêiner; não inicie o antigo depois disso."
