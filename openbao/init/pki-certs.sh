# Sourced by setup.sh (openbao-init) and renew.sh (cert-renewer), after
# common.sh and with BAO_TOKEN set: issues the PKI's service certs, each only
# when it's due.

# Lifetime of each service cert. The kernfolio-service role caps it at 720h;
# shorter is only for testing renewal.
LEAF_TTL="${LEAF_TTL:-720h}"

# names_match <cert> <cn> <alt names>: the cert's DNS SANs are exactly the CN
# plus the alt names (OpenBao adds the CN). Changing a SAN below reissues.
names_match() {
  [ "$(openssl x509 -in "$1" -noout -ext subjectAltName 2>/dev/null | sed 1d | tr -d ' ' | tr ',' '\n' | sed 's/^DNS://' | sort)" = \
    "$(printf '%s\n' "$2" $(echo "$3" | tr ',' ' ') | sort -u)" ]
}

# issue_if_due <dir> <cn> <alt_names> <file stem> <key owner uid>: a new
# <stem>.pem (public: root, 0644) + <stem>-key.pem (<key owner uid>, 0600),
# unless the current one isn't due (cert_due in common.sh), chains to
# <dir>/ca.pem, matches its key and has the right names.
#
# The cert file is leaf + issuing intermediate so servers present a full
# chain. ca.pem holds only the root; without the intermediate in the server's
# cert, clients get PKIX path-building failures at handshake.
#
# Key first, then cert: a consumer reloading in between sees a mismatched
# pair, keeps its old one and tries again (see the README's reload table).
issue_if_due() {
  local dir="$1" cn="$2" alt="$3" stem="$4" owner="$5" json
  local cert="$dir/$stem.pem" key="$dir/$stem-key.pem"
  if ! cert_due "$cert" && same_key "$cert" "$key" && names_match "$cert" "$cn" "$alt" &&
    openssl verify -CAfile "$dir/ca.pem" -untrusted "$cert" "$cert" >/dev/null 2>&1; then
    return 0
  fi
  echo "=== Issuing $stem.pem (CN $cn) ==="
  json="$(bao write -format=json pki_int/issue/kernfolio-service private_key_format=pkcs8 \
    common_name="$cn" ttl="$LEAF_TTL" ${alt:+alt_names="$alt"})" || return 1
  printf '%s' "$json" | jq -r '.data.private_key' | place "$key" "$owner" 0 0600
  printf '%s' "$json" | jq -r '.data.certificate, .data.issuing_ca' | place "$cert" 0 0 0644
}

# issue_service_certs: every service cert. Returns non-zero if any failed.
issue_service_certs() {
  local failed=0
  # openbao-init and cert-renewer may both run at once (`up` while the stack runs).
  exec 9> "$RENEWER_DIR/lock"
  flock 9

  # App (Spring Boot). app.pem is web's server cert for the Caddy -> web mTLS
  # hop (application-tls.yml); Caddy dials the compose service name, hence
  # the `web` SAN. app-client.pem is web's identity towards the optimizer and
  # postgres.
  issue_if_due "$WEB_DIR" app "web,localhost" app "$WEB_UID" || failed=1
  issue_if_due "$WEB_DIR" app "" app-client "$WEB_UID" || failed=1

  # Optimizer (uvicorn). optimizer-client is only for its own healthcheck.
  issue_if_due "$OPTIMIZER_DIR" optimizer "localhost" optimizer "$OPTIMIZER_UID" || failed=1
  issue_if_due "$OPTIMIZER_DIR" optimizer "" optimizer-client "$OPTIMIZER_UID" || failed=1

  # Caddy, towards web.
  issue_if_due "$CADDY_DIR" caddy "" caddy-client "$CADDY_UID" || failed=1

  # PostgreSQL, server cert only.
  issue_if_due "$POSTGRES_DIR" postgres "localhost" postgres "$POSTGRES_UID" || failed=1

  # Postgres superuser client identity. pg_hba.conf requires
  # clientcert=verify-full for user `kernfolio`, i.e. the cert CN must equal
  # the user name. Used by the OpenBao database engine, openbao-db-init and
  # cert-renewer (reload_postgres in renew.sh).
  issue_if_due "$OPENBAO_DIR" kernfolio "" postgres-admin-client "$OPENBAO_UID" || failed=1

  flock -u 9
  return "$failed"
}
