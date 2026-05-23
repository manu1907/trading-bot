package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

final class BinanceWebSocketEndpointPlanner {

    private static final String MICROSECONDS = "MICROSECONDS";

    private final BinanceProperties.Websocket websocket;
    private final Clock clock;

    BinanceWebSocketEndpointPlanner(BinanceProperties.Websocket websocket, Clock clock) {
        this.websocket = Objects.requireNonNull(websocket, "websocket");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    BinanceWebSocketConnectionPlan raw(BinanceWebSocketRoute route, List<String> streams) {
        return plan(BinanceWebSocketMode.RAW, route, streams);
    }

    BinanceWebSocketConnectionPlan combined(BinanceWebSocketRoute route, List<String> streams) {
        return plan(BinanceWebSocketMode.COMBINED, route, streams);
    }

    String streamName(String symbol, String channel) {
        requireText(symbol, "symbol");
        requireText(channel, "channel");
        return symbol.toLowerCase(Locale.ROOT) + "@" + channel;
    }

    private BinanceWebSocketConnectionPlan plan(BinanceWebSocketMode mode,
                                                BinanceWebSocketRoute route,
                                                List<String> streams) {
        List<String> checkedStreams = validateStreams(streams);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(connectionLifetime());
        return new BinanceWebSocketConnectionPlan(
                uri(mode, route, checkedStreams),
                mode,
                route,
                checkedStreams,
                createdAt,
                expiresAt,
                expiresAt.minus(reconnectBeforeExpiry()),
                serverPingInterval(),
                pongTimeout(),
                websocket.maxIncomingMessagesPerSecond()
        );
    }

    private URI uri(BinanceWebSocketMode mode, BinanceWebSocketRoute route, List<String> streams) {
        String streamPath = streams.stream()
                .map(this::encode)
                .collect(Collectors.joining("/"));
        String path = routePrefix(route);
        String query;
        if (mode == BinanceWebSocketMode.RAW) {
            path += normalizePath(websocket.rawStreamPath()) + "/" + streamPath;
            query = timeUnitQueryPrefix("?");
        } else {
            path += normalizePath(websocket.combinedStreamPath());
            query = "?streams=" + streamPath + timeUnitQueryPrefix("&");
        }
        return URI.create(normalizeBaseUrl(websocket.baseUrl()) + path + query);
    }

    private List<String> validateStreams(List<String> streams) {
        if (streams == null || streams.isEmpty()) {
            throw new IllegalArgumentException("At least one Binance websocket stream is required");
        }
        if (streams.size() > websocket.maxStreamsPerConnection()) {
            throw new IllegalArgumentException("Binance websocket stream count exceeds configured maximum: "
                    + websocket.maxStreamsPerConnection());
        }
        return streams.stream()
                .map(stream -> requireText(stream, "stream"))
                .toList();
    }

    private String routePrefix(BinanceWebSocketRoute route) {
        return switch (route) {
            case DEFAULT -> "";
            case PUBLIC -> requiredOptionalPath(websocket.publicPathPrefix(), "publicPathPrefix");
            case MARKET -> requiredOptionalPath(websocket.marketPathPrefix(), "marketPathPrefix");
            case PRIVATE -> requiredOptionalPath(websocket.privatePathPrefix(), "privatePathPrefix");
        };
    }

    private String timeUnitQueryPrefix(String separator) {
        if (MICROSECONDS.equals(websocket.timestampUnit())) {
            return separator + "timeUnit=MICROSECOND";
        }
        return "";
    }

    private Duration connectionLifetime() {
        return Duration.ofHours(requirePositive(websocket.maxConnectionLifetimeHours(), "maxConnectionLifetimeHours"));
    }

    private Duration reconnectBeforeExpiry() {
        return Duration.ofMinutes(requirePositive(websocket.reconnectBeforeExpiryMinutes(), "reconnectBeforeExpiryMinutes"));
    }

    private Duration serverPingInterval() {
        if (websocket.serverPingIntervalSeconds() != null) {
            return Duration.ofSeconds(requirePositive(websocket.serverPingIntervalSeconds(), "serverPingIntervalSeconds"));
        }
        return Duration.ofMinutes(requirePositive(websocket.serverPingIntervalMinutes(), "serverPingIntervalMinutes"));
    }

    private Duration pongTimeout() {
        if (websocket.pongTimeoutSeconds() != null) {
            return Duration.ofSeconds(requirePositive(websocket.pongTimeoutSeconds(), "pongTimeoutSeconds"));
        }
        return Duration.ofMinutes(requirePositive(websocket.pongTimeoutMinutes(), "pongTimeoutMinutes"));
    }

    private int requirePositive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private String requiredOptionalPath(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for routed Binance websocket connections");
        }
        return normalizePath(value);
    }

    private String normalizeBaseUrl(String value) {
        requireText(value, "baseUrl");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizePath(String value) {
        requireText(value, "path");
        return value.startsWith("/") ? value : "/" + value;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%40", "@")
                .replace("%2F", "/")
                .replace("%7E", "~");
    }
}
