# web's token (certs_web/openbao-token), written by openbao/init/setup.sh.
# Token self-lookup and renewal come with the default policy.

# Spring Cloud Vault's KV contexts: kernfolio and application, plus /<profile>.
path "secret/data/kernfolio"     { capabilities = ["read"] }
path "secret/data/kernfolio/*"   { capabilities = ["read"] }
path "secret/data/application"   { capabilities = ["read"] }
path "secret/data/application/*" { capabilities = ["read"] }

# The postgres superuser password is not web's business.
path "secret/data/kernfolio/pg-root" { capabilities = ["deny"] }

# Dynamic database credentials, and renewing / revoking their leases.
path "database/creds/app" { capabilities = ["read"] }
path "sys/leases/renew"   { capabilities = ["update"] }
path "sys/leases/revoke"  { capabilities = ["update"] }
