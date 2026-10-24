#!/bin/sh
# Mutual TLS for OpenBao's own listener (openbao/config/listener.hcl): its
# server cert, and the client certs every OpenBao client must present.
#
# Runs as the one-shot openbao-tls-init container, *before* OpenBao starts, so
# OpenBao's PKI can't issue these certs. Instead they come from a small
# bootstrap CA made here with openssl. The CA's key is thrown away after
# signing, and its cert, openbao-ca.pem, is the one trust anchor on both
# sides: clients verify the server with it (BAO_CACERT,
# spring.cloud.vault.ssl.trust-store) and the listener only accepts client
# certs that chain to it (tls_client_ca_file). It is not the PKI's ca.pem,
# which signs the service-to-service certs; certs from that PKI are refused.
#
# ECDSA P-256 rather than Ed25519 like the PKI: the OpenBao UI is opened in
# browsers, and they don't accept Ed25519 certs.
#
# Files (certs root 0644, keys owned by their user at 0600):
#   certs_openbao: openbao.pem + openbao-key.pem (server),
#                  openbao-client.pem + openbao-client-key.pem (CN openbao-cli:
#                  the server container's own `bao` CLI, openbao-db-init, and
#                  openbao-init, which reads it as root), openbao-ca.pem
#   certs_web:     openbao-client.p12 (CN app, for Spring Cloud Vault, which
#                  can't read a PEM private key), openbao-ca.pem
#
# Idempotent: existing certs are kept as long as every one of them is valid
# for another RENEW_DAYS, chains to openbao-ca.pem and matches its key, and
# the server cert has exactly the SANs below. So restarts don't change what
# anyone trusts. Otherwise the CA and all certs are made again (there is no
# CA key to issue just one), and OpenBao and its clients pick them up when
# compose next starts them; a running OpenBao must be restarted.
set -e

. "$(dirname "$0")/common.sh"

require_dirs "$OPENBAO_DIR" "$WEB_DIR"

CERT=$OPENBAO_DIR/openbao.pem
KEY=$OPENBAO_DIR/openbao-key.pem
CLI_CERT=$OPENBAO_DIR/openbao-client.pem
CLI_KEY=$OPENBAO_DIR/openbao-client-key.pem
WEB_P12=$WEB_DIR/openbao-client.p12
WEB_CA=$WEB_DIR/openbao-ca.pem
# openbao: the compose service name. localhost / 127.0.0.1: the healthcheck
# and `docker compose exec openbao bao ...`, and the published port in dev.
SAN="DNS:openbao,DNS:localhost,IP:127.0.0.1"
SAN_AS_PRINTED="DNS:openbao, DNS:localhost, IP Address:127.0.0.1"
VALID_DAYS=730
RENEW_DAYS=30

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# cert_is_good <cert> <key|-> : unexpired for RENEW_DAYS, chains to the
# anchor, and (unless the key is -) matches the key.
cert_is_good() {
  [ -s "$1" ] || return 1
  openssl x509 -in "$1" -noout -checkend $((RENEW_DAYS * 86400)) >/dev/null || return 1
  openssl verify -CAfile "$OPENBAO_CA" "$1" >/dev/null 2>&1 || return 1
  [ "$2" = - ] && return 0
  [ -s "$2" ] && [ "$(openssl x509 -in "$1" -noout -pubkey)" = "$(openssl pkey -in "$2" -pubout)" ]
}

current_certs_are_good() {
  [ -s "$OPENBAO_CA" ] && cmp -s "$OPENBAO_CA" "$WEB_CA" || return 1
  cert_is_good "$CERT" "$KEY" || return 1
  [ "$(openssl x509 -in "$CERT" -noout -ext subjectAltName | sed -n 2p | tr -d ' ')" = "$(echo "$SAN_AS_PRINTED" | tr -d ' ')" ] || return 1
  cert_is_good "$CLI_CERT" "$CLI_KEY" || return 1
  # The PKCS#12 has an empty password, see below.
  [ -s "$WEB_P12" ] || return 1
  openssl pkcs12 -in "$WEB_P12" -passin pass: -nokeys -clcerts -out "$work/current-web.pem" 2>/dev/null || return 1
  openssl pkcs12 -in "$WEB_P12" -passin pass: -nocerts -nodes -out "$work/current-web-key.pem" 2>/dev/null || return 1
  cert_is_good "$work/current-web.pem" "$work/current-web-key.pem"
}

if current_certs_are_good; then
  echo "=== Reusing existing OpenBao TLS certificates ==="
else
  echo "=== Generating OpenBao TLS certificates ==="

  cat > "$work/ca.ext" <<'EOF'
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
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

  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/ca-key.pem"
  openssl req -new -key "$work/ca-key.pem" -subj "/CN=Kernfolio OpenBao Bootstrap CA" -out "$work/ca.csr"
  openssl x509 -req -in "$work/ca.csr" -signkey "$work/ca-key.pem" -days $((VALID_DAYS + 1)) \
    -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/ca.ext" -out "$work/ca.pem" 2>/dev/null

  # issue <name> <cn> <ext>: $work/<name>.pem (leaf) + $work/<name>-key.pem
  issue() {
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/$1-key.pem"
    openssl req -new -key "$work/$1-key.pem" -subj "/CN=$2" -out "$work/$1.csr"
    openssl x509 -req -in "$work/$1.csr" -CA "$work/ca.pem" -CAkey "$work/ca-key.pem" -days "$VALID_DAYS" \
      -set_serial "0x$(openssl rand -hex 15)" -extfile "$work/$3" -out "$work/$1.pem" 2>/dev/null
  }
  issue server openbao server.ext
  issue cli openbao-cli client.ext
  issue web app client.ext
  rm -f "$work/ca-key.pem"

  # Spring Cloud Vault reads client keys only from a key store, and turns an
  # empty key-store-password into none at all. With no password the JDK can't
  # decrypt encrypted cert bags, so the certs are left unencrypted
  # (-certpbe NONE) and the key is encrypted with the empty password, which
  # it then tries. The file's owner and mode protect it, like every PEM key.
  cat "$work/web.pem" "$work/ca.pem" > "$work/web-chain.pem"
  openssl pkcs12 -export -in "$work/web-chain.pem" -inkey "$work/web-key.pem" -name openbao-client \
    -certpbe NONE -passout pass: -out "$work/web.p12"

  # Keys first, anchors last: if this is interrupted, the check above fails
  # on the next run and everything is made again.
  place "$KEY" "$OPENBAO_UID" 0 0600 < "$work/server-key.pem"
  place "$CLI_KEY" "$OPENBAO_UID" 0 0600 < "$work/cli-key.pem"
  place "$WEB_P12" "$WEB_UID" 0 0600 < "$work/web.p12"
  cat "$work/server.pem" "$work/ca.pem" | place "$CERT" 0 0 0644
  cat "$work/cli.pem" "$work/ca.pem" | place "$CLI_CERT" 0 0 0644
  place "$WEB_CA" 0 0 0644 < "$work/ca.pem"
  place "$OPENBAO_CA" 0 0 0644 < "$work/ca.pem"
fi

echo "=== OpenBao TLS init complete ==="
