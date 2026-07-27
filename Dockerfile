FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN groupadd --gid 10001 dataaudit \
    && useradd --uid 10001 --gid dataaudit --no-create-home --shell /usr/sbin/nologin dataaudit \
    && mkdir -p /tasks /reports /state /logs \
    && chown -R dataaudit:dataaudit /app /tasks /reports /state /logs
COPY --chown=dataaudit:dataaudit data-audit-cli/target/data-audit.jar /app/data-audit.jar

VOLUME ["/tasks", "/reports", "/state", "/logs"]

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/data-audit.jar"]
