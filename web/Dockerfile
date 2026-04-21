# Build the fat JAR from source so the image never relies on stale host state.
# Build context is the repository root (see docker-compose.yml).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# web/build.gradle.kts registers a `tailwindBuild` Exec task that shells out to
# the `tailwindcss` standalone CLI during :web:processResources. Fetch the
# musl-linked binary for the build architecture.
ARG TAILWIND_VERSION=v4.2.2
RUN apk add --no-cache curl && \
    case "$(apk --print-arch)" in \
      x86_64)  TW_ARCH=x64 ;; \
      aarch64) TW_ARCH=arm64 ;; \
      *) echo "unsupported arch $(apk --print-arch)" >&2; exit 1 ;; \
    esac && \
    curl -fsSL "https://github.com/tailwindlabs/tailwindcss/releases/download/${TAILWIND_VERSION}/tailwindcss-linux-${TW_ARCH}-musl" \
      -o /usr/local/bin/tailwindcss && \
    chmod +x /usr/local/bin/tailwindcss

# Copy the Gradle wrapper and project metadata first so Docker can cache the
# dependency resolution layer when only source files change.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY web/build.gradle.kts web/
COPY optimizer/build.gradle.kts optimizer/
COPY digital-twins/yfinance-fake/build.gradle.kts digital-twins/yfinance-fake/
COPY digital-twins/frankfurter-fake/build.gradle.kts digital-twins/frankfurter-fake/

# Copy source for the web module (only what bootJar needs).
COPY web/src web/src

RUN chmod +x gradlew && ./gradlew --no-daemon :web:bootJar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/web/build/libs/web-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
