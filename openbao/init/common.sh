# Sourced by setup-tls.sh, setup.sh and renew.sh, which write the certs_* volumes.
#
# ── Where secrets go ─────────────────────────────────────────────────
# One volume per consumer, each mounted only into its service (at
# /openbao/certs there) and into the init containers at /openbao/certs/<name>.
# So even root in one container can't read another service's key. Within a
# volume, private keys also belong to the service's uid at 0600 (libpq and
# pgjdbc insist on that). The init containers run as root with only
# CAP_CHOWN and CAP_DAC_READ_SEARCH (see docker-compose.yml); the uids are
# pinned via `user:` there — change both together.
CERTS=/openbao/certs
WEB_DIR=$CERTS/web              # certs_web: web
CADDY_DIR=$CERTS/caddy          # certs_caddy: caddy
OPTIMIZER_DIR=$CERTS/optimizer  # certs_optimizer: optimizer
POSTGRES_DIR=$CERTS/postgres    # certs_postgres: postgres
OPENBAO_DIR=$CERTS/openbao      # certs_openbao: openbao server, openbao-db-init
RENEWER_DIR=$CERTS/renewer      # certs_renewer: openbao-init, cert-renewer (root)
# openbao_tls_ca: the key of OpenBao's listener CA (setup-tls.sh), root 0600,
# in openbao-tls-init and cert-renewer only.
TLS_CA_DIR=/openbao/tls-ca

WEB_UID=1002        # Paketo buildpack image (web)
CADDY_UID=1000      # caddy/Dockerfile
OPTIMIZER_UID=65532 # optimizer/Dockerfile (distroless `nonroot`)
POSTGRES_UID=70     # postgres:18-alpine
OPENBAO_UID=100     # openbao/openbao image: openbao server + openbao-db-init

# Trust anchor for OpenBao's own listener (setup-tls.sh), separate from the
# PKI's ca.pem: it has to exist before OpenBao, and so before its PKI.
OPENBAO_CA=$OPENBAO_DIR/openbao-ca.pem

# Scoped, periodic OpenBao tokens that setup.sh mints at bootstrap (see there).
WEB_TOKEN=$WEB_DIR/openbao-token
RENEWER_TOKEN=$RENEWER_DIR/openbao-token

# token_works <file>: the token in <file> exists and OpenBao accepts it.
token_works() {
  [ -s "$1" ] && BAO_TOKEN="$(cat "$1")" bao token lookup >/dev/null 2>&1
}

# cert_time <cert> <startdate|enddate>: that date in epoch seconds.
cert_time() {
  date -u -D '%Y-%m-%d %H:%M:%SZ' -d "$(openssl x509 -in "$1" -noout -"$2" -dateopt iso_8601 | cut -d= -f2)" +%s
}

# cert_due <cert>: missing, unreadable, or less than a third of its lifetime
# left (10 of 30 days for the PKI's service certs), so it's renewed well
# before it expires, even with a few failed rounds in between.
cert_due() {
  local start end
  [ -s "$1" ] || return 0
  start="$(cert_time "$1" startdate)" && end="$(cert_time "$1" enddate)" || return 0
  [ $((end - $(date +%s))) -lt $(((end - start) / 3)) ]
}

# same_key <cert> <key>: the key exists and belongs to the cert.
same_key() {
  [ -s "$2" ] && [ "$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null)" = "$(openssl pkey -in "$2" -pubout 2>/dev/null)" ]
}

# renew_tokens: renews cert-renewer's token (BAO_TOKEN) and web's, so neither
# expires while web is down. Fails only if the renewer's own renewal fails.
renew_tokens() {
  bao token renew >/dev/null || return 1
  if token_works "$WEB_TOKEN"; then
    BAO_TOKEN="$(cat "$WEB_TOKEN")" bao token renew >/dev/null || return 1
  else
    echo "WARNING: web's OpenBao token no longer works; run openbao-init once with BAO_INIT_TOKEN" \
      "(README, \"OpenBao: first start, restarts, starting over\")" >&2
  fi
}

# place <dest> <uid> <gid> <mode>: write stdin to <dest> with that owner and
# mode. Goes through a temp file and rename: with no DAC_OVERRIDE, root can't
# open another user's 0600 file for writing (DAC_READ_SEARCH only lets it
# read), but it may replace it in the root-owned directory.
place() {
  local dest="$1" tmp="$1.tmp.$$"
  cat > "$tmp"
  chmod "$4" "$tmp"
  chown "$2:$3" "$tmp"
  mv -f "$tmp" "$dest"
}

# require_dirs <dir>...: fail unless every volume is mounted.
require_dirs() {
  for d in "$@"; do
    [ -d "$d" ] || { echo "ERROR: $d is not mounted" >&2; exit 1; }
  done
}

umask 077
