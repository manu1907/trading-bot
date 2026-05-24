package io.github.manu.exchange.binance;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class BinanceConfigValidator {

    private static final int MAX_RECV_WINDOW_MILLIS = 60_000;
    private static final String HMAC_SHA256 = "HMAC_SHA256";
    private static final String RSA_SHA256 = "RSA_SHA256";
    private static final String ED25519 = "ED25519";
    private static final Set<String> ASYMMETRIC_SIGNATURE_ALGORITHMS = Set.of(HMAC_SHA256, RSA_SHA256, ED25519);
    private static final Set<String> RSA_SIGNATURE_ALGORITHMS = Set.of(HMAC_SHA256, RSA_SHA256);
    private static final Set<String> HMAC_SIGNATURE_ALGORITHMS = Set.of(HMAC_SHA256);
    private static final Set<String> TIMESTAMP_UNITS = Set.of("MILLISECONDS", "MICROSECONDS");
    private static final Set<String> ORDER_RESPONSE_TYPES = Set.of("ACK", "RESULT", "FULL");
    private static final Set<String> FUTURES_POSITION_MODES = Set.of("ONE_WAY", "HEDGE");
    private static final Set<String> FUTURES_MARGIN_TYPES = Set.of("CROSSED", "ISOLATED");
    private static final String LISTEN_KEY = "listen_key";
    private static final String LISTEN_TOKEN = "listen_token";
    private static final String WEBSOCKET_API = "websocket_api";
    private static final Set<String> USER_DATA_MODES = Set.of(LISTEN_KEY, LISTEN_TOKEN, WEBSOCKET_API);

    private BinanceConfigValidator() {
    }

    static void validate(ExchangeProperties active, BinanceProperties binance) {
        List<String> errors = new ArrayList<>();
        BinanceMarketType marketType = marketType(binance.marketType(), marketPath(active) + ".name", errors);

        validateCredentials(accountPath(active) + ".credentials", binance.credentials(), marketType, errors);
        validateRest(marketPath(active) + ".rest", binance.rest(), marketType, errors);
        validateWebsocket(marketPath(active) + ".websocket", binance.websocket(), errors);
        validateTrading(marketPath(active) + ".trading", binance.trading(), marketType, errors);
        if (!marketType.futures()) {
            validateUserData(marketPath(active) + ".user_data", binance.userDataStream(), marketType, false, errors);
        }
        validateFutures(active, binance, marketType, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void validateCredentials(String path,
                                            BinanceProperties.Credentials credentials,
                                            BinanceMarketType marketType,
                                            List<String> errors) {
        if (credentials == null) {
            errors.add(path + " is required");
            return;
        }

        requireText(path + ".reference", credentials.reference(), errors);
        requireText(path + ".api_key", credentials.apiKey(), errors);
        requireText(path + ".api_secret", credentials.apiSecret(), errors);
        requireOneOf(path + ".key_type", credentials.keyType(), signatureAlgorithms(marketType), errors);
        requireNonEmpty(path + ".required_permissions", credentials.requiredPermissions(), errors);
    }

    private static void validateRest(String path,
                                     BinanceProperties.Rest rest,
                                     BinanceMarketType marketType,
                                     List<String> errors) {
        if (rest == null) {
            errors.add(path + " is required");
            return;
        }

        requireUri(path + ".base_url", rest.baseUrl(), Set.of("http", "https"), errors);
        requirePath(path + ".exchange_info_path", rest.exchangeInfoPath(), errors);
        requirePath(path + ".server_time_path", rest.serverTimePath(), errors);
        requireText(path + ".api_key_header", rest.apiKeyHeader(), errors);
        requireOneOf(path + ".signature_algorithm", rest.signatureAlgorithm(), signatureAlgorithms(marketType), errors);
        requireOneOf(path + ".timestamp_unit", rest.timestampUnit(), TIMESTAMP_UNITS, errors);
        requirePositive(path + ".recv_window_millis", rest.recvWindowMillis(), errors);
        requireAtMost(path + ".recv_window_millis", rest.recvWindowMillis(), MAX_RECV_WINDOW_MILLIS, errors);
        requirePositive(path + ".connect_timeout_millis", rest.connectTimeoutMillis(), errors);
        requirePositive(path + ".response_timeout_millis", rest.responseTimeoutMillis(), errors);
        requireNonNegative(path + ".max_retries", rest.maxRetries(), errors);
        requireNonNegative(path + ".retry_backoff_millis", rest.retryBackoffMillis(), errors);
        requireStatusCodes(path + ".retry_on_status_codes", rest.retryOnStatusCodes(), errors);
        requireNonEmpty(path + ".weight_headers", rest.weightHeaders(), errors);
        requireNonEmpty(path + ".order_count_headers", rest.orderCountHeaders(), errors);
        requireOneOf(path + ".order_response_type_default", rest.orderResponseTypeDefault(), ORDER_RESPONSE_TYPES, errors);
        requireMatching(path + ".exchange_info_path", rest.exchangeInfoPath(), marketType.exchangeInfoPath(), errors);
        validateUnknownExecutionStatus(path + ".unknown_execution_status", rest.unknownExecutionStatus(), errors);
    }

    private static void validateUnknownExecutionStatus(String path,
                                                       BinanceProperties.UnknownExecutionStatus status,
                                                       List<String> errors) {
        if (status == null) {
            errors.add(path + " is required");
            return;
        }

        requireNotNull(path + ".retryable_messages", status.retryableMessages(), errors);
        requireStatusCodes(
                path + ".reconcile_before_retry_status_codes",
                status.reconcileBeforeRetryStatusCodes(),
                errors
        );
    }

    private static void validateWebsocket(String path,
                                          BinanceProperties.Websocket websocket,
                                          List<String> errors) {
        if (websocket == null) {
            errors.add(path + " is required");
            return;
        }

        requireUri(path + ".base_url", websocket.baseUrl(), Set.of("ws", "wss"), errors);
        requireOptionalPath(path + ".public_path_prefix", websocket.publicPathPrefix(), errors);
        requireOptionalPath(path + ".market_path_prefix", websocket.marketPathPrefix(), errors);
        requireOptionalPath(path + ".private_path_prefix", websocket.privatePathPrefix(), errors);
        requirePath(path + ".raw_stream_path", websocket.rawStreamPath(), errors);
        requirePath(path + ".combined_stream_path", websocket.combinedStreamPath(), errors);
        requirePositive(path + ".max_connection_lifetime_hours", websocket.maxConnectionLifetimeHours(), errors);
        requirePositive(path + ".reconnect_before_expiry_minutes", websocket.reconnectBeforeExpiryMinutes(), errors);
        requireExactlyOnePositive(
                path + ".server_ping_interval_seconds",
                websocket.serverPingIntervalSeconds(),
                path + ".server_ping_interval_minutes",
                websocket.serverPingIntervalMinutes(),
                errors
        );
        requireExactlyOnePositive(
                path + ".pong_timeout_seconds",
                websocket.pongTimeoutSeconds(),
                path + ".pong_timeout_minutes",
                websocket.pongTimeoutMinutes(),
                errors
        );
        requirePositive(path + ".max_incoming_messages_per_second", websocket.maxIncomingMessagesPerSecond(), errors);
        requirePositive(path + ".max_streams_per_connection", websocket.maxStreamsPerConnection(), errors);
        requireOptionalPositive(
                path + ".max_connection_attempts_per_five_minutes",
                websocket.maxConnectionAttemptsPerFiveMinutes(),
                errors
        );
        requireOneOf(path + ".timestamp_unit", websocket.timestampUnit(), TIMESTAMP_UNITS, errors);
    }

    private static void validateFutures(ExchangeProperties active,
                                        BinanceProperties binance,
                                        BinanceMarketType marketType,
                                        List<String> errors) {
        if (!marketType.futures()) {
            return;
        }

        String path = marketPath(active);
        validateUserData(path + ".user_data", binance.userDataStream(), marketType, true, errors);
        validateFuturesAccount(path + ".futures_account", binance.futuresAccount(), marketType, errors);
    }

    private static void validateTrading(String path,
                                        BinanceProperties.Trading trading,
                                        BinanceMarketType marketType,
                                        List<String> errors) {
        if (trading == null) {
            errors.add(path + " is required");
            return;
        }

        BinanceTradingCapability expected = BinanceTradingCapability.forMarketType(marketType);
        requireMatching(path + ".new_order_path", trading.newOrderPath(), expected.newOrderPath(), errors);
        requireOptionalMatching(path + ".test_order_path", trading.testOrderPath(), expected.testOrderPath(), errors);
        requireMatching(path + ".query_order_path", trading.queryOrderPath(), expected.queryOrderPath(), errors);
        requireMatching(path + ".cancel_order_path", trading.cancelOrderPath(), expected.cancelOrderPath(), errors);
        requireMatching(path + ".open_orders_path", trading.openOrdersPath(), expected.openOrdersPath(), errors);
        requireConfiguredPath(path + ".all_orders_path", trading.allOrdersPath(), expected.allOrdersPath(), errors);
        requireConfiguredPath(path + ".account_trades_path", trading.accountTradesPath(), expected.accountTradesPath(), errors);
        requireConfiguredPath(
                path + ".commission_rates_path",
                trading.commissionRatesPath(),
                expected.commissionRatesPath(),
                errors
        );
        requireConfiguredPath(
                path + ".prevented_matches_path",
                trading.preventedMatchesPath(),
                expected.preventedMatchesPath(),
                errors
        );
        requireConfiguredPath(
                path + ".amend_keep_priority_path",
                trading.amendKeepPriorityPath(),
                expected.amendKeepPriorityPath(),
                errors
        );
        requireConfiguredPath(
                path + ".cancel_replace_path",
                trading.cancelReplacePath(),
                expected.cancelReplacePath(),
                errors
        );
        requireConfiguredPath(path + ".sor_order_path", trading.sorOrderPath(), expected.sorOrderPath(), errors);
        requireConfiguredPath(path + ".sor_test_order_path", trading.sorTestOrderPath(), expected.sorTestOrderPath(), errors);
        requireConfiguredPath(path + ".batch_orders_path", trading.batchOrdersPath(), expected.batchOrdersPath(), errors);
        requireConfiguredPath(path + ".modify_order_path", trading.modifyOrderPath(), expected.modifyOrderPath(), errors);
        requireConfiguredPath(
                path + ".modify_multiple_orders_path",
                trading.modifyMultipleOrdersPath(),
                expected.modifyMultipleOrdersPath(),
                errors
        );
        requireConfiguredPath(
                path + ".modify_order_history_path",
                trading.modifyOrderHistoryPath(),
                expected.modifyOrderHistoryPath(),
                errors
        );
        requireConfiguredPath(
                path + ".cancel_multiple_orders_path",
                trading.cancelMultipleOrdersPath(),
                expected.cancelMultipleOrdersPath(),
                errors
        );
        requireConfiguredPath(
                path + ".cancel_all_open_orders_path",
                trading.cancelAllOpenOrdersPath(),
                expected.cancelAllOpenOrdersPath(),
                errors
        );
        requireConfiguredPath(
                path + ".countdown_cancel_all_path",
                trading.countdownCancelAllPath(),
                expected.countdownCancelAllPath(),
                errors
        );
        requireSameValues(path + ".supported_sides", trading.supportedSides(), expected.supportedSides(), errors);
        requireSameValues(path + ".supported_order_types", trading.supportedOrderTypes(), expected.supportedOrderTypes(), errors);
        requireSameValues(path + ".supported_time_in_force", trading.supportedTimeInForce(), expected.supportedTimeInForce(), errors);
        requireSameValues(
                path + ".supported_order_response_types",
                trading.supportedOrderResponseTypes(),
                expected.supportedOrderResponseTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_self_trade_prevention_modes",
                trading.supportedSelfTradePreventionModes(),
                expected.supportedSelfTradePreventionModes(),
                errors
        );
        requireSameValues(
                path + ".supported_position_sides",
                trading.supportedPositionSides(),
                expected.supportedPositionSides(),
                errors
        );
        requireSameValues(
                path + ".supported_price_match_order_types",
                trading.supportedPriceMatchOrderTypes(),
                expected.supportedPriceMatchOrderTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_working_type_order_types",
                trading.supportedWorkingTypeOrderTypes(),
                expected.supportedWorkingTypeOrderTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_working_types",
                trading.supportedWorkingTypes(),
                expected.supportedWorkingTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_price_protect_order_types",
                trading.supportedPriceProtectOrderTypes(),
                expected.supportedPriceProtectOrderTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_pegged_order_types",
                trading.supportedPeggedOrderTypes(),
                expected.supportedPeggedOrderTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_peg_price_types",
                trading.supportedPegPriceTypes(),
                expected.supportedPegPriceTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_peg_offset_types",
                trading.supportedPegOffsetTypes(),
                expected.supportedPegOffsetTypes(),
                errors
        );
        requireSameValues(
                path + ".supported_margin_side_effect_types",
                trading.supportedMarginSideEffectTypes(),
                expected.supportedMarginSideEffectTypes(),
                errors
        );
        requireSameValues(
                path + ".auto_repay_at_cancel_side_effect_types",
                trading.autoRepayAtCancelSideEffectTypes(),
                expected.autoRepayAtCancelSideEffectTypes(),
                errors
        );
        requireOptionalMatching(
                path + ".max_peg_offset_value",
                trading.maxPegOffsetValue(),
                expected.maxPegOffsetValue(),
                errors
        );
        requireMatching(path + ".supports_quote_order_qty", trading.supportsQuoteOrderQty(), expected.supportsQuoteOrderQty(), errors);
        requireMatching(path + ".supports_reduce_only", trading.supportsReduceOnly(), expected.supportsReduceOnly(), errors);
        requireMatching(path + ".supports_close_position", trading.supportsClosePosition(), expected.supportsClosePosition(), errors);
        requireMatching(path + ".supports_price_match", trading.supportsPriceMatch(), expected.supportsPriceMatch(), errors);
        requireMatching(path + ".supports_working_type", trading.supportsWorkingType(), expected.supportsWorkingType(), errors);
        requireMatching(path + ".supports_price_protect", trading.supportsPriceProtect(), expected.supportsPriceProtect(), errors);
        requireMatching(path + ".supports_pegged_orders", trading.supportsPeggedOrders(), expected.supportsPeggedOrders(), errors);
        requireMatching(path + ".supports_iceberg_qty", trading.supportsIcebergQty(), expected.supportsIcebergQty(), errors);
        requireMatching(path + ".supports_trailing_delta", trading.supportsTrailingDelta(), expected.supportsTrailingDelta(), errors);
        requireMatching(
                path + ".supports_margin_side_effect_controls",
                trading.supportsMarginSideEffectControls(),
                expected.supportsMarginSideEffectControls(),
                errors
        );
        requireMatching(
                path + ".supports_isolated_margin_flag",
                trading.supportsIsolatedMarginFlag(),
                expected.supportsIsolatedMarginFlag(),
                errors
        );
        requireMatching(
                path + ".supports_market_maker_protection",
                trading.supportsMarketMakerProtection(),
                expected.supportsMarketMakerProtection(),
                errors
        );
    }

    private static void validateUserData(String path,
                                         BinanceProperties.UserDataStream userData,
                                         BinanceMarketType marketType,
                                         boolean required,
                                         List<String> errors) {
        if (userData == null) {
            if (required) {
                errors.add(path + " is required for Binance futures markets");
            }
            return;
        }

        requireOneOf(path + ".mode", userData.mode(), USER_DATA_MODES, errors);
        if (WEBSOCKET_API.equals(userData.mode())) {
            return;
        }

        requirePath(path + ".start_path", userData.startPath(), errors);
        requireMatching(path + ".start_path", userData.startPath(), marketType.userDataStartPath(), errors);
        requirePositive(path + ".validity_minutes", userData.validityMinutes(), errors);
        requirePositive(path + ".request_weight", userData.requestWeight(), errors);

        if (LISTEN_KEY.equals(userData.mode())) {
            requirePath(path + ".keepalive_path", userData.keepalivePath(), errors);
            requirePath(path + ".close_path", userData.closePath(), errors);
            requireMatching(path + ".keepalive_path", userData.keepalivePath(), marketType.userDataStartPath(), errors);
            requireMatching(path + ".close_path", userData.closePath(), marketType.userDataStartPath(), errors);
            requirePositive(path + ".renewal_interval_minutes", userData.renewalIntervalMinutes(), errors);
        } else if (LISTEN_TOKEN.equals(userData.mode())) {
            requireOptionalPath(path + ".keepalive_path", userData.keepalivePath(), errors);
            requireOptionalPath(path + ".close_path", userData.closePath(), errors);
            requireOptionalPositive(path + ".renewal_interval_minutes", userData.renewalIntervalMinutes(), errors);
        }
    }

    private static void validateFuturesAccount(String path,
                                               BinanceProperties.FuturesAccount account,
                                               BinanceMarketType marketType,
                                               List<String> errors) {
        if (account == null) {
            errors.add(path + " is required for Binance futures markets");
            return;
        }

        requireOneOf(path + ".position_mode", account.positionMode(), FUTURES_POSITION_MODES, errors);
        requireSameValues(path + ".supported_position_modes", account.supportedPositionModes(), FUTURES_POSITION_MODES, errors);
        requireSameValues(path + ".supported_margin_types", account.supportedMarginTypes(), FUTURES_MARGIN_TYPES, errors);
        String pathPrefix = futuresPathPrefix(marketType);
        requireMatching(path + ".position_mode_path", account.positionModePath(), pathPrefix + "/v1/positionSide/dual", errors);
        requireMatching(path + ".margin_type_path", account.marginTypePath(), pathPrefix + "/v1/marginType", errors);
        requireMatching(path + ".leverage_path", account.leveragePath(), pathPrefix + "/v1/leverage", errors);
        requireMatching(path + ".balance_path", account.balancePath(), futuresAccountReadPathPrefix(marketType) + "/balance", errors);
        requireMatching(path + ".account_info_path", account.accountInfoPath(), futuresAccountReadPathPrefix(marketType) + "/account", errors);
        requireMatching(path + ".position_risk_path", account.positionRiskPath(), futuresPositionRiskPath(marketType), errors);
        requireMatching(path + ".adl_quantile_path", account.adlQuantilePath(), pathPrefix + "/v1/adlQuantile", errors);
        requireMatching(path + ".force_orders_path", account.forceOrdersPath(), pathPrefix + "/v1/forceOrders", errors);
        requireMatching(path + ".income_path", account.incomePath(), pathPrefix + "/v1/income", errors);
        requireMatching(path + ".funding_rate_path", account.fundingRatePath(), pathPrefix + "/v1/fundingRate", errors);
        if (marketType == BinanceMarketType.FUTURES_USD_M) {
            requireMatching(path + ".multi_assets_mode_path", account.multiAssetsModePath(), "/fapi/v1/multiAssetsMargin", errors);
        } else {
            requireOptionalMatching(path + ".multi_assets_mode_path", account.multiAssetsModePath(), null, errors);
            requireMatching(path + ".multi_assets_mode_expected", account.multiAssetsModeExpected(), false, errors);
        }
        requireMatching(path + ".min_initial_leverage", account.minInitialLeverage(), 1, errors);
        requireMatching(path + ".max_initial_leverage", account.maxInitialLeverage(), 125, errors);
        requireMatching(path + ".portfolio_margin_expected", account.portfolioMarginExpected(), false, errors);
    }

    private static String futuresPathPrefix(BinanceMarketType marketType) {
        return switch (marketType) {
            case FUTURES_USD_M -> "/fapi";
            case FUTURES_COIN_M -> "/dapi";
            default -> throw new IllegalArgumentException("Expected Binance futures market type");
        };
    }

    private static String futuresAccountReadPathPrefix(BinanceMarketType marketType) {
        return switch (marketType) {
            case FUTURES_USD_M -> "/fapi/v3";
            case FUTURES_COIN_M -> "/dapi/v1";
            default -> throw new IllegalArgumentException("Expected Binance futures market type");
        };
    }

    private static String futuresPositionRiskPath(BinanceMarketType marketType) {
        return switch (marketType) {
            case FUTURES_USD_M -> "/fapi/v3/positionRisk";
            case FUTURES_COIN_M -> "/dapi/v1/positionRisk";
            default -> throw new IllegalArgumentException("Expected Binance futures market type");
        };
    }

    private static BinanceMarketType marketType(String value, String path, List<String> errors) {
        if (!hasText(value)) {
            errors.add(path + " is required");
            return BinanceMarketType.SPOT;
        }
        try {
            return BinanceMarketType.fromConfigValue(value);
        } catch (IllegalArgumentException e) {
            errors.add(path + " must be one of " + enumNames(BinanceMarketType.class));
            return BinanceMarketType.SPOT;
        }
    }

    private static void requireText(String path, String value, List<String> errors) {
        if (!hasText(value)) {
            errors.add(path + " is required");
        }
    }

    private static void requirePath(String path, String value, List<String> errors) {
        requireText(path, value, errors);
        if (hasText(value) && !value.startsWith("/")) {
            errors.add(path + " must start with '/'");
        }
    }

    private static void requireOptionalPath(String path, String value, List<String> errors) {
        if (value == null) {
            return;
        }
        requirePath(path, value, errors);
    }

    private static void requireUri(String path, String value, Set<String> schemes, List<String> errors) {
        requireText(path, value, errors);
        if (!hasText(value)) {
            return;
        }

        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !schemes.contains(scheme.toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                errors.add(path + " must be an absolute URI with scheme " + schemes);
            }
        } catch (IllegalArgumentException e) {
            errors.add(path + " must be a valid absolute URI");
        }
    }

    private static void requireOneOf(String path, String value, Set<String> allowed, List<String> errors) {
        requireText(path, value, errors);
        if (hasText(value) && !allowed.contains(value)) {
            errors.add(path + " must be one of " + allowed);
        }
    }

    private static void requireNonEmpty(String path, Collection<?> values, List<String> errors) {
        if (values == null || values.isEmpty()) {
            errors.add(path + " must not be empty");
        }
    }

    private static void requireNotNull(String path, Object value, List<String> errors) {
        if (value == null) {
            errors.add(path + " is required");
        }
    }

    private static void requireStatusCodes(String path, List<Integer> values, List<String> errors) {
        requireNonEmpty(path, values, errors);
        if (values == null) {
            return;
        }
        for (Integer value : values) {
            if (value == null || value < 100 || value > 599) {
                errors.add(path + " must contain HTTP status codes between 100 and 599");
                return;
            }
        }
    }

    private static void requirePositive(String path, Integer value, List<String> errors) {
        if (value == null || value <= 0) {
            errors.add(path + " must be positive");
        }
    }

    private static void requireOptionalPositive(String path, Integer value, List<String> errors) {
        if (value != null && value <= 0) {
            errors.add(path + " must be positive when configured");
        }
    }

    private static void requireNonNegative(String path, Integer value, List<String> errors) {
        if (value == null || value < 0) {
            errors.add(path + " must be zero or positive");
        }
    }

    private static void requireAtMost(String path, Integer value, int max, List<String> errors) {
        if (value != null && value > max) {
            errors.add(path + " must be at most " + max);
        }
    }

    private static void requireExactlyOnePositive(String firstPath,
                                                  Integer first,
                                                  String secondPath,
                                                  Integer second,
                                                  List<String> errors) {
        boolean hasFirst = first != null;
        boolean hasSecond = second != null;
        if (hasFirst == hasSecond) {
            errors.add("exactly one of " + firstPath + " and " + secondPath + " must be configured");
            return;
        }
        requireOptionalPositive(firstPath, first, errors);
        requireOptionalPositive(secondPath, second, errors);
    }

    private static void requireMatching(String path, String actual, String expected, List<String> errors) {
        if (actual != null && expected != null && !expected.equals(actual)) {
            errors.add(path + " must be " + expected);
        }
    }

    private static void requireOptionalMatching(String path, String actual, String expected, List<String> errors) {
        if (expected == null) {
            if (actual != null) {
                errors.add(path + " must be omitted");
            }
            return;
        }
        requireMatching(path, actual, expected, errors);
    }

    private static void requireConfiguredPath(String path, String actual, String expected, List<String> errors) {
        if (expected == null) {
            requireOptionalMatching(path, actual, null, errors);
            return;
        }
        requirePath(path, actual, errors);
        requireMatching(path, actual, expected, errors);
    }

    private static void requireOptionalMatching(String path, Integer actual, Integer expected, List<String> errors) {
        if (expected == null) {
            if (actual != null) {
                errors.add(path + " must be omitted");
            }
            return;
        }
        if (!expected.equals(actual)) {
            errors.add(path + " must be " + expected);
        }
    }

    private static void requireMatching(String path, Integer actual, Integer expected, List<String> errors) {
        if (!expected.equals(actual)) {
            errors.add(path + " must be " + expected);
        }
    }

    private static void requireMatching(String path, boolean actual, boolean expected, List<String> errors) {
        if (actual != expected) {
            errors.add(path + " must be " + expected);
        }
    }

    private static void requireSameValues(String path, List<String> actual, Set<String> expected, List<String> errors) {
        if (expected.isEmpty()) {
            if (actual == null || !actual.isEmpty()) {
                errors.add(path + " must be empty");
            }
            return;
        }
        requireNonEmpty(path, actual, errors);
        if (actual != null && !Set.copyOf(actual).equals(expected)) {
            errors.add(path + " must contain " + expected);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String accountPath(ExchangeProperties active) {
        return "exchange.providers.binance.environments.%s.accounts.%s"
                .formatted(active.environment(), active.account());
    }

    private static String marketPath(ExchangeProperties active) {
        return accountPath(active) + ".markets." + active.market();
    }

    private static Set<String> signatureAlgorithms(BinanceMarketType marketType) {
        return switch (marketType) {
            case SPOT, MARGIN_CROSS, MARGIN_ISOLATED -> ASYMMETRIC_SIGNATURE_ALGORITHMS;
            case FUTURES_USD_M, FUTURES_COIN_M -> RSA_SIGNATURE_ALGORITHMS;
            case OPTIONS -> HMAC_SIGNATURE_ALGORITHMS;
        };
    }

    private static <T extends Enum<T>> String enumNames(Class<T> type) {
        return Set.of(type.getEnumConstants()).stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
