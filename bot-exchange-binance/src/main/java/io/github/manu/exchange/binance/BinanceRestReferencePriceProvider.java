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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class BinanceRestReferencePriceProvider implements BinanceReferencePriceProvider {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final BinanceProperties binance;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

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
        String symbolName = requireText(symbol, "symbol");
        String cacheKey = symbolName.toUpperCase(Locale.ROOT);
        Duration cacheTtl = duration(binance.trading().referencePriceCacheTtlMillis());
        Duration maxAge = duration(binance.trading().referencePriceMaxAgeMillis());
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && isCacheFresh(cached, cacheTtl) && isSourceFresh(cached, maxAge)) {
            return Optional.of(cached.price());
        }
        BinanceRestRequestFactory requestFactory = new BinanceRestRequestFactory(binance.rest(), clock, 0);
        URI uri = requestFactory.publicUri(path, List.of(BinanceRequestParameter.of("symbol", symbolName)));
        try {
            BinanceHttpResponse response = transport.sendPublic(uri, "GET");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BinanceApiException(response.statusCode(), null, "reference price fetch failed");
            }
            Optional<ReferencePrice> price = referencePrice(
                    jsonMapper.readTree(response.body()),
                    symbolName,
                    field,
                    binance.trading().referencePriceTimestampField()
            );
            if (price.isEmpty() || !isSourceFresh(price.get(), maxAge)) {
                cache.remove(cacheKey);
                return Optional.empty();
            }
            CacheEntry entry = new CacheEntry(price.get().price(), price.get().sourceTimestamp(), Instant.now(clock));
            if (!cacheTtl.isZero()) {
                cache.put(cacheKey, entry);
            }
            return Optional.of(entry.price());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fetch Binance reference price", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching Binance reference price", e);
        }
    }

    private Optional<ReferencePrice> referencePrice(
            JsonNode root,
            String symbol,
            String priceField,
            String timestampField
    ) {
        if (root.isArray()) {
            for (JsonNode item : root) {
                if (symbol.equalsIgnoreCase(text(item, "symbol")) && item.hasNonNull(priceField)) {
                    return Optional.of(referencePrice(item, priceField, timestampField));
                }
            }
            return Optional.empty();
        }
        if (!root.hasNonNull(priceField)) {
            return Optional.empty();
        }
        return Optional.of(referencePrice(root, priceField, timestampField));
    }

    private ReferencePrice referencePrice(JsonNode node, String priceField, String timestampField) {
        return new ReferencePrice(
                new BigDecimal(node.required(priceField).asString()),
                timestamp(node, timestampField)
        );
    }

    private Instant timestamp(JsonNode node, String field) {
        if (field == null || field.isBlank() || !node.hasNonNull(field)) {
            return null;
        }
        long epochMillis = Long.parseLong(node.required(field).asString());
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
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

    private Duration duration(Integer millis) {
        if (millis == null || millis <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(millis);
    }

    private boolean isCacheFresh(CacheEntry entry, Duration cacheTtl) {
        return !cacheTtl.isZero() && entry.fetchedAt().plus(cacheTtl).isAfter(Instant.now(clock));
    }

    private boolean isSourceFresh(CacheEntry entry, Duration maxAge) {
        return isSourceFresh(new ReferencePrice(entry.price(), entry.sourceTimestamp()), maxAge);
    }

    private boolean isSourceFresh(ReferencePrice price, Duration maxAge) {
        return maxAge.isZero()
                || price.sourceTimestamp() != null
                && !price.sourceTimestamp().plus(maxAge).isBefore(Instant.now(clock));
    }

    private record ReferencePrice(BigDecimal price, Instant sourceTimestamp) {
    }

    private record CacheEntry(BigDecimal price, Instant sourceTimestamp, Instant fetchedAt) {
    }
}
