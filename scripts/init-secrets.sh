#!/usr/bin/env bash
# Create any secret files required by docker-compose if they don't already
# exist. Generates fresh random values on first run; leaves existing files
# alone so the same password keeps working across restarts. Safe to run
# repeatedly.
#
# Layout: each secret is a single file in ./secrets/ at the repo root, mounted
# by docker-compose as a Docker Compose secret (see docker-compose.yml).
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
secrets_dir="${repo_root}/secrets"
mkdir -p "${secrets_dir}"
chmod 700 "${secrets_dir}"

generate_if_missing() {
  local path="$1"
  local length="${2:-32}"
  if [ ! -s "${path}" ]; then
    # No trailing newline; the postgres entrypoint strips trailing whitespace
    # but other consumers (setup-db.sh) don't, so avoid it. openssl avoids
    # the SIGPIPE-from-head pipeline which 'set -o pipefail' would trip on.
    # Keep exactly ${length} bytes with no trailing newline. cut would append
    # one; head -c doesn't.
    openssl rand -base64 "$(( length * 2 ))" \
      | LC_ALL=C tr -dc 'A-Za-z0-9' \
      | head -c "${length}" \
      > "${path}" || true
    printf "" >> "${path}"
    chmod 600 "${path}"
    echo "generated ${path}"
  else
    echo "kept    ${path}"
  fi
}

generate_if_missing "${secrets_dir}/pg_root_password"
