#!/bin/sh
# cert-renewer: keeps every cert and both scoped OpenBao tokens fresh while
# the stack runs, so nothing has to be restarted for them. Every
# RENEW_INTERVAL seconds (1 hour), one round:
#   1. renews OpenBao's listener and client certs that are due
#      (setup-tls.sh --leaves-only), with the CA key in openbao_tls_ca. First,
#      as it needs no OpenBao: the steps after it need the CLI's client cert;
#   2. renews its own token and web's (common.sh, renew_tokens);
#   3. reissues the PKI service certs that are due (pki-certs.sh), with its
#      token, which can do only that, and makes postgres reload its cert;
#   4. warns about trust anchors with less than a year left: those are never
#      replaced while running (README, "Certificate renewal").
# Each service picks up its new cert by itself (README, reload table). A
# failed round, e.g. while OpenBao is sealed, is retried after
# RETRY_INTERVAL (1 minute).
#
# --once: one round now, then exit with its status
# (`docker compose exec cert-renewer /openbao/init/renew.sh --once`).
set -e

. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/pki-certs.sh"

export BAO_ADDR="${BAO_ADDR:-https://openbao:8200}"
export BAO_CACERT="${BAO_CACERT:-$OPENBAO_CA}"
export BAO_CLIENT_CERT="${BAO_CLIENT_CERT:-$OPENBAO_DIR/openbao-client.pem}"
export BAO_CLIENT_KEY="${BAO_CLIENT_KEY:-$OPENBAO_DIR/openbao-client-key.pem}"
RENEW_INTERVAL="${RENEW_INTERVAL:-3600}"
RETRY_INTERVAL="${RETRY_INTERVAL:-60}"
PG_HOST="${PG_HOST:-postgres}"

require_dirs "$WEB_DIR" "$CADDY_DIR" "$OPTIMIZER_DIR" "$POSTGRES_DIR" "$OPENBAO_DIR" "$RENEWER_DIR" "$TLS_CA_DIR"

# Postgres reads its cert only at start and on reload. Holds the fingerprint
# of the postgres.pem the last successful reload loaded, so a reload that
# failed (postgres down) is retried. A fingerprint, not an mtime: -nt has
# 1 s resolution, so a cert replaced within the second of the last reload
# would otherwise never be loaded.
PG_RELOADED=$RENEWER_DIR/postgres-reloaded

# reload_postgres: pg_reload_conf() if postgres.pem isn't the cert postgres
# last loaded. As the superuser, over mTLS with its cert (like openbao-db-init).
reload_postgres() {
  local current key
  current="$(openssl x509 -in "$POSTGRES_DIR/postgres.pem" -noout -fingerprint -sha256)" || return 1
  [ -e "$PG_RELOADED" ] && [ "$(cat "$PG_RELOADED")" = "$current" ] && return 0
  # libpq wants a key owned by the current user (root here), at 0600: mktemp's mode.
  key="$(mktemp)" || return 1
  cat "$OPENBAO_DIR/postgres-admin-client-key.pem" > "$key"
  if PGSSLMODE=verify-full PGSSLROOTCERT="$OPENBAO_DIR/ca.pem" \
    PGSSLCERT="$OPENBAO_DIR/postgres-admin-client.pem" PGSSLKEY="$key" \
    PGCHANNELBINDING=disable PGPASSWORD="$(cat "$OPENBAO_DIR/pg_root_password")" PGCONNECT_TIMEOUT=10 \
    psql -h "$PG_HOST" -U kernfolio -d kernfolio -qAtc 'SELECT pg_reload_conf()' >/dev/null; then
    echo "=== postgres reloaded its certificate ==="
    printf '%s\n' "$current" > "$PG_RELOADED"
  else
    echo "WARNING: couldn't make postgres reload its certificate; retrying next round" >&2
  fi
  rm -f -- "$key"
}

# warn_if_expiring <name> < cert.pem: less than a year left. Reads the PEM
# from stdin, so a cert fetched from OpenBao needs no temp file.
warn_if_expiring() {
  local pem
  pem="$(cat)"
  if ! printf '%s\n' "$pem" | openssl x509 -noout -checkend $((365 * 86400)) >/dev/null 2>&1; then
    echo "WARNING: $1 expires $(printf '%s\n' "$pem" | openssl x509 -noout -enddate | cut -d= -f2); see README, \"Certificate renewal\"" >&2
  fi
}

round() {
  "$(dirname "$0")/setup-tls.sh" --leaves-only || return 1
  token_works "$RENEWER_TOKEN" || {
    echo "ERROR: cert-renewer's OpenBao token doesn't work (OpenBao sealed, or the token expired:" \
      "README, \"OpenBao: first start, restarts, starting over\")" >&2
    return 1
  }
  BAO_TOKEN="$(cat "$RENEWER_TOKEN")"
  export BAO_TOKEN
  renew_tokens || return 1
  issue_service_certs || return 1
  reload_postgres
  warn_if_expiring "The PKI root CA" < "$WEB_DIR/ca.pem"
  local intermediate
  intermediate="$(bao read -field=certificate pki_int/cert/ca 2>/dev/null)" &&
    printf '%s\n' "$intermediate" | warn_if_expiring "The PKI intermediate CA"
  warn_if_expiring "OpenBao's TLS CA" < "$OPENBAO_CA"
}

if [ "${1:-}" = --once ]; then
  round
  exit $?
fi

# PID 1 gets no default signal handling: stop on TERM between and during sleeps.
trap 'exit 0' TERM INT

echo "=== cert-renewer: a round every ${RENEW_INTERVAL}s ==="
while :; do
  if round; then
    wait_for="$RENEW_INTERVAL"
  else
    echo "WARNING: renewal round failed; retrying in ${RETRY_INTERVAL}s" >&2
    wait_for="$RETRY_INTERVAL"
  fi
  sleep "$wait_for" &
  wait $! || true
done
