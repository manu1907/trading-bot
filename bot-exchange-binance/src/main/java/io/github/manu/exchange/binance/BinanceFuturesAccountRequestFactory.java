package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceFuturesAccountRequestFactory {

    private static final String HEDGE = "HEDGE";
    private static final String ONE_WAY = "ONE_WAY";

    private final BinanceProperties binance;
    private final BinanceProperties.FuturesAccount account;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceFuturesAccountRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        this.binance = Objects.requireNonNull(binance, "binance");
        if (this.binance.futuresAccount() == null) {
            throw new IllegalArgumentException("Binance futures account config is required");
        }
        account = this.binance.futuresAccount();
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

    BinanceSignedRequest balances(String privateCredential) {
        return restRequestFactory.signedUri(account.balancePath(), List.of(), privateCredential);
    }

    BinanceSignedRequest accountInfo(String privateCredential) {
        return restRequestFactory.signedUri(account.accountInfoPath(), List.of(), privateCredential);
    }

    BinanceSignedRequest positionRisk(BinanceFuturesPositionRiskQuery query, String privateCredential) {
        BinanceFuturesPositionRiskQuery safeQuery = query == null
                ? new BinanceFuturesPositionRiskQuery(null, null, null)
                : query;
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        validatePositionRiskQuery(marketType, safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", safeQuery.symbol());
        add(parameters, "pair", safeQuery.pair());
        add(parameters, "marginAsset", safeQuery.marginAsset());
        return restRequestFactory.signedUri(account.positionRiskPath(), parameters, privateCredential);
    }

    private void validatePositionRiskQuery(BinanceMarketType marketType, BinanceFuturesPositionRiskQuery query) {
        if (marketType == BinanceMarketType.FUTURES_USD_M) {
            if (hasText(query.pair()) || hasText(query.marginAsset())) {
                throw new IllegalArgumentException("pair and marginAsset are only supported for COIN-M position risk queries");
            }
            return;
        }
        if (hasText(query.symbol())) {
            throw new IllegalArgumentException("symbol is only supported for USD-M position risk queries");
        }
        if (hasText(query.pair()) && hasText(query.marginAsset())) {
            throw new IllegalArgumentException("only one of pair or marginAsset can be configured");
        }
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

    private void add(List<BinanceRequestParameter> parameters, String name, String value) {
        if (hasText(value)) {
            parameters.add(BinanceRequestParameter.of(name, value));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
