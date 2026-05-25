package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceMarketProperties(
        boolean enabled,
        @NotNull String name,
        @Valid @NotNull BinanceProperties.Rest rest,
        @Valid @NotNull BinanceProperties.Websocket websocket,
        @Valid @NotNull BinanceProperties.Trading trading,
        @Valid BinanceProperties.UserDataStream userData,
        @Valid @NotNull BinanceProperties.MarketDataStream marketData,
        @Valid @NotNull BinanceProperties.Reconciliation reconciliation,
        @Valid BinanceProperties.MarginAccount marginAccount,
        @Valid BinanceProperties.FuturesAccount futuresAccount,
        @Valid BinanceProperties.OptionsAccount optionsAccount
) {
}
