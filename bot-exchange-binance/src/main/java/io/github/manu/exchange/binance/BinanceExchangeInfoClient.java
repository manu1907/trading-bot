package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

final class BinanceExchangeInfoClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final BinanceProperties.Rest rest;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final Clock clock;
    private final BinanceExchangeInfoParser parser;

    BinanceExchangeInfoClient(BinanceProperties.Rest rest) {
        this(rest, new BinanceJdkHttpTransport(
                timeout(rest.connectTimeoutMillis()),
                timeout(rest.responseTimeoutMillis())
        ), JsonMapperFactory.create(), Clock.systemUTC(), new BinanceExchangeInfoParser());
    }

    BinanceExchangeInfoClient(
            BinanceProperties.Rest rest,
            BinanceHttpTransport transport,
            ObjectMapper jsonMapper,
            Clock clock,
            BinanceExchangeInfoParser parser
    ) {
        this.rest = Objects.requireNonNull(rest, "rest is required");
        this.transport = Objects.requireNonNull(transport, "transport is required");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.parser = Objects.requireNonNull(parser, "parser is required");
    }

    BinanceExchangeMetadata fetch() throws IOException, InterruptedException {
        BinanceRestRequestFactory requestFactory = new BinanceRestRequestFactory(rest, clock, 0);
        URI uri = requestFactory.publicUri(rest.exchangeInfoPath(), List.of());
        BinanceHttpResponse response = transport.sendPublic(uri, "GET");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BinanceApiException(response.statusCode(), null, "exchangeInfo fetch failed");
        }

        JsonNode body = jsonMapper.readTree(response.body());
        return parser.parse(rest.baseUrl(), body, clock.instant());
    }

    private static Duration timeout(Integer millis) {
        if (millis == null || millis <= 0) {
            return DEFAULT_TIMEOUT;
        }
        return Duration.ofMillis(millis);
    }
}
