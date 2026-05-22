package io.github.manu.config.properties;

import jakarta.validation.constraints.NotBlank;

public record BotProperties(
        @NotBlank String instanceId,
        @NotBlank String targetId,
        @NotBlank String timezone
) {
}
