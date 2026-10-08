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
  "status --porcelain") ;;
  "tag --points-at HEAD") printf '%s\n' "$RELEASE_TAG" ;;
  "push origin "*) ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$temp_dir/bin/git"

for command in python3 twine npm cargo dotnet ruby gem docker; do
  cat > "$temp_dir/bin/$command" <<EOF
#!/usr/bin/env bash
printf '$command %s\\n' "\$*" >> "\$COMMAND_LOG"
EOF
  chmod +x "$temp_dir/bin/$command"
done

RELEASE_TAG=v0.2.2 VERSION=0.2.2 bash "$root_dir/scripts/publish-python.sh"
RELEASE_TAG=v0.2.2 VERSION=0.2.2 bash "$root_dir/scripts/publish-nodejs.sh"
RELEASE_TAG=v0.2.2 VERSION=0.2.2 bash "$root_dir/scripts/publish-rust.sh"
RELEASE_TAG=v0.2.2 VERSION=0.2.2 NUGET_API_KEY=test bash "$root_dir/scripts/publish-dotnet.sh"
RELEASE_TAG=v0.2.2 VERSION=0.2.2 bash "$root_dir/scripts/publish-ruby.sh"
RELEASE_TAG=axonbase-sdk-go/v0.2.2 VERSION=0.2.2 bash "$root_dir/scripts/publish-go.sh"

if bash "$root_dir/scripts/publish-php.sh"; then
  printf 'expected publish-php.sh to require a dedicated repository\n' >&2
  exit 1
fi

command_log=$(<"$COMMAND_LOG")
[[ "$command_log" == *"python3 -m pytest"* ]]
[[ "$command_log" == *"python3 -m twine upload axonbase-sdk-python/dist/*"* ]]
[[ "$command_log" == *"npm publish --access public"* ]]
[[ "$command_log" == *"cargo publish"* ]]
[[ "$command_log" == *"docker buildx build --target dotnet-package --output type=local,dest="* ]]
[[ "$command_log" == *"docker run --rm -e NUGET_API_KEY"* ]]
[[ "$command_log" == *"gem push axonbase-sdk-0.2.2.gem"* ]]
[[ "$command_log" == *"docker build --target dotnet-test --file Dockerfile.sdk-tests ."* ]]
[[ "$command_log" == *"docker build --target go-test --file Dockerfile.sdk-tests ."* ]]
[[ "$command_log" == *"docker build --target php-test --file Dockerfile.sdk-tests ."* ]]

printf 'SDK publish scripts: ok\n'
