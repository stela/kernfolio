# Image for the one-shot openbao-tls-init, openbao-init and openbao-db-init
# containers: the OpenBao CLI plus openssl (setup-tls.sh), jq and psql. The
# server runs the stock image.
FROM openbao/openbao:2.7.1

USER root
RUN apk add --no-cache jq openssl postgresql18-client
# Back to the unprivileged default; compose picks each container's user:
# openbao-tls-init and openbao-init need root (with only CHOWN +
# DAC_READ_SEARCH) to give every key in the certs_* volumes to its service;
# openbao-db-init runs as openbao.
USER openbao
