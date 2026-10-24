# Dev only (docker-compose.dev.yml, and the tests' OpenBaoContainer): loaded
# after openbao.hcl and listener.hcl, so dev runs prod's Raft storage and TLS
# listener. Not `bao server -dev`: that keeps everything in memory (a new PKI
# root on every restart) and always opens an extra plain-HTTP listener.
#
# Instead OpenBao unseals itself with a static key and initializes itself on
# first start. Both are fine for dev only: the key sits next to the data it
# protects. Production is initialized and unsealed by an operator.

# 32 random bytes, made by openbao-tls-init (setup-tls.sh) when
# OPENBAO_DEV_UNSEAL_KEY=1.
seal "static" {
  current_key_id = "dev-1"
  current_key    = "file:///openbao/certs/dev-unseal.key"
}

# Runs once, when the storage is empty. Self-init revokes its own root token
# afterwards, so it makes the well-known dev token: an orphan, so it outlives
# that token. setup.sh then configures everything else, as in production.
initialize "dev" {
  request "dev-root-token" {
    operation = "update"
    path      = "auth/token/create-orphan"
    data = {
      id                = { eval_type = "string", eval_source = "env", env_var = "BAO_DEV_ROOT_TOKEN", require_present = true }
      policies          = ["root"]
      no_default_policy = true
    }
  }
}
