#!/bin/sh
set -e

. "$(dirname "$0")/common.sh"

# OpenBao's listener cert comes from openbao-tls-init (setup-tls.sh).
export BAO_ADDR="${BAO_ADDR:-https://openbao:8200}"
export BAO_CACERT="${BAO_CACERT:-$OPENBAO_CA}"
export BAO_TOKEN="${BAO_TOKEN:-dev-root-token}"

# Secrets layout and place(): see common.sh.
ALL_DIRS="$WEB_DIR $CADDY_DIR $OPTIMIZER_DIR $POSTGRES_DIR $OPENBAO_DIR"
require_dirs $ALL_DIRS

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
# OpenBao is the origin of this secret. It gets materialized as
# pg_root_password into two volumes: postgres's, which reads it via
# POSTGRES_PASSWORD_FILE at initdb time, and OpenBao's, for openbao-db-init
# wiring up the database engine. The files are the durable source of truth
# (they survive OpenBao dev-mode restarts, which wipe its in-memory KV); the
# value is also mirrored into OpenBao KV so it's visible via `bao kv get`.
if [ -s "$OPENBAO_DIR/pg_root_password" ]; then
    echo "=== Reusing existing pg_root_password ==="
    pg_root_password="$(cat "$OPENBAO_DIR/pg_root_password")"
elif [ -s "$POSTGRES_DIR/pg_root_password" ]; then
    echo "=== Reusing existing pg_root_password ==="
    pg_root_password="$(cat "$POSTGRES_DIR/pg_root_password")"
else
    echo "=== Generating pg_root_password ==="
    # OpenBao's sys/tools/random endpoint is the idiomatic way to source
    # random bytes when you're already talking to OpenBao; format=hex avoids
    # base64 post-processing. 16 bytes = 32 hex chars = 128 bits of entropy.
    pg_root_password="$(bao write -field=random_bytes sys/tools/random/16 format=hex)"
fi
# Re-placed on every run, so both copies stay identical and correctly owned.
printf '%s' "$pg_root_password" | place "$POSTGRES_DIR/pg_root_password" "$POSTGRES_UID" 0 0600
printf '%s' "$pg_root_password" | place "$OPENBAO_DIR/pg_root_password" "$OPENBAO_UID" 0 0600
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

if ca_pem="$(bao read -field=certificate pki/cert/ca 2>/dev/null)" && [ -n "$ca_pem" ]; then
    echo "=== Reusing existing root CA ==="
else
    echo "=== Generating root CA ==="
    ca_pem="$(bao write -field=certificate pki/root/generate/internal \
      common_name="Kernfolio Dev CA" \
      key_type=ed25519 \
      ttl=87600h)"

    bao write pki/config/urls \
      issuing_certificates="${BAO_ADDR}/v1/pki/ca" \
      crl_distribution_points="${BAO_ADDR}/v1/pki/crl"
fi

for d in $ALL_DIRS; do
  printf '%s\n' "$ca_pem" | place "$d/ca.pem" 0 0 0644
done

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
  allowed_domains="app,web,optimizer,postgres,caddy,kernfolio,localhost" \
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
# issue_cert <dir> <cn> <alt_names> <file stem> <key owner uid>: <stem>.pem is
# public (root, 0644); <stem>-key.pem belongs to <key owner uid>, 0600.
issue_cert() {
  local dir="$1" cn="$2" alt="$3" stem="$4" owner="$5"
  local args="common_name=${cn} ttl=720h"
  [ -n "$alt" ] && args="${args} alt_names=${alt}"
  bao write -format=json "pki_int/issue/kernfolio-service" private_key_format=pkcs8 $args > /tmp/cert.json
  jq -r '.data.certificate, .data.issuing_ca' /tmp/cert.json | place "$dir/$stem.pem" 0 0 0644
  jq -r '.data.private_key' /tmp/cert.json | place "$dir/$stem-key.pem" "$owner" 0 0600
  rm -f /tmp/cert.json
}

# App (Spring Boot) — Ed25519. app.pem is web's server cert for the Caddy -> web
# mTLS hop (application-tls.yml); Caddy dials the compose service name, hence
# the `web` SAN. app-client.pem is web's identity towards the optimizer
# (Netty 4.2+ supports EdDSA auto-detection).
issue_cert "$WEB_DIR" app "web,localhost" app "$WEB_UID"
issue_cert "$WEB_DIR" app "" app-client "$WEB_UID"

# Optimizer (Python/uvicorn) — Ed25519
# optimizer-client is only used by the optimizer's own healthcheck.
issue_cert "$OPTIMIZER_DIR" optimizer "localhost" optimizer "$OPTIMIZER_UID"
issue_cert "$OPTIMIZER_DIR" optimizer "" optimizer-client "$OPTIMIZER_UID"

# Caddy (Go) — Ed25519
issue_cert "$CADDY_DIR" caddy "" caddy-client "$CADDY_UID"

# PostgreSQL (OpenSSL) — Ed25519. Server cert only.
issue_cert "$POSTGRES_DIR" postgres "localhost" postgres "$POSTGRES_UID"

# Postgres superuser client identity. pg_hba.conf requires
# clientcert=verify-full for user `kernfolio`, i.e. the cert CN must equal the
# user name. Used by the OpenBao database engine and by openbao-db-init.
issue_cert "$OPENBAO_DIR" kernfolio "" postgres-admin-client "$OPENBAO_UID"

echo "=== OpenBao init complete ==="
