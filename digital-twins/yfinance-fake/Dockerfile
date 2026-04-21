# Build from source. Build context is the repository root.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY web/build.gradle.kts web/
COPY optimizer/build.gradle.kts optimizer/
COPY digital-twins/yfinance-fake/build.gradle.kts digital-twins/yfinance-fake/
COPY digital-twins/frankfurter-fake/build.gradle.kts digital-twins/frankfurter-fake/

COPY digital-twins/yfinance-fake/src digital-twins/yfinance-fake/src

RUN chmod +x gradlew && ./gradlew --no-daemon :digital-twins:yfinance-fake:bootJar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/digital-twins/yfinance-fake/build/libs/yfinance-fake-*-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
