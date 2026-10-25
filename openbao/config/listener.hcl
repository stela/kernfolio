# OpenBao's API listener, over mutual TLS. Loaded next to openbao.hcl in both
# stacks (dev adds dev.hcl). The certs come from openbao-tls-init
# (openbao/init/setup-tls.sh), because OpenBao's PKI can't issue them before
# OpenBao runs; cert-renewer renews them while it runs.
listener "tcp" {
  address         = "0.0.0.0:8200"
  tls_cert_file   = "/openbao/certs/openbao.pem"
  tls_key_file    = "/openbao/certs/openbao-key.pem"
  tls_min_version = "tls13"
  # Picks up a renewed cert and key within 30 s, no restart.
  tls_auto_reload = true

  # Every client presents a cert from OpenBao's TLS CA. Only the chain is
  # checked, not the CN (like the optimizer); the token still decides what a
  # client may do. This file is read only at start, which is why that CA
  # never changes while the stack runs (setup-tls.sh).
  tls_require_and_verify_client_cert = true
  tls_client_ca_file                 = "/openbao/certs/openbao-ca.pem"
}

api_addr = "https://openbao:8200"
