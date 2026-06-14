package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerImageContractTest {

    @Test
    void dockerfile_defines_live_runtime_contract() throws IOException {
        String dockerfile = Files.readString(resolve("Dockerfile"));

        assertThat(dockerfile)
                .contains("FROM eclipse-temurin:25-jre")
                .contains("ARG IMAGE_CREATED=\"unknown\"")
                .contains("ARG IMAGE_REVISION=\"unknown\"")
                .contains("ARG IMAGE_SOURCE=\"https://github.com/manu1907/trading-bot\"")
                .contains("ARG IMAGE_VERSION=\"dev\"")
                .contains("org.opencontainers.image.created=\"${IMAGE_CREATED}\"")
                .contains("org.opencontainers.image.revision=\"${IMAGE_REVISION}\"")
                .contains("org.opencontainers.image.source=\"${IMAGE_SOURCE}\"")
                .contains("org.opencontainers.image.version=\"${IMAGE_VERSION}\"")
                .contains("COPY --chown=tradingbot:tradingbot bot-app/build/libs/bot-app.jar /app/trading-bot.jar")
                .contains("COPY --chown=tradingbot:tradingbot config /app/config")
                .contains("ENV SPRING_PROFILES_ACTIVE=live")
                .contains("ENV BOT_CONFIG_DIR=/app/config")
                .contains("--enable-native-access=ALL-UNNAMED")
                .contains("--add-opens=java.base/java.nio=ALL-UNNAMED")
                .contains("USER tradingbot:tradingbot")
                .contains("HEALTHCHECK")
                .contains("/actuator/health/readiness")
                .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/trading-bot.jar\"]");
    }

    @Test
    void dockerignore_keeps_context_limited_to_runtime_inputs() throws IOException {
        String dockerignore = Files.readString(resolve(".dockerignore"));

        assertThat(dockerignore)
                .contains("*")
                .contains("!bot-app/build/libs/bot-app.jar")
                .contains("!config/**");
    }

    @Test
    void github_actions_builds_runtime_image_after_quality_gate() throws IOException {
        String workflow = Files.readString(resolve(".github/workflows/security.yml"));

        assertThat(workflow)
                .contains("container-image:")
                .contains("needs:")
                .contains("- quality-gate")
                .contains("./gradlew --no-daemon :bot-app:bootJar")
                .contains("Capture image metadata")
                .contains("docker/build-push-action@v6")
                .contains("IMAGE_REVISION=${{ github.sha }}")
                .contains("org.opencontainers.image.revision=${{ github.sha }}")
                .contains("metadata-file: build/container/buildx-metadata.json")
                .contains("push: false")
                .contains("tags: trading-bot:${{ github.sha }}")
                .contains("actions/upload-artifact@v5")
                .contains("trading-bot-image-metadata-${{ github.sha }}");
    }

    private Path resolve(String path) {
        Path cwd = Path.of(path);
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path parent = Path.of("..").resolve(path).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }
}
