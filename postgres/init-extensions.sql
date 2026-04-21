-- Postgres init scripts in /docker-entrypoint-initdb.d/ run as the superuser
-- on first startup of an empty pgdata volume. The Vault database engine
-- issues unprivileged roles, so any Liquibase changeset that needs a
-- superuser-only operation (e.g. CREATE EXTENSION) must be pre-satisfied here.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
