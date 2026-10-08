#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${1:-"$project_dir/deploy/.env.dev"}
compose_file=${2:-"$project_dir/deploy/docker-compose.yml"}
[[ "$env_file" = /* ]] || env_file="$project_dir/$env_file"
[[ "$compose_file" = /* ]] || compose_file="$project_dir/$compose_file"
[[ -f "$env_file" ]] || { printf 'Missing env file: %s\n' "$env_file" >&2; exit 1; }
[[ -f "$compose_file" ]] || { printf 'Missing Compose file: %s\n' "$compose_file" >&2; exit 1; }

# The selected dotenv file is the sole source of stand configuration.
while IFS= read -r variable; do
  if printenv "$variable" > /dev/null; then
    printf 'Unset process variable %s; configure it in the selected env file.\n' "$variable" >&2
    exit 1
  fi
done < <(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$env_file")

dev_host=$(sed -n 's/^DEV_HOST=\([A-Za-z0-9.-]*\)\r\{0,1\}$/\1/p' "$env_file")
[[ "$dev_host" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || {
  printf 'DEV_HOST must be a nonempty host name or IPv4 address in the env file.\n' >&2
  exit 1
}
global_host=$(sed -n 's/^GLOBAL_NGINX_HOST=\([A-Za-z0-9.-]*\)\r\{0,1\}$/\1/p' "$env_file")
[[ "$global_host" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || {
  printf 'GLOBAL_NGINX_HOST must be set independently in the env file.\n' >&2
  exit 1
}
unset DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH
docker_host="tcp://$dev_host:2375"
docker --host "$docker_host" compose --env-file "$env_file" -f "$compose_file" config --quiet
docker --host "$docker_host" info --format '{{.ServerVersion}}'
docker --host "$docker_host" compose --env-file "$env_file" -f "$compose_file" build --pull
docker --host "$docker_host" compose --env-file "$env_file" -f "$compose_file" up --detach --wait --wait-timeout 300
printf 'Compose services are ready. Verify the public browser route separately.\n'
