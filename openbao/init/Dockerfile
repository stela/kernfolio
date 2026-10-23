FROM openbao/openbao:2.7.1

# The base image ends with `USER openbao`; package installs need root.
USER root
RUN apk add --no-cache jq postgresql18-client \
    # Pre-create the openbao_certs mount point owned by openbao. Docker copies
    # this ownership into a fresh, empty named volume on first mount, so the
    # init scripts can write certs without running as root.
    && mkdir -p /openbao/certs \
    && chown openbao:openbao /openbao/certs
USER openbao
