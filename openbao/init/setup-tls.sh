#!/bin/sh
# Mutual TLS for OpenBao's own listener (openbao/config/listener.hcl): its
# server cert, and the client certs every OpenBao client must present.
#
# OpenBao's PKI can't issue these: the one-shot openbao-tls-init container
# runs this *before* OpenBao starts. So they come from a small CA made here
# with openssl. Its cert, openbao-ca.pem, is the one trust anchor on both
# sides: clients verify the server with it (BAO_CACERT, VaultBootstrap) and
# the listener only accepts client certs that chain to it
# (tls_client_ca_file). It is not the PKI's ca.pem, which signs the
# service-to-service certs; certs from that PKI are refused.
#
# ECDSA P-256 rather than Ed25519 like the PKI: the OpenBao UI is opened in
# browsers, and they don't accept Ed25519 certs.
#
# Files (certs root 0644, keys owned by their user at 0600):
#   openbao_tls_ca: openbao-ca-key.pem (root; only here and in cert-renewer)
#   certs_openbao:  openbao.pem + openbao-key.pem (server),
#                   openbao-client.pem + openbao-client-key.pem (CN openbao-cli:
#                   the server container's own `bao` CLI, openbao-db-init,
#                   openbao-init and cert-renewer, which read it as root),
#                   openbao-ca.pem
#   certs_web:      openbao-client.pem + openbao-client-key.pem (CN app, for
#                   Spring Cloud Vault), openbao-ca.pem
#
# The CA lasts 10 years and is kept, with its key, so the trust anchor never
# changes: OpenBao reads tls_client_ca_file only at start. Each leaf lasts
# LEAF_SECONDS (90 days) and is renewed on its own once a third of that is
# left (cert_due in common.sh), or when it no longer chains to the CA, matches
# its key or, for the server, has the SANs below. OpenBao reloads its server
# cert (tls_auto_reload), and its clients read theirs per connection.
#
# --leaves-only (cert-renewer, every round): never makes a new CA. Without it
# (openbao-tls-init, before OpenBao starts), a missing or invalid CA, or one
# with less than CA_REDO_DAYS left, is made again along with every leaf, and
# OpenBao and all its clients must then be restarted, which `docker compose
# down && docker compose up -d` does.
set -e

. "$(dirname "$0")/common.sh"

LEAVES_ONLY=0
[ "${1:-}" = --leaves-only ] && LEAVES_ONLY=1

require_dirs "$OPENBAO_DIR" "$WEB_DIR" "$TLS_CA_DIR"

CA_KEY=$TLS_CA_DIR/openbao-ca-key.pem
CERT=$OPENBAO_DIR/openbao.pem
KEY=$OPENBAO_DIR/openbao-key.pem
CLI_CERT=$OPENBAO_DIR/openbao-client.pem
CLI_KEY=$OPENBAO_DIR/openbao-client-key.pem
WEB_CERT=$WEB_DIR/openbao-client.pem
WEB_KEY=$WEB_DIR/openbao-client-key.pem
WEB_CA=$WEB_DIR/openbao-ca.pem
# openbao: the compose service name. localhost / 127.0.0.1: the healthcheck
# and `docker compose exec openbao bao ...`, and the published port in dev.
SAN="DNS:openbao,DNS:localhost,IP:127.0.0.1"
SAN_AS_PRINTED="DNS:openbao, DNS:localhost, IP Address:127.0.0.1"
CA_DAYS=3650
CA_REDO_DAYS=90
LEAF_SECONDS="${OPENBAO_TLS_LEAF_SECONDS:-$((90 * 86400))}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# One writer at a time: openbao-tls-init re-runs on every `up`, while
# cert-renewer may be in a round.
exec 9> "$TLS_CA_DIR/lock"
flock 9

# Dev only (docker-compose.dev.yml): the key for dev.hcl's static seal. Never
# replaced: OpenBao's existing data can only be unsealed with the key it was
# initialized with.
DEV_UNSEAL_KEY=$OPENBAO_DIR/dev-unseal.key
if [ "$LEAVES_ONLY" = 0 ] && [ "${OPENBAO_DEV_UNSEAL_KEY:-}" = 1 ] && [ ! -e "$DEV_UNSEAL_KEY" ]; then
  echo "=== Generating the dev unseal key ==="
  openssl rand 32 | place "$DEV_UNSEAL_KEY" "$OPENBAO_UID" 0 0600
fi

# Self-signed, matches its key, and good for CA_REDO_DAYS more.
ca_is_good() {
  [ -s "$OPENBAO_CA" ] && same_key "$OPENBAO_CA" "$CA_KEY" \
    && openssl verify -CAfile "$OPENBAO_CA" "$OPENBAO_CA" >/dev/null 2>&1 \
    && openssl x509 -in "$OPENBAO_CA" -noout -checkend $((CA_REDO_DAYS * 86400)) >/dev/null
}

# leaf_is_good <cert> <key>: not due, chains to the CA, matches the key.
leaf_is_good() {
  ! cert_due "$1" && openssl verify -CAfile "$OPENBAO_CA" "$1" >/dev/null 2>&1 && same_key "$1" "$2"
}

server_is_good() {
  leaf_is_good "$CERT" "$KEY" &&
    [ "$(openssl x509 -in "$CERT" -noout -ext subjectAltName | sed -n 2p | tr -d ' ')" = "$(echo "$SAN_AS_PRINTED" | tr -d ' ')" ]
}

if ! ca_is_good; then
  if [ "$LEAVES_ONLY" = 1 ]; then
    echo "ERROR: OpenBao's TLS CA ($OPENBAO_CA) is missing, invalid or about to expire, and only" >&2
    echo "openbao-tls-init may replace it: run \`docker compose down && docker compose up -d\`." >&2
    exit 1
  fi
  echo "=== Generating OpenBao's TLS CA ==="
  cat > "$work/ca.ext" <<'EOF'
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/ca-key.pem"
  openssl req -new -key "$work/ca-key.pem" -subj "/CN=Kernfolio OpenBao TLS CA" -out "$work/ca.csr"
  openssl x509 -req -in "$work/ca.csr" -signkey "$work/ca-key.pem" -days "$CA_DAYS" \
    -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/ca.ext" -out "$work/ca.pem" 2>/dev/null
  # Key first: if this is interrupted, ca_is_good fails next time and the CA
  # is made again. Every leaf then fails to chain and is reissued below.
  place "$CA_KEY" 0 0 0600 < "$work/ca-key.pem"
  place "$OPENBAO_CA" 0 0 0644 < "$work/ca.pem"
fi

# web's copy of the anchor. Only openbao-tls-init writes it, as web reads it
# at start only.
if [ "$LEAVES_ONLY" = 0 ] && ! cmp -s "$OPENBAO_CA" "$WEB_CA"; then
  place "$WEB_CA" 0 0 0644 < "$OPENBAO_CA"
fi
# Web's client key used to be a PKCS#12.
rm -f "$WEB_DIR/openbao-client.p12"

cat > "$work/server.ext" <<EOF
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = $SAN
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF
cat > "$work/client.ext" <<'EOF'
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF

# issue <cert> <key> <key uid> <cn> <ext>: a new key and LEAF_SECONDS cert,
# placed key first, then leaf + CA.
issue() {
  echo "=== Issuing OpenBao TLS cert $1 (CN $4) ==="
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/leaf-key.pem"
  openssl req -new -key "$work/leaf-key.pem" -subj "/CN=$4" -out "$work/leaf.csr"
  openssl x509 -req -in "$work/leaf.csr" -CA "$OPENBAO_CA" -CAkey "$CA_KEY" \
    -not_after "$(date -u -d "@$(($(date +%s) + LEAF_SECONDS))" +%Y%m%d%H%M%SZ)" \
    -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/$5" -out "$work/leaf.pem" 2>/dev/null
  place "$2" "$3" 0 0600 < "$work/leaf-key.pem"
  cat "$work/leaf.pem" "$OPENBAO_CA" | place "$1" 0 0 0644
}

# `if`, not `||`: set -e doesn't apply inside a function called left of `||`.
if ! server_is_good; then issue "$CERT" "$KEY" "$OPENBAO_UID" openbao server.ext; fi
if ! leaf_is_good "$CLI_CERT" "$CLI_KEY"; then issue "$CLI_CERT" "$CLI_KEY" "$OPENBAO_UID" openbao-cli client.ext; fi
if ! leaf_is_good "$WEB_CERT" "$WEB_KEY"; then issue "$WEB_CERT" "$WEB_KEY" "$WEB_UID" app client.ext; fi

# Quiet in cert-renewer's hourly rounds unless something was issued.
[ "$LEAVES_ONLY" = 1 ] || echo "=== OpenBao TLS certificates are current ==="
