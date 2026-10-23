#!/bin/sh
# Postgres entrypoint wrapper: TLS on, mTLS-only TCP (see pg_hba_mtls.conf).
#
# Runs as root, like the image's own entrypoint, before it drops to `postgres`.
# Postgres refuses a server key that isn't owned by it with mode 0600, while
# the copy on the shared openbao_certs volume belongs to OpenBao's init user.
# So the key is copied on every start, which also picks up re-issued certs.
#
# The settings are -c flags rather than a mounted config file: the image never
# reads /etc/postgresql/conf.d, and initdb's postgresql.conf lives in PGDATA.
# They reach the temporary init-time server as well as the real one.
set -e

install -d -o postgres -g postgres -m 700 /run/postgresql-tls
install -o postgres -g postgres -m 600 /openbao/certs/postgres-key.pem /run/postgresql-tls/server.key

exec docker-entrypoint.sh postgres \
    -c ssl=on \
    -c ssl_cert_file=/openbao/certs/postgres.pem \
    -c ssl_key_file=/run/postgresql-tls/server.key \
    -c ssl_ca_file=/openbao/certs/ca.pem \
    -c ssl_min_protocol_version=TLSv1.3 \
    -c hba_file=/etc/postgresql/pg_hba.conf \
    "$@"
