#!/bin/sh
# Caddy, plus a reload whenever cert-renewer renews its client cert for the
# hop to web: Caddy reads tls_client_auth's files only when it loads its
# config. `caddy reload --force` (admin API on localhost:2019) loads it again
# without dropping connections. A failed reload, e.g. a cert and key caught
# mid-renewal, keeps the old config and is retried on the next check.
set -u

CONFIG=/etc/caddy/Caddyfile
CERT=/openbao/certs/caddy-client.pem
KEY=/openbao/certs/caddy-client-key.pem
INTERVAL="${CERT_CHECK_INTERVAL:-60}"

caddy run --config "$CONFIG" --adapter caddyfile &
caddy_pid=$!
# This shell is PID 1: hand stop signals on to Caddy.
trap 'kill -TERM "$caddy_pid" 2>/dev/null' TERM INT

fingerprint() { cat "$CERT" "$KEY" 2>/dev/null | sha256sum; }
loaded="$(fingerprint)"

while kill -0 "$caddy_pid" 2>/dev/null; do
  sleep "$INTERVAL" &
  wait $! || true
  current="$(fingerprint)"
  [ "$current" = "$loaded" ] && continue
  if caddy reload --config "$CONFIG" --adapter caddyfile --force; then
    echo "caddy/run.sh: reloaded for a renewed $CERT"
    loaded="$current"
  else
    echo "caddy/run.sh: reload failed; retrying in ${INTERVAL}s" >&2
  fi
done

wait "$caddy_pid"
