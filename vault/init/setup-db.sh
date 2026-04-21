#!/bin/sh
set -e

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"

# Postgres superuser password. Must match POSTGRES_PASSWORD on the postgres
# service in docker-compose.yml (both come from the DB_ROOT_PASSWORD env var
# with the same dev fallback).
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-kernfolio}"

echo "=== Vault DB init: waiting for Vault ==="
until vault status >/dev/null 2>&1; do sleep 1; done

# ── Database secrets engine ──────────────────────────────────────────
echo "=== Enabling database secrets engine ==="
vault secrets enable database 2>/dev/null || true

vault write database/config/kernfolio \
  plugin_name=postgresql-database-plugin \
  allowed_roles="app" \
  connection_url="postgresql://{{username}}:{{password}}@postgres:5432/kernfolio?sslmode=disable" \
  username="kernfolio" \
  password="${DB_ROOT_PASSWORD}"

vault write database/roles/app \
  db_name=kernfolio \
  creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; \
    GRANT ALL ON SCHEMA public TO \"{{name}}\"; \
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; \
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"{{name}}\";" \
  revocation_statements="DROP ROLE IF EXISTS \"{{name}}\";" \
  default_ttl="1h" \
  max_ttl="24h"

echo "=== Vault DB init complete ==="
