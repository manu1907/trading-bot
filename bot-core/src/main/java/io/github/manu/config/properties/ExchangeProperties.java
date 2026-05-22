package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeProperties(
        @NotBlank String provider,
        @NotBlank String environment,
        @NotBlank String account,
        @NotBlank String market
) {
}
