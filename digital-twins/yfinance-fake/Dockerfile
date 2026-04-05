FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY build/libs/yfinance-fake-*-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
