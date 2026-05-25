package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceOptionsAccountRequestFactory {

    private final BinanceProperties.OptionsAccount account;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceOptionsAccountRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        Objects.requireNonNull(binance, "binance");
        if (binance.optionsAccount() == null) {
            throw new IllegalArgumentException("Binance options account config is required");
        }
        account = binance.optionsAccount();
        restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest marginAccount(String privateCredential) {
        return restRequestFactory.signedUri(account.marginAccountPath(), List.of(), privateCredential);
    }

    BinanceSignedRequest positions(String symbol, String privateCredential) {
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", symbol);
        return restRequestFactory.signedUri(account.positionPath(), parameters, privateCredential);
    }

    private void add(List<BinanceRequestParameter> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.add(BinanceRequestParameter.of(name, value));
        }
    }
}
