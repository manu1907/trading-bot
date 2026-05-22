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
    private static final Set<String> SIGNATURE_ALGORITHMS = Set.of("HMAC_SHA256");
    private static final Set<String> TIMESTAMP_UNITS = Set.of("MILLISECONDS", "MICROSECONDS");
    private static final Set<String> ORDER_RESPONSE_TYPES = Set.of("ACK", "RESULT", "FULL");
    private static final Set<String> FUTURES_POSITION_MODES = Set.of("ONE_WAY", "HEDGE");
    private static final Set<String> USER_DATA_MODES = Set.of("listen_key");

    private BinanceConfigValidator() {
    }

    static void validate(ExchangeProperties active, BinanceProperties binance) {
        List<String> errors = new ArrayList<>();
        BinanceMarketType marketType = marketType(binance.marketType(), marketPath(active) + ".name", errors);

        validateCredentials(accountPath(active) + ".credentials", binance.credentials(), errors);
        validateRest(marketPath(active) + ".rest", binance.rest(), marketType, errors);
        validateWebsocket(marketPath(active) + ".websocket", binance.websocket(), errors);
        validateFutures(active, binance, marketType, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void validateCredentials(String path,
                                            BinanceProperties.Credentials credentials,
                                            List<String> errors) {
        if (credentials == null) {
            errors.add(path + " is required");
            return;
        }

        requireText(path + ".reference", credentials.reference(), errors);
        requireText(path + ".api_key", credentials.apiKey(), errors);
        requireText(path + ".api_secret", credentials.apiSecret(), errors);
        requireOneOf(path + ".key_type", credentials.keyType(), SIGNATURE_ALGORITHMS, errors);
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
        requireOneOf(path + ".signature_algorithm", rest.signatureAlgorithm(), SIGNATURE_ALGORITHMS, errors);
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
        if (marketType != BinanceMarketType.FUTURES_USD_M) {
            return;
        }

        String path = marketPath(active);
        validateUserData(path + ".user_data", binance.userDataStream(), marketType, errors);
        validateFuturesAccount(path + ".futures_account", binance.futuresAccount(), errors);
    }

    private static void validateUserData(String path,
                                         BinanceProperties.UserDataStream userData,
                                         BinanceMarketType marketType,
                                         List<String> errors) {
        if (userData == null) {
            errors.add(path + " is required for USD-M futures");
            return;
        }

        requireOneOf(path + ".mode", userData.mode(), USER_DATA_MODES, errors);
        requirePath(path + ".start_path", userData.startPath(), errors);
        requirePath(path + ".keepalive_path", userData.keepalivePath(), errors);
        requirePath(path + ".close_path", userData.closePath(), errors);
        requireMatching(path + ".start_path", userData.startPath(), marketType.listenKeyPath(), errors);
        requireMatching(path + ".keepalive_path", userData.keepalivePath(), marketType.listenKeyPath(), errors);
        requireMatching(path + ".close_path", userData.closePath(), marketType.listenKeyPath(), errors);
        requirePositive(path + ".listen_key_validity_minutes", userData.listenKeyValidityMinutes(), errors);
        requirePositive(path + ".keepalive_interval_minutes", userData.keepaliveIntervalMinutes(), errors);
        requirePositive(path + ".request_weight", userData.requestWeight(), errors);
    }

    private static void validateFuturesAccount(String path,
                                               BinanceProperties.FuturesAccount account,
                                               List<String> errors) {
        if (account == null) {
            errors.add(path + " is required for USD-M futures");
            return;
        }

        requireOneOf(path + ".position_mode", account.positionMode(), FUTURES_POSITION_MODES, errors);
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

    private static <T extends Enum<T>> String enumNames(Class<T> type) {
        return Set.of(type.getEnumConstants()).stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
