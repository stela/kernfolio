#!/bin/sh
# Fetch one-time OpenBao DB credential and run pg_dump.
# The credential auto-expires after the configured TTL.
set -e

BAO_ADDR="${BAO_ADDR:-http://openbao:8200}"
BAO_TOKEN="${BAO_TOKEN:?BAO_TOKEN is required}"
DB_HOST="${DB_HOST:-postgres}"
DB_NAME="${DB_NAME:-kernfolio}"

# Request dynamic credentials from OpenBao (the API still uses the X-Vault-Token header)
CRED=$(curl -s -H "X-Vault-Token: ${BAO_TOKEN}" \
  "${BAO_ADDR}/v1/database/creds/app")

DB_USER=$(echo "$CRED" | jq -r '.data.username')
DB_PASS=$(echo "$CRED" | jq -r '.data.password')

if [ -z "$DB_USER" ] || [ "$DB_USER" = "null" ]; then
  echo "ERROR: Failed to get OpenBao DB credentials" >&2
  exit 1
fi

# Postgres only accepts mTLS (postgres/pg_hba_mtls.conf). Dynamic app roles
# need any client cert from the OpenBao CA; use the app's.
CERT_DIR="${OPENBAO_CERT_DIR:-/openbao/certs}"
export PGSSLMODE=verify-full
export PGSSLROOTCERT="$CERT_DIR/ca.pem"
export PGSSLCERT="$CERT_DIR/app-client.pem"
# The key belongs to web's uid (0600), so run this as root in a container that
# mounts openbao_certs. libpq wants the key owned by the current user, hence
# a private copy.
PGSSLKEY="$(mktemp)"
trap 'rm -f "$PGSSLKEY"' EXIT
install -m 600 "$CERT_DIR/app-client-key.pem" "$PGSSLKEY"
export PGSSLKEY
# No SCRAM channel binding with Ed25519 server certs (see openbao/init/setup-db.sh).
export PGCHANNELBINDING=disable
export PGPASSWORD="$DB_PASS"
pg_dump -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" --no-owner --no-acl
