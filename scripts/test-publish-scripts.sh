#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

mkdir -p "$temp_dir/bin"
export PATH="$temp_dir/bin:$PATH"
export COMMAND_LOG="$temp_dir/commands.log"

cat > "$temp_dir/bin/git" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  "rev-parse --is-inside-work-tree") echo true ;;
  "status --porcelain")
    [[ ${GIT_DIRTY:-0} == 1 ]] && printf ' M pom.xml\n'
    ;;
  "describe --exact-match --tags HEAD") echo v0.2.0 ;;
  *) exit 1 ;;
esac
EOF

cat > "$temp_dir/bin/mvn" <<'EOF'
#!/usr/bin/env bash
printf 'mvn %s\n' "$*" >> "$COMMAND_LOG"
case "$*" in
  *help:evaluate*) echo 0.2.0 ;;
esac
EOF

cat > "$temp_dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$COMMAND_LOG"
EOF

chmod +x "$temp_dir/bin/git" "$temp_dir/bin/mvn" "$temp_dir/bin/docker"

bash "$root_dir/scripts/publish-maven.sh"
command_log=$(<"$COMMAND_LOG")
[[ "$command_log" == *"mvn clean deploy -pl axonbase-sdk-java,axonbase-jdbc,axonbase-spring-data -am -DskipTests"* ]]

: > "$COMMAND_LOG"
bash "$root_dir/scripts/publish-docker.sh"
command_log=$(<"$COMMAND_LOG")
[[ "$command_log" == *"mvn clean test -pl axonbase-server -am"* ]]
[[ "$command_log" == *"docker buildx build --platform linux/amd64,linux/arm64 --tag axonbase/axonbase:0.2.0 --tag axonbase/axonbase:latest --push ."* ]]

if GIT_DIRTY=1 bash "$root_dir/scripts/publish-maven.sh"; then
  printf 'expected publish-maven.sh to reject a dirty worktree\n' >&2
  exit 1
fi

printf 'publish scripts: ok\n'
