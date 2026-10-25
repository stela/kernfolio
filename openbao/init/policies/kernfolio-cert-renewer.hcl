# cert-renewer's token (certs_renewer/openbao-token), written by
# openbao/init/setup.sh. Also used by setup.sh itself without BAO_INIT_TOKEN.
# Token self-lookup and renewal come with the default policy.
path "pki_int/issue/kernfolio-service" { capabilities = ["update"] }
