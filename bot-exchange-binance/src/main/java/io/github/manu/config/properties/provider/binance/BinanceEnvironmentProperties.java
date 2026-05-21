package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.manu.config.properties.ExchangeProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceEnvironmentProperties(
        boolean enabled,
        @Valid @NotNull Map<String, BinanceAccountProperties> accounts
) {
    public BinanceEnvironmentProperties {
        accounts = Map.copyOf(accounts);
    }

    public BinanceProperties resolve(ExchangeProperties active) {
        if (!enabled) {
            throw new IllegalArgumentException("Binance environment is disabled: " + active.environment());
        }
        BinanceAccountProperties account = accounts.get(active.account());
        if (account == null) {
            throw new IllegalArgumentException("Unknown Binance account: " + active.account());
        }
        return account.resolve(active);
    }
}
