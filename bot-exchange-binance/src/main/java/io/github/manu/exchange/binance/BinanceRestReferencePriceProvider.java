package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BinanceRestReferencePriceProvider implements BinanceReferencePriceProvider {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final BinanceProperties binance;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final Clock clock;

    BinanceRestReferencePriceProvider(BinanceProperties binance) {
        this(
                binance,
                new BinanceJdkHttpTransport(
                        timeout(binance.rest().connectTimeoutMillis()),
                        timeout(binance.rest().responseTimeoutMillis())
                ),
                JsonMapperFactory.create(),
                Clock.systemUTC()
        );
    }

    BinanceRestReferencePriceProvider(
            BinanceProperties binance,
            BinanceHttpTransport transport,
            ObjectMapper jsonMapper,
            Clock clock
    ) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<BigDecimal> weightedAveragePrice(String symbol) {
        String path = requireText(binance.trading().referencePricePath(), "reference_price_path");
        String field = requireText(binance.trading().referencePriceResponseField(), "reference_price_response_field");
        BinanceRestRequestFactory requestFactory = new BinanceRestRequestFactory(binance.rest(), clock, 0);
        URI uri = requestFactory.publicUri(path, List.of(BinanceRequestParameter.of("symbol", requireText(symbol, "symbol"))));
        try {
            BinanceHttpResponse response = transport.sendPublic(uri, "GET");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BinanceApiException(response.statusCode(), null, "reference price fetch failed");
            }
            return decimal(jsonMapper.readTree(response.body()), symbol, field);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch Binance reference price", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching Binance reference price", e);
        }
    }

    private Optional<BigDecimal> decimal(JsonNode root, String symbol, String field) {
        if (root.isArray()) {
            for (JsonNode item : root) {
                if (symbol.equalsIgnoreCase(text(item, "symbol")) && item.hasNonNull(field)) {
                    return Optional.of(new BigDecimal(item.required(field).asString()));
                }
            }
            return Optional.empty();
        }
        if (!root.hasNonNull(field)) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(root.required(field).asString()));
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asString() : null;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Duration timeout(Integer millis) {
        if (millis == null || millis <= 0) {
            return DEFAULT_TIMEOUT;
        }
        return Duration.ofMillis(millis);
    }
}
