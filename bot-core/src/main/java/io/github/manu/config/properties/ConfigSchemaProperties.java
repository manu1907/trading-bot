package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigSchemaProperties(
        @NotBlank String id,
        @NotNull Integer version,
        @NotBlank String migrationPolicy
) {
}
