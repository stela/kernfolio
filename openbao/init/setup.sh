#!/bin/sh
# Seeds OpenBao and issues every service's PKI certs. Runs as the one-shot
# openbao-init container on every `docker compose up`, in one of two modes:
#
# - Bootstrap, with BAO_INIT_TOKEN (root, or a token with the same powers):
#   mounts and configures the engines, seeds KV, writes the policies and token
#   roles, and mints the scoped tokens for web and cert-renewer (see the end).
#   Idempotent. In prod the operator passes the root token once, after
#   `bao operator init` (README, "OpenBao: first start, restarts, starting
#   over"); the dev overlay always passes dev-root-token.
# - Certs only, without BAO_INIT_TOKEN: issues certs with cert-renewer's own
#   token, which can do nothing else. Fails if OpenBao was never bootstrapped
#   or that token expired.
set -e

. "$(dirname "$0")/common.sh"

# OpenBao's mTLS certs come from openbao-tls-init (setup-tls.sh).
export BAO_ADDR="${BAO_ADDR:-https://openbao:8200}"
export BAO_CACERT="${BAO_CACERT:-$OPENBAO_CA}"
export BAO_CLIENT_CERT="${BAO_CLIENT_CERT:-$OPENBAO_DIR/openbao-client.pem}"
export BAO_CLIENT_KEY="${BAO_CLIENT_KEY:-$OPENBAO_DIR/openbao-client-key.pem}"
unset BAO_TOKEN

# Secrets layout and place(): see common.sh.
ALL_DIRS="$WEB_DIR $CADDY_DIR $OPTIMIZER_DIR $POSTGRES_DIR $OPENBAO_DIR $RENEWER_DIR"
require_dirs $ALL_DIRS

echo "=== OpenBao init: waiting for OpenBao (unsealed) ==="
until bao status >/dev/null 2>&1; do sleep 1; done

if [ -n "${BAO_INIT_TOKEN:-}" ]; then
  BOOTSTRAP=1
  export BAO_TOKEN="$BAO_INIT_TOKEN"
  # In dev, self-init creates the token just after unsealing (dev.hcl).
  tries=0
  until bao token lookup >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -lt 30 ] || { echo "ERROR: OpenBao rejects BAO_INIT_TOKEN" >&2; exit 1; }
    sleep 1
  done
  echo "=== Bootstrapping OpenBao with BAO_INIT_TOKEN ==="
elif token_works "$RENEWER_TOKEN"; then
  BOOTSTRAP=0
  BAO_TOKEN="$(cat "$RENEWER_TOKEN")"
  export BAO_TOKEN
  echo "=== No BAO_INIT_TOKEN: only issuing certificates, with cert-renewer's token ==="
  bao token renew >/dev/null
  # web's token too, in case web was down for a while.
  if token_works "$WEB_TOKEN"; then
    BAO_TOKEN="$(cat "$WEB_TOKEN")" bao token renew >/dev/null
  else
    echo "WARNING: web's OpenBao token no longer works; run once with BAO_INIT_TOKEN" >&2
  fi
else
  echo "ERROR: OpenBao isn't bootstrapped yet, or its tokens expired." >&2
  echo "Run once with BAO_INIT_TOKEN set: see README, \"OpenBao: first start, restarts, starting over\"." >&2
  exit 1
fi

if [ "$BOOTSTRAP" = 1 ]; then
  # ── KV v2 secrets engine ───────────────────────────────────────────
  echo "=== Enabling KV v2 ==="
  bao secrets enable -path=secret -version=2 kv 2>/dev/null || true

  # Placeholder values, written only once: in prod the operator replaces
  # them, and later runs must not put them back.
  if bao kv get secret/kernfolio >/dev/null 2>&1; then
    echo "=== Keeping existing secret/kernfolio ==="
  else
    echo "=== Seeding secret/kernfolio ==="
    bao kv put secret/kernfolio \
      spring.mail.host="smtp.example.com" \
      spring.mail.port="587" \
      spring.mail.username="kernfolio@example.com" \
      spring.mail.password="smtp-dev-password" \
      session.signing-key="$(bao write -field=random_bytes sys/tools/random/32 format=hex)" \
      admin.email="admin@kernfolio.dev"
  fi
fi

# ── PostgreSQL superuser password ────────────────────────────────────
# OpenBao is the origin of this secret. It gets materialized as
# pg_root_password into two volumes: postgres's, which reads it via
# POSTGRES_PASSWORD_FILE at initdb time, and OpenBao's, for openbao-db-init
# wiring up the database engine. The files are the source of truth (they
# also survive removing openbao_data, which pgdata doesn't notice); the value
# is also mirrored into OpenBao KV so it's visible via `bao kv get`.
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
if [ "$BOOTSTRAP" = 1 ]; then
  bao kv put secret/kernfolio/pg-root password="$pg_root_password" >/dev/null
fi

# ── PKI secrets engine ───────────────────────────────────────────────
# Idempotent: if openbao-init re-runs against a surviving OpenBao, we must
# NOT regenerate the root. Re-generating appends another issuer, but
# `default` stays pinned to the first one, so the next sign-intermediate
# signs with the OLD root while ca.pem gets overwritten with the NEW root
# — PKIX path-building breaks across the stack.
if [ "$BOOTSTRAP" = 1 ]; then
  echo "=== Enabling PKI engine ==="
  bao secrets enable pki 2>/dev/null || true
  bao secrets tune -max-lease-ttl=87600h pki
fi

# pki/cert/ca needs no token.
if ca_pem="$(bao read -field=certificate pki/cert/ca 2>/dev/null)" && [ -n "$ca_pem" ]; then
    echo "=== Reusing existing root CA ==="
elif [ "$BOOTSTRAP" = 1 ]; then
    echo "=== Generating root CA ==="
    ca_pem="$(bao write -field=certificate pki/root/generate/internal \
      common_name="Kernfolio Dev CA" \
      key_type=ed25519 \
      ttl=87600h)"

    bao write pki/config/urls \
      issuing_certificates="${BAO_ADDR}/v1/pki/ca" \
      crl_distribution_points="${BAO_ADDR}/v1/pki/crl"
else
    echo "ERROR: OpenBao has no PKI root CA; run once with BAO_INIT_TOKEN" >&2
    exit 1
fi

for d in $ALL_DIRS; do
  [ "$d" = "$RENEWER_DIR" ] && continue
  printf '%s\n' "$ca_pem" | place "$d/ca.pem" 0 0 0644
done

if [ "$BOOTSTRAP" = 1 ]; then
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
fi


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

# ── Scoped tokens (bootstrap only) ───────────────────────────────────
# Everything after bootstrap runs on two tokens that can do only their job
# (policies/*.hcl):
#   kernfolio-app (web): its KV secrets and database credentials.
#   kernfolio-cert-renewer (cert-renewer, and this script without
#   BAO_INIT_TOKEN): issue service certs.
# Both are periodic orphans: they never expire while they're renewed within
# 30 days (web's session manager renews its own; every run of this script
# renews both), and revoking the init token doesn't take them along. A token role lets a periodic
# orphan be made without sudo, and pins its policy.
if [ "$BOOTSTRAP" = 1 ]; then
  echo "=== Writing policies and token roles ==="
  for role in kernfolio-app kernfolio-cert-renewer; do
    bao policy write "$role" "$(dirname "$0")/policies/$role.hcl" >/dev/null
    bao write "auth/token/roles/$role" allowed_policies="$role" orphan=true renewable=true token_period=720h >/dev/null
  done

  # mint_token <file> <role> <uid>: a new token, unless the one in <file> still works.
  mint_token() {
    if token_works "$1"; then
      echo "=== Reusing the $2 token ==="
      BAO_TOKEN="$(cat "$1")" bao token renew >/dev/null
    else
      echo "=== Minting the $2 token ==="
      bao token create -role="$2" -field=token | place "$1" "$3" 0 0600
    fi
  }
  mint_token "$WEB_TOKEN" kernfolio-app "$WEB_UID"
  mint_token "$RENEWER_TOKEN" kernfolio-cert-renewer 0
fi

echo "=== OpenBao init complete ==="
