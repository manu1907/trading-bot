package io.github.manu.config.properties;

import jakarta.validation.constraints.NotNull;

public record BotProperties(
        @NotNull String instanceId,
        @NotNull String targetId,
        @NotNull String timezone
) {
}
