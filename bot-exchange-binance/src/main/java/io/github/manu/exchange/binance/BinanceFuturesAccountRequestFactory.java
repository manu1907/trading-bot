package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceFuturesAccountRequestFactory {

    private static final String HEDGE = "HEDGE";
    private static final String ONE_WAY = "ONE_WAY";
    private static final List<String> AUTO_CLOSE_TYPES = List.of("LIQUIDATION", "ADL");

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

    BinanceSignedRequest adlQuantiles(String symbol, String privateCredential) {
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", symbol);
        return restRequestFactory.signedUri(account.adlQuantilePath(), parameters, privateCredential);
    }

    BinanceSignedRequest forceOrders(BinanceFuturesForceOrderQuery query, String privateCredential) {
        BinanceFuturesForceOrderQuery safeQuery = query == null
                ? new BinanceFuturesForceOrderQuery(null, null, null, null, null)
                : query;
        validateForceOrderQuery(safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", safeQuery.symbol());
        add(parameters, "autoCloseType", safeQuery.autoCloseType());
        add(parameters, "startTime", safeQuery.startTime());
        add(parameters, "endTime", safeQuery.endTime());
        add(parameters, "limit", safeQuery.limit());
        return restRequestFactory.signedUri(account.forceOrdersPath(), parameters, privateCredential);
    }

    BinanceSignedRequest income(BinanceFuturesIncomeQuery query, String privateCredential) {
        BinanceFuturesIncomeQuery safeQuery = query == null
                ? new BinanceFuturesIncomeQuery(null, null, null, null, null, null)
                : query;
        validateIncomeQuery(safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", safeQuery.symbol());
        add(parameters, "incomeType", safeQuery.incomeType());
        add(parameters, "startTime", safeQuery.startTime());
        add(parameters, "endTime", safeQuery.endTime());
        add(parameters, "page", safeQuery.page());
        add(parameters, "limit", safeQuery.limit());
        return restRequestFactory.signedUri(account.incomePath(), parameters, privateCredential);
    }

    URI fundingRates(BinanceFuturesFundingRateQuery query) {
        BinanceFuturesFundingRateQuery safeQuery = query == null
                ? new BinanceFuturesFundingRateQuery(null, null, null, null)
                : query;
        validateFundingRateQuery(safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", safeQuery.symbol());
        add(parameters, "startTime", safeQuery.startTime());
        add(parameters, "endTime", safeQuery.endTime());
        add(parameters, "limit", safeQuery.limit());
        return restRequestFactory.publicUri(account.fundingRatePath(), parameters);
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

    private void validateForceOrderQuery(BinanceFuturesForceOrderQuery query) {
        if (hasText(query.autoCloseType()) && !AUTO_CLOSE_TYPES.contains(query.autoCloseType())) {
            throw new IllegalArgumentException("autoCloseType must be one of " + AUTO_CLOSE_TYPES);
        }
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requirePositive("limit", query.limit());
        requireOrderedTimes(query.startTime(), query.endTime());
        if (query.limit() != null && query.limit() > 100) {
            throw new IllegalArgumentException("limit must be at most 100");
        }
    }

    private void validateIncomeQuery(BinanceFuturesIncomeQuery query) {
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requireOrderedTimes(query.startTime(), query.endTime());
        requirePositive("page", query.page());
        requirePositive("limit", query.limit());
        if (query.limit() != null && query.limit() > 1000) {
            throw new IllegalArgumentException("limit must be at most 1000");
        }
    }

    private void validateFundingRateQuery(BinanceFuturesFundingRateQuery query) {
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        if (marketType == BinanceMarketType.FUTURES_COIN_M) {
            requireText("symbol", query.symbol());
        }
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requireOrderedTimes(query.startTime(), query.endTime());
        requirePositive("limit", query.limit());
        if (query.limit() != null && query.limit() > 1000) {
            throw new IllegalArgumentException("limit must be at most 1000");
        }
    }

    private void requireOrderedTimes(Long startTime, Long endTime) {
        if (startTime != null && endTime != null && startTime > endTime) {
            throw new IllegalArgumentException("startTime must be less than or equal to endTime");
        }
    }

    private void requirePositive(String field, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive when configured");
        }
    }

    private void requirePositive(String field, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive when configured");
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

    private void add(List<BinanceRequestParameter> parameters, String name, Long value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, Integer value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
