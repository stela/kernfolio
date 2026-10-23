# Multi-stage: the build stage installs a uv-managed CPython (version from
# .python-version) and every dependency into a project-local virtualenv; the
# runtime stage is distroless and receives only that Python and the venv.
# Nothing is compiled in the runtime image, mirroring the JVM flow where a
# fat JAR produced at build time is dropped into a slim JRE image.
#
# Runtime base: Google's distroless cc (glibc, libstdc++, libgomp, libssl,
# tzdata, CA certs). No shell, no package manager, no setuid binaries. Its own
# python3 variant ships Debian's 3.13, hence the uv-managed 3.14. The build
# stage is the same Debian release, so the glibc matches.
FROM debian:trixie-slim AS build

# Pin uv to a specific release for reproducibility.
COPY --from=ghcr.io/astral-sh/uv:0.11.7 /uv /uvx /usr/local/bin/

# Python lands at the same path in both stages; the venv's interpreter links
# to it. (No UV_COMPILE_BYTECODE: it adds ~120 MB; as before, modules compile
# in memory at startup since the non-root user can't write next to them.)
ENV UV_PYTHON_INSTALL_DIR=/opt/python \
    UV_PYTHON_PREFERENCE=only-managed \
    UV_LINK_MODE=copy

WORKDIR /opt/optimizer
COPY .python-version pyproject.toml uv.lock ./
# Trim the interpreter to what a headless server runs:
# - pip: every CPython 3.14 distribution preinstalls it; the app runs from the
#   venv and never needs it, and scanners flag the libraries it vendors.
# - Tcl/Tk, tkinter, IDLE, turtledemo: GUI.
# - C headers, *-config, pydoc: only for building extensions / interactive use.
RUN uv python install \
    && cd "$(dirname "$(dirname "$(uv python find)")")" \
    && rm -rf lib/python3*/site-packages/pip lib/python3*/site-packages/pip-*.dist-info \
              lib/python3*/ensurepip/_bundled bin/pip* \
              lib/tcl* lib/tk* lib/itcl* lib/libtcl* lib/libtk* \
              lib/python3*/tkinter lib/python3*/idlelib lib/python3*/turtledemo \
              lib/python3*/lib-dynload/_tkinter* bin/idle* \
              include lib/python3*/config-* bin/*-config bin/pydoc*
# Only the main dependency set (no 'dev' extra) — pytest and pip-audit don't
# belong in the runtime image.
RUN uv sync --frozen --no-install-project

COPY app/ app/
RUN uv sync --frozen --no-editable

FROM gcr.io/distroless/cc-debian13:nonroot
COPY --from=build /opt/python /opt/python
COPY --from=build /opt/optimizer/.venv /opt/optimizer/.venv
WORKDIR /opt/optimizer
COPY app/ app/
COPY run_mtls.py ./
ENV PATH="/opt/optimizer/.venv/bin:${PATH}"

# distroless's `nonroot` (65532), whose home is yfinance's tz/cookie cache.
# openbao/init/setup.sh gives it the optimizer's two private keys.
USER 65532:65532

EXPOSE 8000

# run_mtls.py enables TLS when OpenBao-issued certs are mounted at /openbao/certs
# (always the case in this stack). Plain `uvicorn …` would leave the server
# on HTTP while the web client dials https://optimizer:8000.
CMD ["python", "run_mtls.py"]
