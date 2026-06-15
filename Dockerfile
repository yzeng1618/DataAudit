FROM eclipse-temurin:17-jre

WORKDIR /app
COPY data-audit-cli/target/data-audit.jar /app/data-audit.jar
RUN mkdir -p /tasks /reports /state /logs

VOLUME ["/tasks", "/reports", "/state", "/logs"]

ENTRYPOINT ["java", "-jar", "/app/data-audit.jar"]
