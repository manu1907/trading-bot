package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class BinanceServerTimeSynchronizer {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final BinanceProperties.Rest rest;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final Clock clock;

    BinanceServerTimeSynchronizer(BinanceProperties.Rest rest) {
        this(rest, new BinanceJdkHttpTransport(
                timeout(rest.connectTimeoutMillis()),
                timeout(rest.responseTimeoutMillis())
        ), JsonMapperFactory.create(), Clock.systemUTC());
    }

    BinanceServerTimeSynchronizer(
            BinanceProperties.Rest rest,
            BinanceHttpTransport transport,
            ObjectMapper jsonMapper,
            Clock clock
    ) {
        this.rest = Objects.requireNonNull(rest, "rest is required");
        this.transport = Objects.requireNonNull(transport, "transport is required");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    BinanceServerTimeSnapshot sync() throws IOException, InterruptedException {
        BinanceRestRequestFactory requestFactory = new BinanceRestRequestFactory(rest, clock, 0);
        URI uri = requestFactory.publicUri(rest.serverTimePath(), List.of());

        Instant requestStartedAt = clock.instant();
        BinanceHttpResponse response = transport.sendPublic(uri, "GET");
        Instant responseReceivedAt = clock.instant();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BinanceApiException(response.statusCode(), null, "server time sync failed");
        }

        JsonNode body = jsonMapper.readTree(response.body());
        long serverTimeMillis = body.required("serverTime").asLong();
        long requestStartedMillis = requestStartedAt.toEpochMilli();
        long responseReceivedMillis = responseReceivedAt.toEpochMilli();
        long roundTripMillis = responseReceivedMillis - requestStartedMillis;
        long localMidpointMillis = requestStartedMillis + (roundTripMillis / 2L);

        return new BinanceServerTimeSnapshot(
                Instant.ofEpochMilli(serverTimeMillis),
                requestStartedAt,
                responseReceivedAt,
                serverTimeMillis - localMidpointMillis,
                roundTripMillis
        );
    }

    private static Duration timeout(Integer millis) {
        if (millis == null || millis <= 0) {
            return DEFAULT_TIMEOUT;
        }
        return Duration.ofMillis(millis);
    }
}
