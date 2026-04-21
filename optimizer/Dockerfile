# Multi-stage: the build stage resolves and installs every dependency into
# a project-local virtualenv via uv; the runtime stage starts from a clean
# python:3.14-slim and receives only the finished venv. Nothing is compiled
# in the runtime image, mirroring the JVM flow where a fat JAR produced at
# build time is dropped into a slim JRE image.
FROM python:3.14-slim AS build
WORKDIR /opt/optimizer

# Pin uv to a specific release for reproducibility.
COPY --from=ghcr.io/astral-sh/uv:0.11.7 /uv /uvx /usr/local/bin/

COPY pyproject.toml uv.lock ./
# Only the main dependency set (no 'dev' extra) — pytest and pip-audit don't
# belong in the runtime image.
RUN uv sync --frozen --no-install-project

COPY app/ app/
RUN uv sync --frozen --no-editable

FROM python:3.14-slim
WORKDIR /opt/optimizer
COPY --from=build /opt/optimizer/.venv /opt/optimizer/.venv
COPY app/ app/
ENV PATH="/opt/optimizer/.venv/bin:$PATH"

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
