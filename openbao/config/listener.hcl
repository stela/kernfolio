# OpenBao's API listener, over TLS. Production loads this next to openbao.hcl;
# dev mode (docker-compose.dev.yml) loads only this file, which adds it to the
# dev server's own plain-HTTP listener (kept on 127.0.0.1 there). The cert
# comes from openbao-tls-init (openbao/init/setup-tls.sh), because OpenBao's
# PKI can't issue it before OpenBao runs.
listener "tcp" {
  address         = "0.0.0.0:8200"
  tls_cert_file   = "/openbao/certs/openbao.pem"
  tls_key_file    = "/openbao/certs/openbao-key.pem"
  tls_min_version = "tls13"
}

api_addr = "https://openbao:8200"
