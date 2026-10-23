-- Pre-create extensions that require superuser privileges.
-- OpenBao-generated database roles won't have superuser access.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
