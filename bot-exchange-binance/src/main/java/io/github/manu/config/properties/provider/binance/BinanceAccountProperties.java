package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.manu.config.properties.ExchangeProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAccountProperties(
        boolean enabled,
        @Valid @NotNull BinanceProperties.Credentials credentials,
        @Valid @NotNull Map<String, BinanceMarketProperties> markets
) {
    public BinanceAccountProperties {
        markets = Map.copyOf(markets);
    }

    public BinanceProperties resolve(ExchangeProperties active) {
        if (!enabled) {
            throw new IllegalArgumentException("Binance account is disabled: " + active.account());
        }
        BinanceMarketProperties market = markets.get(active.market());
        if (market == null) {
            throw new IllegalArgumentException("Unknown Binance market: " + active.market());
        }
        if (!market.enabled()) {
            throw new IllegalArgumentException("Binance market is disabled: " + active.market());
        }

        return new BinanceProperties(
                market.name(),
                credentials,
                market.rest(),
                market.websocket(),
                market.trading(),
                market.userData(),
                market.marginAccount(),
                market.futuresAccount()
        );
    }
}
