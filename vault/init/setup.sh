#!/bin/sh
set -e

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"

echo "=== Vault init: waiting for Vault ==="
until vault status >/dev/null 2>&1; do sleep 1; done

# ── KV v2 secrets engine ─────────────────────────────────────────────
echo "=== Enabling KV v2 ==="
vault secrets enable -path=secret -version=2 kv 2>/dev/null || true

vault kv put secret/kernfolio \
  spring.mail.host="smtp.example.com" \
  spring.mail.port="587" \
  spring.mail.username="kernfolio@example.com" \
  spring.mail.password="smtp-dev-password" \
  session.signing-key="dev-session-signing-key-change-in-prod" \
  admin.email="admin@kernfolio.dev"

# ── PostgreSQL superuser password ────────────────────────────────────
# Vault is the origin of this secret. It gets materialized onto the
# vault_agent_certs volume at /vault/certs/pg_root_password so that (a)
# the postgres image can consume it via POSTGRES_PASSWORD_FILE at initdb
# time and (b) vault-db-init can read it when wiring up the database
# engine. The file itself is the durable source of truth (persists across
# Vault dev-mode restarts, which wipe Vault's in-memory KV); we also
# mirror the value into Vault KV so it's visible via `vault kv get`.
PG_ROOT_PASSWORD_FILE=/vault/certs/pg_root_password
if [ -s "$PG_ROOT_PASSWORD_FILE" ]; then
    echo "=== Reusing existing pg_root_password ==="
    pg_root_password="$(cat "$PG_ROOT_PASSWORD_FILE")"
else
    echo "=== Generating pg_root_password ==="
    # Vault's sys/tools/random endpoint is the idiomatic way to source
    # random bytes when you're already talking to Vault; format=hex avoids
    # base64 post-processing. 16 bytes = 32 hex chars = 128 bits of entropy.
    pg_root_password="$(vault write -field=random_bytes sys/tools/random/16 format=hex)"
    printf '%s' "$pg_root_password" > "$PG_ROOT_PASSWORD_FILE"
    chmod 644 "$PG_ROOT_PASSWORD_FILE"
fi
vault kv put secret/kernfolio/pg-root password="$pg_root_password" >/dev/null

# ── PKI secrets engine ───────────────────────────────────────────────
echo "=== Enabling PKI engine ==="
vault secrets enable pki 2>/dev/null || true
vault secrets tune -max-lease-ttl=87600h pki

# Generate root CA (Ed25519)
vault write -field=certificate pki/root/generate/internal \
  common_name="Kernfolio Dev CA" \
  key_type=ed25519 \
  ttl=87600h > /vault/certs/ca.pem

vault write pki/config/urls \
  issuing_certificates="${VAULT_ADDR}/v1/pki/ca" \
  crl_distribution_points="${VAULT_ADDR}/v1/pki/crl"

# Enable intermediate PKI for issuing certs
vault secrets enable -path=pki_int pki 2>/dev/null || true
vault secrets tune -max-lease-ttl=43800h pki_int

# Generate intermediate CSR and sign it (Ed25519)
vault write -format=json pki_int/intermediate/generate/internal \
  common_name="Kernfolio Dev Intermediate CA" \
  key_type=ed25519 | \
  jq -r '.data.csr' > /tmp/pki_int.csr

vault write -format=json pki/root/sign-intermediate \
  csr=@/tmp/pki_int.csr \
  format=pem_bundle \
  ttl=43800h | \
  jq -r '.data.certificate' > /tmp/intermediate.pem

vault write pki_int/intermediate/set-signed certificate=@/tmp/intermediate.pem

# Ed25519 role — used by all non-Java services
vault write pki_int/roles/kernfolio-service \
  allowed_domains="app,optimizer,postgres,caddy,localhost" \
  allow_subdomains=false \
  allow_bare_domains=true \
  allow_localhost=true \
  key_type=ed25519 \
  max_ttl=720h


# ── Issue service certificates ───────────────────────────────────────
echo "=== Issuing service certificates ==="

# Helper: issue a cert and extract both cert and key from a single issuance
issue_cert() {
  local role="$1" cn="$2" alt="$3" cert_file="$4" key_file="$5"
  local args="common_name=${cn} ttl=720h"
  [ -n "$alt" ] && args="${args} alt_names=${alt}"
  vault write -format=json "pki_int/issue/${role}" private_key_format=pkcs8 $args > /tmp/cert.json
  jq -r '.data.certificate' /tmp/cert.json > "$cert_file"
  jq -r '.data.private_key' /tmp/cert.json > "$key_file"
}

# App (Spring Boot) — Ed25519 (Netty 4.2+ supports EdDSA auto-detection)
issue_cert "kernfolio-service" "app" "localhost" /vault/certs/app.pem /vault/certs/app-key.pem
issue_cert "kernfolio-service" "app" "" /vault/certs/app-client.pem /vault/certs/app-client-key.pem

# Optimizer (Python/uvicorn) — Ed25519
issue_cert "kernfolio-service" "optimizer" "localhost" /vault/certs/optimizer.pem /vault/certs/optimizer-key.pem
issue_cert "kernfolio-service" "optimizer" "" /vault/certs/optimizer-client.pem /vault/certs/optimizer-client-key.pem

# Caddy (Go) — Ed25519
issue_cert "kernfolio-service" "caddy" "" /vault/certs/caddy-client.pem /vault/certs/caddy-client-key.pem

# PostgreSQL (OpenSSL) — Ed25519
issue_cert "kernfolio-service" "postgres" "localhost" /vault/certs/postgres.pem /vault/certs/postgres-key.pem

# Fix permissions for postgres (runs as uid 70)
chmod 600 /vault/certs/postgres-key.pem
chmod 644 /vault/certs/postgres.pem /vault/certs/ca.pem

echo "=== Vault init complete ==="
