package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.manu.config.properties.ExchangeProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceProviderProperties(
        boolean enabled,
        @Valid @NotNull Map<String, BinanceEnvironmentProperties> environments
) {
    public BinanceProviderProperties {
        environments = Map.copyOf(environments);
    }

    public BinanceProperties resolve(ExchangeProperties active) {
        if (!enabled) {
            throw new IllegalArgumentException("Binance provider is disabled");
        }
        BinanceEnvironmentProperties environment = environments.get(active.environment());
        if (environment == null) {
            throw new IllegalArgumentException("Unknown Binance environment: " + active.environment());
        }
        return environment.resolve(active);
    }
}
