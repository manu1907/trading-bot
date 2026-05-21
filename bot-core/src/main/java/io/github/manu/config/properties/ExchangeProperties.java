package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeProperties(
        @NotNull String provider,
        @NotNull String environment,
        @NotNull String account,
        @NotNull String market
) {
}
