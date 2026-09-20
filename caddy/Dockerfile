# Stock Caddy, but running as an unprivileged user. It listens on 8080/8443
# inside the container (see Caddyfile); docker publishes those as 80/443, so
# nothing here ever needs a privileged port.
FROM caddy:2-alpine

# The upstream binary carries cap_net_bind_service as a file capability. We
# don't need it, and with `no-new-privileges` + `cap_drop: ALL` (see
# docker-compose.yml) the kernel refuses to exec a binary whose file caps it
# can't grant — so strip it.
RUN apk add --no-cache libcap-setcap \
    && setcap -r /usr/bin/caddy \
    && apk del libcap-setcap \
    && addgroup -g 1000 caddy \
    && adduser -D -H -u 1000 -G caddy caddy \
    && mkdir -p /data /config \
    && chown caddy:caddy /data /config

# Fresh named volumes inherit this ownership on first mount. Volumes created
# by the old root-running container stay root-owned and must be removed once.
USER caddy
