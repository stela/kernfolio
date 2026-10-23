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

export PGPASSWORD="$DB_PASS"
pg_dump -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" --no-owner --no-acl
