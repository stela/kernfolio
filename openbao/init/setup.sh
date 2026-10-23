#!/bin/sh
set -e

export BAO_ADDR="${BAO_ADDR:-http://openbao:8200}"
export BAO_TOKEN="${BAO_TOKEN:-dev-root-token}"

echo "=== OpenBao init: waiting for OpenBao ==="
until bao status >/dev/null 2>&1; do sleep 1; done

# ── KV v2 secrets engine ─────────────────────────────────────────────
echo "=== Enabling KV v2 ==="
bao secrets enable -path=secret -version=2 kv 2>/dev/null || true

bao kv put secret/kernfolio \
  spring.mail.host="smtp.example.com" \
  spring.mail.port="587" \
  spring.mail.username="kernfolio@example.com" \
  spring.mail.password="smtp-dev-password" \
  session.signing-key="dev-session-signing-key-change-in-prod" \
  admin.email="admin@kernfolio.dev"

# ── PostgreSQL superuser password ────────────────────────────────────
# OpenBao is the origin of this secret. It gets materialized onto the
# openbao_certs volume at /openbao/certs/pg_root_password so that (a)
# the postgres image can consume it via POSTGRES_PASSWORD_FILE at initdb
# time and (b) openbao-db-init can read it when wiring up the database
# engine. The file itself is the durable source of truth (persists across
# OpenBao dev-mode restarts, which wipe OpenBao's in-memory KV); we also
# mirror the value into OpenBao KV so it's visible via `bao kv get`.
PG_ROOT_PASSWORD_FILE=/openbao/certs/pg_root_password
if [ -s "$PG_ROOT_PASSWORD_FILE" ]; then
    echo "=== Reusing existing pg_root_password ==="
    pg_root_password="$(cat "$PG_ROOT_PASSWORD_FILE")"
else
    echo "=== Generating pg_root_password ==="
    # OpenBao's sys/tools/random endpoint is the idiomatic way to source
    # random bytes when you're already talking to OpenBao; format=hex avoids
    # base64 post-processing. 16 bytes = 32 hex chars = 128 bits of entropy.
    pg_root_password="$(bao write -field=random_bytes sys/tools/random/16 format=hex)"
    printf '%s' "$pg_root_password" > "$PG_ROOT_PASSWORD_FILE"
    chmod 644 "$PG_ROOT_PASSWORD_FILE"
fi
bao kv put secret/kernfolio/pg-root password="$pg_root_password" >/dev/null

# ── PKI secrets engine ───────────────────────────────────────────────
# Idempotent: if openbao-init re-runs against a surviving (dev) OpenBao, we
# must NOT regenerate the root. Re-generating appends another issuer, but
# `default` stays pinned to the first one, so the next sign-intermediate
# signs with the OLD root while ca.pem gets overwritten with the NEW root
# — PKIX path-building breaks across the stack.
echo "=== Enabling PKI engine ==="
bao secrets enable pki 2>/dev/null || true
bao secrets tune -max-lease-ttl=87600h pki

if bao read -field=certificate pki/cert/ca >/openbao/certs/ca.pem 2>/dev/null \
   && [ -s /openbao/certs/ca.pem ]; then
    echo "=== Reusing existing root CA ==="
else
    echo "=== Generating root CA ==="
    bao write -field=certificate pki/root/generate/internal \
      common_name="Kernfolio Dev CA" \
      key_type=ed25519 \
      ttl=87600h > /openbao/certs/ca.pem

    bao write pki/config/urls \
      issuing_certificates="${BAO_ADDR}/v1/pki/ca" \
      crl_distribution_points="${BAO_ADDR}/v1/pki/crl"
fi

# Enable intermediate PKI for issuing certs
bao secrets enable -path=pki_int pki 2>/dev/null || true
bao secrets tune -max-lease-ttl=43800h pki_int

# Generate intermediate only if pki_int has no signed CA cert yet.
if ! bao read pki_int/cert/ca 2>/dev/null | grep -q "BEGIN CERTIFICATE"; then
    echo "=== Generating intermediate CA ==="
    bao write -format=json pki_int/intermediate/generate/internal \
      common_name="Kernfolio Dev Intermediate CA" \
      key_type=ed25519 | \
      jq -r '.data.csr' > /tmp/pki_int.csr

    bao write -format=json pki/root/sign-intermediate \
      csr=@/tmp/pki_int.csr \
      format=pem_bundle \
      ttl=43800h | \
      jq -r '.data.certificate' > /tmp/intermediate.pem

    bao write pki_int/intermediate/set-signed certificate=@/tmp/intermediate.pem
else
    echo "=== Reusing existing intermediate CA ==="
fi

# Ed25519 role — used by all non-Java services
bao write pki_int/roles/kernfolio-service \
  allowed_domains="app,web,optimizer,postgres,caddy,localhost" \
  allow_subdomains=false \
  allow_bare_domains=true \
  allow_localhost=true \
  key_type=ed25519 \
  max_ttl=720h


# ── Issue service certificates ───────────────────────────────────────
echo "=== Issuing service certificates ==="

# Helper: issue a cert and extract both cert and key from a single issuance.
# The cert file is leaf + issuing intermediate so servers present a full
# chain. ca.pem holds only the root — without the intermediate in the
# server's cert, clients get PKIX path-building failures at handshake.
issue_cert() {
  local role="$1" cn="$2" alt="$3" cert_file="$4" key_file="$5"
  local args="common_name=${cn} ttl=720h"
  [ -n "$alt" ] && args="${args} alt_names=${alt}"
  bao write -format=json "pki_int/issue/${role}" private_key_format=pkcs8 $args > /tmp/cert.json
  jq -r '.data.certificate, .data.issuing_ca' /tmp/cert.json > "$cert_file"
  jq -r '.data.private_key' /tmp/cert.json > "$key_file"
}

# App (Spring Boot) — Ed25519. app.pem is web's server cert for the Caddy -> web
# mTLS hop (application-tls.yml); Caddy dials the compose service name, hence
# the `web` SAN. app-client.pem is web's identity towards the optimizer
# (Netty 4.2+ supports EdDSA auto-detection).
issue_cert "kernfolio-service" "app" "web,localhost" /openbao/certs/app.pem /openbao/certs/app-key.pem
issue_cert "kernfolio-service" "app" "" /openbao/certs/app-client.pem /openbao/certs/app-client-key.pem

# Optimizer (Python/uvicorn) — Ed25519
issue_cert "kernfolio-service" "optimizer" "localhost" /openbao/certs/optimizer.pem /openbao/certs/optimizer-key.pem
issue_cert "kernfolio-service" "optimizer" "" /openbao/certs/optimizer-client.pem /openbao/certs/optimizer-client-key.pem

# Caddy (Go) — Ed25519
issue_cert "kernfolio-service" "caddy" "" /openbao/certs/caddy-client.pem /openbao/certs/caddy-client-key.pem

# PostgreSQL (OpenSSL) — Ed25519
issue_cert "kernfolio-service" "postgres" "localhost" /openbao/certs/postgres.pem /openbao/certs/postgres-key.pem

# Fix permissions for postgres (runs as uid 70)
chmod 600 /openbao/certs/postgres-key.pem
chmod 644 /openbao/certs/postgres.pem /openbao/certs/ca.pem

echo "=== OpenBao init complete ==="
