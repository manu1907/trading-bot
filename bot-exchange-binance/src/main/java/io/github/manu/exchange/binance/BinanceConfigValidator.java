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
    private static final Set<String> MARKET_DATA_CONNECTION_MODES = Set.of("raw", "combined");
    private static final Set<String> MARKET_DATA_ROUTES = Set.of("default", "public", "market");
    private static final int OPTIONS_MMP_MAX_WINDOW_MILLIS = 5_000;
    private static final int OPTIONS_AUTO_CANCEL_MIN_COUNTDOWN_MILLIS = 5_000;

    private BinanceConfigValidator() {
    }

    static void validate(ExchangeProperties active, BinanceProperties binance) {
        List<String> errors = new ArrayList<>();
        BinanceMarketType marketType = marketType(binance.marketType(), marketPath(active) + ".name", errors);

        validateCredentials(accountPath(active) + ".credentials", binance.credentials(), marketType, errors);
        validateRest(marketPath(active) + ".rest", binance.rest(), marketType, errors);
        validateWebsocket(marketPath(active) + ".websocket", binance.websocket(), marketType, errors);
        validateTrading(marketPath(active) + ".trading", binance.trading(), marketType, errors);
        validateMarketData(marketPath(active) + ".market_data", binance.marketData(), binance.websocket(), errors);
        validateReconciliation(marketPath(active) + ".reconciliation", binance.reconciliation(), marketType, errors);
        validateMarginAccount(marketPath(active) + ".margin_account", binance.marginAccount(), marketType, errors);
        validateOptionsAccount(marketPath(active) + ".options_account", binance.optionsAccount(), marketType, errors);
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

    private static void validateReconciliation(String path,
                                               BinanceProperties.Reconciliation reconciliation,
                                               BinanceMarketType marketType,
                                               List<String> errors) {
        if (reconciliation == null) {
            errors.add(path + " is required");
            return;
        }

        requirePositive(path + ".interval_seconds", reconciliation.intervalSeconds(), errors);
        requirePositive(path + ".dedupe_window_event_ids", reconciliation.dedupeWindowEventIds(), errors);
        requireNotNull(path + ".projection_comparison_enabled", reconciliation.projectionComparisonEnabled(), errors);
        requireNotNull(path + ".fail_on_projection_mismatch", reconciliation.failOnProjectionMismatch(), errors);
        requireNotNull(path + ".open_order_symbols", reconciliation.openOrderSymbols(), errors);
        requireNotNull(path + ".order_history_symbols", reconciliation.orderHistorySymbols(), errors);
        requireOptionalPositive(path + ".order_history_limit", reconciliation.orderHistoryLimit(), errors);
        requireNotNull(path + ".account_trade_symbols", reconciliation.accountTradeSymbols(), errors);
        requireOptionalPositive(path + ".account_trade_limit", reconciliation.accountTradeLimit(), errors);
        requireOptionalPositive(path + ".account_trade_order_history_limit", reconciliation.accountTradeOrderHistoryLimit(), errors);
        requireNotNull(path + ".isolated_margin_symbols", reconciliation.isolatedMarginSymbols(), errors);
        requireNotNull(path + ".options_position_symbols", reconciliation.optionsPositionSymbols(), errors);
        if (!Boolean.TRUE.equals(reconciliation.runtimeEnabled())) {
            return;
        }

        if (!Boolean.TRUE.equals(reconciliation.openOrdersEnabled())
                && !Boolean.TRUE.equals(reconciliation.orderHistoryEnabled())
                && !Boolean.TRUE.equals(reconciliation.accountTradesEnabled())
                && !Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())
                && !Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())
                && !Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled())
                && !Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())
                && !Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled())
                && !Boolean.TRUE.equals(reconciliation.optionsAccountEnabled())
                && !Boolean.TRUE.equals(reconciliation.optionsPositionsEnabled())) {
            errors.add(path + " must enable at least one snapshot source when runtime_enabled is true");
        }
        if (Boolean.TRUE.equals(reconciliation.orderHistoryEnabled())
                && reconciliation.orderHistorySymbols().isEmpty()) {
            errors.add(path + ".order_history_symbols must not be empty when order_history_enabled is true");
        }
        if (Boolean.TRUE.equals(reconciliation.orderHistoryEnabled())) {
            requirePositive(path + ".order_history_limit", reconciliation.orderHistoryLimit(), errors);
        }
        if (Boolean.TRUE.equals(reconciliation.accountTradesEnabled())
                && reconciliation.accountTradeSymbols().isEmpty()) {
            errors.add(path + ".account_trade_symbols must not be empty when account_trades_enabled is true");
        }
        if (Boolean.TRUE.equals(reconciliation.accountTradesEnabled())) {
            requirePositive(path + ".account_trade_limit", reconciliation.accountTradeLimit(), errors);
            requirePositive(
                    path + ".account_trade_order_history_limit",
                    reconciliation.accountTradeOrderHistoryLimit(),
                    errors
            );
        }
        if (!marketType.futures()
                && (Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled()))) {
            errors.add(path + " futures snapshot sources require a Binance futures market");
        }
        if (marketType != BinanceMarketType.MARGIN_CROSS
                && marketType != BinanceMarketType.MARGIN_ISOLATED
                && (Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled()))) {
            errors.add(path + " margin snapshot sources require a Binance margin market");
        }
        if (marketType != BinanceMarketType.OPTIONS
                && (Boolean.TRUE.equals(reconciliation.optionsAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.optionsPositionsEnabled()))) {
            errors.add(path + " options snapshot sources require a Binance options market");
        }
    }

    private static void validateWebsocket(String path,
                                          BinanceProperties.Websocket websocket,
                                          BinanceMarketType marketType,
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
        if (marketType == BinanceMarketType.SPOT) {
            requireUri(path + ".api_base_url", websocket.apiBaseUrl(), Set.of("ws", "wss"), errors);
            requirePath(path + ".api_path", websocket.apiPath(), errors);
        } else {
            requireOptionalUri(path + ".api_base_url", websocket.apiBaseUrl(), Set.of("ws", "wss"), errors);
            requireOptionalPath(path + ".api_path", websocket.apiPath(), errors);
        }
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

    private static void validateMarginAccount(String path,
                                              BinanceProperties.MarginAccount account,
                                              BinanceMarketType marketType,
                                              List<String> errors) {
        if (marketType != BinanceMarketType.MARGIN_CROSS && marketType != BinanceMarketType.MARGIN_ISOLATED) {
            return;
        }
        if (account == null) {
            errors.add(path + " is required");
            return;
        }

        requirePath(path + ".borrow_repay_path", account.borrowRepayPath(), errors);
        requireMatching(path + ".borrow_repay_path", account.borrowRepayPath(), "/sapi/v1/margin/borrow-repay", errors);
        requirePath(path + ".transfer_history_path", account.transferHistoryPath(), errors);
        requireMatching(path + ".transfer_history_path", account.transferHistoryPath(), "/sapi/v1/margin/transfer", errors);
        requirePath(path + ".max_transferable_path", account.maxTransferablePath(), errors);
        requireMatching(path + ".max_transferable_path", account.maxTransferablePath(), "/sapi/v1/margin/maxTransferable", errors);
        requirePath(path + ".cross_account_path", account.crossAccountPath(), errors);
        requireMatching(path + ".cross_account_path", account.crossAccountPath(), "/sapi/v1/margin/account", errors);
        requirePath(path + ".isolated_account_path", account.isolatedAccountPath(), errors);
        requireMatching(path + ".isolated_account_path", account.isolatedAccountPath(), "/sapi/v1/margin/isolated/account", errors);
        requirePath(path + ".isolated_account_limit_path", account.isolatedAccountLimitPath(), errors);
        requireMatching(path + ".isolated_account_limit_path", account.isolatedAccountLimitPath(), "/sapi/v1/margin/isolated/accountLimit", errors);
        requirePath(path + ".trade_coeff_path", account.tradeCoeffPath(), errors);
        requireMatching(path + ".trade_coeff_path", account.tradeCoeffPath(), "/sapi/v1/margin/tradeCoeff", errors);
        requirePath(path + ".special_key_list_path", account.specialKeyListPath(), errors);
        requireMatching(path + ".special_key_list_path", account.specialKeyListPath(), "/sapi/v1/margin/api-key-list", errors);
        requirePath(path + ".special_key_path", account.specialKeyPath(), errors);
        requireMatching(path + ".special_key_path", account.specialKeyPath(), "/sapi/v1/margin/apiKey", errors);
        requirePath(path + ".special_key_ip_path", account.specialKeyIpPath(), errors);
        requireMatching(path + ".special_key_ip_path", account.specialKeyIpPath(), "/sapi/v1/margin/apiKey/ip", errors);
        requirePath(path + ".special_key_exit_mode_path", account.specialKeyExitModePath(), errors);
        requireMatching(path + ".special_key_exit_mode_path", account.specialKeyExitModePath(), "/sapi/v1/margin/exit-special-key-mode", errors);
        requireSameValues(path + ".supported_borrow_repay_types", account.supportedBorrowRepayTypes(), Set.of("BORROW", "REPAY"), errors);
        requireSameValues(path + ".supported_transfer_history_types", account.supportedTransferHistoryTypes(), Set.of("ROLL_IN", "ROLL_OUT"), errors);
        requireSameValues(path + ".supported_special_key_permission_modes", account.supportedSpecialKeyPermissionModes(), Set.of("READ", "TRADE"), errors);
        requireAtLeast(path + ".max_transfer_history_days", account.maxTransferHistoryDays(), 1, errors);
        requireAtMost(path + ".max_transfer_history_days", account.maxTransferHistoryDays(), 30, errors);
        requireAtLeast(path + ".max_transfer_history_size", account.maxTransferHistorySize(), 1, errors);
        requireAtMost(path + ".max_transfer_history_size", account.maxTransferHistorySize(), 100, errors);
        requireAtLeast(path + ".max_isolated_account_symbols", account.maxIsolatedAccountSymbols(), 1, errors);
        requireAtMost(path + ".max_isolated_account_symbols", account.maxIsolatedAccountSymbols(), 5, errors);
        requireAtLeast(path + ".max_special_key_ips", account.maxSpecialKeyIps(), 1, errors);
        requireAtMost(path + ".max_special_key_ips", account.maxSpecialKeyIps(), 30, errors);
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
        requireConfiguredPath(path + ".order_list_oco_path", trading.orderListOcoPath(), expected.orderListOcoPath(), errors);
        requireConfiguredPath(path + ".order_list_oto_path", trading.orderListOtoPath(), expected.orderListOtoPath(), errors);
        requireConfiguredPath(path + ".order_list_otoco_path", trading.orderListOtocoPath(), expected.orderListOtocoPath(), errors);
        requireConfiguredPath(path + ".order_list_opo_path", trading.orderListOpoPath(), expected.orderListOpoPath(), errors);
        requireConfiguredPath(path + ".order_list_opoco_path", trading.orderListOpocoPath(), expected.orderListOpocoPath(), errors);
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
                path + ".cancel_all_open_orders_by_underlying_path",
                trading.cancelAllOpenOrdersByUnderlyingPath(),
                expected.cancelAllOpenOrdersByUnderlyingPath(),
                errors
        );
        requireConfiguredPath(
                path + ".countdown_cancel_all_path",
                trading.countdownCancelAllPath(),
                expected.countdownCancelAllPath(),
                errors
        );
        requireConfiguredPath(
                path + ".exercise_record_path",
                trading.exerciseRecordPath(),
                expected.exerciseRecordPath(),
                errors
        );
        requireOptionalPath(path + ".reference_price_path", trading.referencePricePath(), errors);
        requireOptionalPositive(path + ".reference_price_max_age_millis", trading.referencePriceMaxAgeMillis(), errors);
        requireOptionalPositive(path + ".reference_price_cache_ttl_millis", trading.referencePriceCacheTtlMillis(), errors);
        if (trading.enforcePercentPriceFilters()) {
            requirePath(path + ".reference_price_path", trading.referencePricePath(), errors);
            requireText(path + ".reference_price_response_field", trading.referencePriceResponseField(), errors);
            if (trading.referencePriceMaxAgeMillis() != null) {
                requireText(path + ".reference_price_timestamp_field", trading.referencePriceTimestampField(), errors);
            }
        }
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
        requireMatching(path + ".supports_post_only", trading.supportsPostOnly(), expected.supportsPostOnly(), errors);
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
        requireNotNull(path + ".runtime_enabled", userData.runtimeEnabled(), errors);
        if (WEBSOCKET_API.equals(userData.mode())) {
            if (Boolean.TRUE.equals(userData.runtimeEnabled())) {
                errors.add(path + ".runtime_enabled must be false for websocket_api until subscription runtime is implemented");
            }
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

    private static void validateMarketData(String path,
                                           BinanceProperties.MarketDataStream marketData,
                                           BinanceProperties.Websocket websocket,
                                           List<String> errors) {
        if (marketData == null) {
            errors.add(path + " is required");
            return;
        }

        requireNotNull(path + ".runtime_enabled", marketData.runtimeEnabled(), errors);
        requireOneOf(path + ".connection_mode", marketData.connectionMode(), MARKET_DATA_CONNECTION_MODES, errors);
        requireOneOf(path + ".route", marketData.route(), MARKET_DATA_ROUTES, errors);
        requireNotNull(path + ".streams", marketData.streams(), errors);
        if (marketData.streams() == null) {
            return;
        }
        boolean derivesStreams = Boolean.TRUE.equals(marketData.deriveStreamsFromExchangeMetadata());
        if (Boolean.TRUE.equals(marketData.runtimeEnabled()) && !derivesStreams) {
            requireNonEmpty(path + ".streams", marketData.streams(), errors);
        }
        if (derivesStreams) {
            requireNonEmpty(path + ".derived_stream_templates", marketData.derivedStreamTemplates(), errors);
            requireOptionalPositive(path + ".derived_max_symbols", marketData.derivedMaxSymbols(), errors);
        }
        if (websocket != null && websocket.maxStreamsPerConnection() != null
                && marketData.streams().size() > websocket.maxStreamsPerConnection()) {
            errors.add(path + ".streams exceeds websocket.max_streams_per_connection");
        }
        int index = 0;
        for (String stream : marketData.streams()) {
            requireText(path + ".streams[" + index + "]", stream, errors);
            index++;
        }
        index = 0;
        for (String template : marketData.derivedStreamTemplates()) {
            requireText(path + ".derived_stream_templates[" + index + "]", template, errors);
            if (template != null && !hasSymbolPlaceholder(template)) {
                errors.add(path + ".derived_stream_templates[" + index + "] must include a symbol placeholder");
            }
            index++;
        }
        index = 0;
        for (String quoteAsset : marketData.derivedAllowedQuoteAssets()) {
            requireText(path + ".derived_allowed_quote_assets[" + index + "]", quoteAsset, errors);
            index++;
        }
        index = 0;
        for (String contractType : marketData.derivedAllowedContractTypes()) {
            requireText(path + ".derived_allowed_contract_types[" + index + "]", contractType, errors);
            index++;
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

    private static void validateOptionsAccount(String path,
                                               BinanceProperties.OptionsAccount account,
                                               BinanceMarketType marketType,
                                               List<String> errors) {
        if (marketType != BinanceMarketType.OPTIONS) {
            return;
        }
        if (account == null) {
            errors.add(path + " is required for Binance options markets");
            return;
        }

        requireMatching(path + ".margin_account_path", account.marginAccountPath(), "/eapi/v1/marginAccount", errors);
        requireMatching(path + ".position_path", account.positionPath(), "/eapi/v1/position", errors);
        requireMatching(path + ".market_maker_protection_path", account.marketMakerProtectionPath(), "/eapi/v1/mmp", errors);
        requireMatching(
                path + ".market_maker_protection_set_path",
                account.marketMakerProtectionSetPath(),
                "/eapi/v1/mmpSet",
                errors
        );
        requireMatching(
                path + ".market_maker_protection_reset_path",
                account.marketMakerProtectionResetPath(),
                "/eapi/v1/mmpReset",
                errors
        );
        requireMatching(
                path + ".auto_cancel_all_open_orders_path",
                account.autoCancelAllOpenOrdersPath(),
                "/eapi/v1/countdownCancelAll",
                errors
        );
        requireMatching(
                path + ".auto_cancel_all_open_orders_heartbeat_path",
                account.autoCancelAllOpenOrdersHeartbeatPath(),
                "/eapi/v1/countdownCancelAllHeartBeat",
                errors
        );
        requireMatching(
                path + ".max_market_maker_protection_window_millis",
                account.maxMarketMakerProtectionWindowMillis(),
                OPTIONS_MMP_MAX_WINDOW_MILLIS,
                errors
        );
        requireMatching(
                path + ".min_auto_cancel_all_open_orders_countdown_millis",
                account.minAutoCancelAllOpenOrdersCountdownMillis(),
                OPTIONS_AUTO_CANCEL_MIN_COUNTDOWN_MILLIS,
                errors
        );
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

    private static void requireOptionalUri(String path, String value, Set<String> schemes, List<String> errors) {
        if (value == null) {
            return;
        }
        requireUri(path, value, schemes, errors);
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

    private static boolean hasSymbolPlaceholder(String value) {
        return value.contains("{symbol}") || value.contains("{symbol_lower}") || value.contains("{symbolLower}");
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

    private static void requireAtLeast(String path, Integer value, int min, List<String> errors) {
        if (value == null || value < min) {
            errors.add(path + " must be at least " + min);
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
