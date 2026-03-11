FROM eclipse-temurin:17-jre

WORKDIR /app
COPY data-audit-cli/target/data-audit.jar /app/data-audit.jar

ENTRYPOINT ["java", "-jar", "/app/data-audit.jar"]
