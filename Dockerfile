FROM eclipse-temurin:25-jre

ARG IMAGE_CREATED="unknown"
ARG IMAGE_REVISION="unknown"
ARG IMAGE_SOURCE="https://github.com/manu1907/trading-bot"
ARG IMAGE_VERSION="dev"

LABEL org.opencontainers.image.title="trading-bot"
LABEL org.opencontainers.image.description="Live trading-bot runtime image"
LABEL org.opencontainers.image.created="${IMAGE_CREATED}"
LABEL org.opencontainers.image.revision="${IMAGE_REVISION}"
LABEL org.opencontainers.image.source="${IMAGE_SOURCE}"
LABEL org.opencontainers.image.version="${IMAGE_VERSION}"

WORKDIR /app

RUN groupadd --system tradingbot \
    && useradd --system --gid tradingbot --home-dir /app --shell /usr/sbin/nologin tradingbot \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config /app/data/journal /app/data/projection /app/data/audit \
    && chown -R tradingbot:tradingbot /app

COPY --chown=tradingbot:tradingbot bot-app/build/libs/bot-app.jar /app/trading-bot.jar
COPY --chown=tradingbot:tradingbot config /app/config

ENV SPRING_PROFILES_ACTIVE=live
ENV BOT_CONFIG_DIR=/app/config
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.nio.channels.spi=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

EXPOSE 8080

USER tradingbot:tradingbot

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["curl", "-fsS", "http://127.0.0.1:8080/actuator/health/readiness"]

ENTRYPOINT ["java", "-jar", "/app/trading-bot.jar"]
