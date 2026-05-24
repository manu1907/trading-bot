package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

final class BinanceFuturesAccountRequestFactory {

    private static final String HEDGE = "HEDGE";
    private static final String ONE_WAY = "ONE_WAY";

    private final BinanceProperties.FuturesAccount account;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceFuturesAccountRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        Objects.requireNonNull(binance, "binance");
        if (binance.futuresAccount() == null) {
            throw new IllegalArgumentException("Binance futures account config is required");
        }
        account = binance.futuresAccount();
        restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest changePositionMode(String positionMode, String privateCredential) {
        requireOneOf("positionMode", positionMode, account.supportedPositionModes());
        return restRequestFactory.signedUri(account.positionModePath(), List.of(
                BinanceRequestParameter.of("dualSidePosition", Boolean.toString(HEDGE.equals(positionMode)))
        ), privateCredential);
    }

    BinanceSignedRequest changeMarginType(String symbol, String marginType, String privateCredential) {
        requireText("symbol", symbol);
        requireOneOf("marginType", marginType, account.supportedMarginTypes());
        return restRequestFactory.signedUri(account.marginTypePath(), List.of(
                BinanceRequestParameter.of("symbol", symbol),
                BinanceRequestParameter.of("marginType", marginType)
        ), privateCredential);
    }

    BinanceSignedRequest changeInitialLeverage(String symbol, int leverage, String privateCredential) {
        requireText("symbol", symbol);
        Integer min = account.minInitialLeverage();
        Integer max = account.maxInitialLeverage();
        if ((min != null && leverage < min) || (max != null && leverage > max)) {
            throw new IllegalArgumentException("leverage must be between " + min + " and " + max);
        }
        return restRequestFactory.signedUri(account.leveragePath(), List.of(
                BinanceRequestParameter.of("symbol", symbol),
                BinanceRequestParameter.of("leverage", Integer.toString(leverage))
        ), privateCredential);
    }

    BinanceSignedRequest changeMultiAssetsMode(boolean multiAssetsMode, String privateCredential) {
        if (account.multiAssetsModePath() == null || account.multiAssetsModePath().isBlank()) {
            throw new IllegalArgumentException("multi-assets mode is not supported for this Binance futures market");
        }
        return restRequestFactory.signedUri(account.multiAssetsModePath(), List.of(
                BinanceRequestParameter.of("multiAssetsMargin", Boolean.toString(multiAssetsMode))
        ), privateCredential);
    }

    private void requireOneOf(String field, String value, List<String> allowed) {
        requireText(field, value);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
