# One Dockerfile, two images (compose `target:`):
#   server: the OpenBao server (kernfolio-openbao)
#   init:   the one-shot openbao-init / openbao-db-init setup (kernfolio-openbao-init)

FROM openbao/openbao:2.7.1 AS server
# The server mounts openbao_certs too (the Postgres database engine reads its
# client cert from it), and it starts before openbao-init. The stock image has
# no /openbao/certs, so a fresh volume would come up root-owned and the init
# scripts (uid 100) couldn't write it. Pre-creating the mount point owned by
# openbao makes Docker give a fresh, empty volume that ownership on first mount.
USER root
RUN mkdir -p /openbao/certs && chown openbao:openbao /openbao/certs
USER openbao

FROM server AS init
USER root
RUN apk add --no-cache jq postgresql18-client
USER openbao
