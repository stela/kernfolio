#!/bin/sh
# Server certificate for OpenBao's own listener (openbao/config/listener.hcl).
#
# Runs as the one-shot openbao-tls-init container, *before* OpenBao starts, so
# OpenBao's PKI can't issue this cert. Instead it comes from a small bootstrap
# CA made here with openssl. The CA's key is thrown away after signing, and
# its cert, openbao-ca.pem, is the trust anchor every OpenBao client uses
# (BAO_CACERT, spring.cloud.vault.ssl.trust-store). It is not the PKI's
# ca.pem, which signs the service-to-service certs.
#
# ECDSA P-256 rather than Ed25519 like the PKI: the OpenBao UI is opened in
# browsers, and they don't accept Ed25519 certs.
#
# Idempotent: an existing cert is kept as long as it is valid for another
# RENEW_DAYS, has exactly the SANs below, chains to openbao-ca.pem and matches
# its key. So restarts don't change what clients trust. Otherwise CA and
# cert are both made again, and every client picks up the new
# openbao-ca.pem when compose next starts it (it runs after this).
set -e

. "$(dirname "$0")/common.sh"

require_dirs "$OPENBAO_DIR" "$WEB_DIR"

CERT=$OPENBAO_DIR/openbao.pem
KEY=$OPENBAO_DIR/openbao-key.pem
# openbao: the compose service name. localhost / 127.0.0.1: the healthcheck
# and `docker compose exec openbao bao ...`, and the published port in dev.
SAN="DNS:openbao,DNS:localhost,IP:127.0.0.1"
SAN_AS_PRINTED="DNS:openbao, DNS:localhost, IP Address:127.0.0.1"
VALID_DAYS=730
RENEW_DAYS=30

current_cert_is_good() {
  [ -s "$CERT" ] && [ -s "$KEY" ] && [ -s "$OPENBAO_CA" ] || return 1
  openssl x509 -in "$CERT" -noout -checkend $((RENEW_DAYS * 86400)) >/dev/null || return 1
  openssl verify -CAfile "$OPENBAO_CA" "$CERT" >/dev/null 2>&1 || return 1
  [ "$(openssl x509 -in "$CERT" -noout -ext subjectAltName | sed -n 2p | tr -d ' ' )" = "$(echo "$SAN_AS_PRINTED" | tr -d ' ')" ] || return 1
  [ "$(openssl x509 -in "$CERT" -noout -pubkey)" = "$(openssl pkey -in "$KEY" -pubout)" ]
}

if current_cert_is_good; then
  echo "=== Reusing existing OpenBao listener certificate ==="
else
  echo "=== Generating OpenBao listener certificate ==="
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT

  cat > "$work/ca.ext" <<'EOF'
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
  cat > "$work/leaf.ext" <<EOF
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = $SAN
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF

  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/ca-key.pem"
  openssl req -new -key "$work/ca-key.pem" -subj "/CN=Kernfolio OpenBao Bootstrap CA" -out "$work/ca.csr"
  openssl x509 -req -in "$work/ca.csr" -signkey "$work/ca-key.pem" -days $((VALID_DAYS + 1)) \
    -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/ca.ext" -out "$work/ca.pem" 2>/dev/null

  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/key.pem"
  openssl req -new -key "$work/key.pem" -subj "/CN=openbao" -out "$work/leaf.csr"
  openssl x509 -req -in "$work/leaf.csr" -CA "$work/ca.pem" -CAkey "$work/ca-key.pem" -days "$VALID_DAYS" \
    -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/leaf.ext" -out "$work/leaf.pem" 2>/dev/null

  # Key first, anchor last: if this is interrupted, the check above fails on
  # the next run and everything is made again.
  place "$KEY" "$OPENBAO_UID" 0 0600 < "$work/key.pem"
  cat "$work/leaf.pem" "$work/ca.pem" | place "$CERT" 0 0 0644
  place "$OPENBAO_CA" 0 0 0644 < "$work/ca.pem"
fi

# Every run, so a recreated certs_web volume gets it back.
place "$WEB_DIR/openbao-ca.pem" 0 0 0644 < "$OPENBAO_CA"

echo "=== OpenBao TLS init complete ==="
