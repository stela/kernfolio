#!/bin/sh
set -e

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"

echo "=== Vault init: waiting for Vault ==="
until vault status >/dev/null 2>&1; do sleep 1; done

# ── KV v2 secrets engine ─────────────────────────────────────────────
echo "=== Enabling KV v2 ==="
vault secrets enable -path=secret -version=2 kv 2>/dev/null || true

vault kv put secret/kernfolio/smtp \
  host="smtp.example.com" \
  port="587" \
  username="kernfolio@example.com" \
  password="smtp-dev-password"

vault kv put secret/kernfolio/session \
  signing-key="dev-session-signing-key-change-in-prod"

vault kv put secret/kernfolio/admin \
  email="admin@kernfolio.dev"

# ── Database secrets engine ──────────────────────────────────────────
echo "=== Enabling database secrets engine ==="
vault secrets enable database 2>/dev/null || true

vault write database/config/kernfolio \
  plugin_name=postgresql-database-plugin \
  allowed_roles="app" \
  connection_url="postgresql://{{username}}:{{password}}@postgres:5432/kernfolio?sslmode=disable" \
  username="kernfolio" \
  password="kernfolio"

vault write database/roles/app \
  db_name=kernfolio \
  creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; \
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; \
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"{{name}}\";" \
  revocation_statements="DROP ROLE IF EXISTS \"{{name}}\";" \
  default_ttl="1h" \
  max_ttl="24h"

# ── PKI secrets engine ───────────────────────────────────────────────
echo "=== Enabling PKI engine ==="
vault secrets enable pki 2>/dev/null || true
vault secrets tune -max-lease-ttl=87600h pki

# Generate root CA
vault write -field=certificate pki/root/generate/internal \
  common_name="Kernfolio Dev CA" \
  ttl=87600h > /vault/certs/ca.pem

vault write pki/config/urls \
  issuing_certificates="${VAULT_ADDR}/v1/pki/ca" \
  crl_distribution_points="${VAULT_ADDR}/v1/pki/crl"

# Enable intermediate PKI for issuing certs
vault secrets enable -path=pki_int pki 2>/dev/null || true
vault secrets tune -max-lease-ttl=43800h pki_int

# Generate intermediate CSR and sign it
vault write -format=json pki_int/intermediate/generate/internal \
  common_name="Kernfolio Dev Intermediate CA" | \
  jq -r '.data.csr' > /tmp/pki_int.csr

vault write -format=json pki/root/sign-intermediate \
  csr=@/tmp/pki_int.csr \
  format=pem_bundle \
  ttl=43800h | \
  jq -r '.data.certificate' > /tmp/intermediate.pem

vault write pki_int/intermediate/set-signed certificate=@/tmp/intermediate.pem

# Create roles for issuing certs
vault write pki_int/roles/kernfolio-service \
  allowed_domains="app,optimizer,postgres,caddy,localhost" \
  allow_subdomains=false \
  allow_bare_domains=true \
  allow_localhost=true \
  max_ttl=720h

# ── Issue service certificates ───────────────────────────────────────
echo "=== Issuing service certificates ==="

# App (Spring Boot) server + client cert
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="app" \
  alt_names="localhost" \
  ttl=720h | \
  jq -r '.data.certificate' > /vault/certs/app.pem
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="app" \
  alt_names="localhost" \
  ttl=720h | \
  jq -r '.data.private_key' > /vault/certs/app-key.pem

# App client cert (for connecting to optimizer)
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="app" \
  ttl=720h > /tmp/app-client.json
jq -r '.data.certificate' /tmp/app-client.json > /vault/certs/app-client.pem
jq -r '.data.private_key' /tmp/app-client.json > /vault/certs/app-client-key.pem

# Optimizer server + client cert
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="optimizer" \
  alt_names="localhost" \
  ttl=720h > /tmp/optimizer.json
jq -r '.data.certificate' /tmp/optimizer.json > /vault/certs/optimizer.pem
jq -r '.data.private_key' /tmp/optimizer.json > /vault/certs/optimizer-key.pem

vault write -format=json pki_int/issue/kernfolio-service \
  common_name="optimizer" \
  ttl=720h > /tmp/optimizer-client.json
jq -r '.data.certificate' /tmp/optimizer-client.json > /vault/certs/optimizer-client.pem
jq -r '.data.private_key' /tmp/optimizer-client.json > /vault/certs/optimizer-client-key.pem

# Caddy client cert (for upstream mTLS to app)
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="caddy" \
  ttl=720h > /tmp/caddy-client.json
jq -r '.data.certificate' /tmp/caddy-client.json > /vault/certs/caddy-client.pem
jq -r '.data.private_key' /tmp/caddy-client.json > /vault/certs/caddy-client-key.pem

# PostgreSQL server cert
vault write -format=json pki_int/issue/kernfolio-service \
  common_name="postgres" \
  alt_names="localhost" \
  ttl=720h > /tmp/postgres.json
jq -r '.data.certificate' /tmp/postgres.json > /vault/certs/postgres.pem
jq -r '.data.private_key' /tmp/postgres.json > /vault/certs/postgres-key.pem

# Fix permissions for postgres (runs as uid 70)
chmod 600 /vault/certs/postgres-key.pem
chmod 644 /vault/certs/postgres.pem /vault/certs/ca.pem

echo "=== Vault init complete ==="
