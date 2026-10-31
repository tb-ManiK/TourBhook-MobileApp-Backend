
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/tourbhook-api-1.0.0.jar app.jar

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar app.jar"]
