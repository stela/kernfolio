FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY build/libs/frankfurter-fake-*-SNAPSHOT.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
