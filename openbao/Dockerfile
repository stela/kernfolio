# Image for the one-shot openbao-init and openbao-db-init containers: the
# OpenBao CLI plus jq and psql. The server runs the stock image.
FROM openbao/openbao:2.7.1

USER root
RUN apk add --no-cache jq postgresql18-client
# Back to the unprivileged default; compose picks each container's user:
# openbao-init needs root (with only CAP_CHOWN) to give every key on the
# openbao_certs volume to its service, openbao-db-init runs as openbao.
USER openbao
