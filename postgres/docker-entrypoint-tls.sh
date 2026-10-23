#!/bin/sh
# Postgres entrypoint wrapper: TLS on, mTLS-only TCP (see pg_hba_mtls.conf).
#
# The settings are -c flags rather than a mounted config file: the image never
# reads /etc/postgresql/conf.d, and initdb's postgresql.conf lives in PGDATA.
# They reach the temporary init-time server as well as the real one.
# postgres-key.pem is owned by postgres (uid 70), mode 0600, as postgres
# requires (openbao/init/setup.sh).
set -e

exec docker-entrypoint.sh postgres \
    -c ssl=on \
    -c ssl_cert_file=/openbao/certs/postgres.pem \
    -c ssl_key_file=/openbao/certs/postgres-key.pem \
    -c ssl_ca_file=/openbao/certs/ca.pem \
    -c ssl_min_protocol_version=TLSv1.3 \
    -c hba_file=/etc/postgresql/pg_hba.conf \
    "$@"
