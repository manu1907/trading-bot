package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.MarketDataAskLevel;
import io.github.manu.events.v1.MarketDataBidLevel;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.events.v1.MarketDataEventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

final class BinanceMarketDataEventMapper {

    private final ObjectMapper jsonMapper;

    BinanceMarketDataEventMapper() {
        this(JsonMapperFactory.create());
    }

    BinanceMarketDataEventMapper(ObjectMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    List<TradingEventEnvelope<MarketDataEvent>> map(String payload, Context context) {
        requireText(payload, "payload");
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        JsonNode root = jsonMapper.readTree(payload);
        return mapNode(root, normalized, null);
    }

    private List<TradingEventEnvelope<MarketDataEvent>> mapNode(JsonNode node, Context context, String streamName) {
        if (node.isArray()) {
            List<TradingEventEnvelope<MarketDataEvent>> envelopes = new ArrayList<>();
            for (JsonNode item : node) {
                envelopes.addAll(mapNode(item, context, streamName));
            }
            return envelopes;
        }
        String nestedStreamName = text(node, "stream");
        if (node.hasNonNull("data")) {
            return mapNode(node.required("data"), context, nestedStreamName == null ? streamName : nestedStreamName);
        }
        String eventType = text(node, "e");
        if (eventType == null && isBookTicker(node)) {
            return List.of(bookTicker(node, context, streamName));
        }
        if (eventType == null && node.hasNonNull("lastUpdateId")) {
            return List.of(depthSnapshot(node, context, streamName));
        }
        if (eventType == null) {
            return List.of();
        }
        return switch (eventType) {
            case "trade" -> List.of(trade(node, context, streamName, false));
            case "aggTrade" -> List.of(trade(node, context, streamName, true));
            case "bookTicker" -> List.of(bookTicker(node, context, streamName));
            case "depthUpdate" -> List.of(depthDelta(node, context, streamName));
            case "markPriceUpdate" -> List.of(markPrice(node, context, streamName));
            case "kline" -> List.of(kline(node, context, streamName));
            default -> List.of();
        };
    }

    private TradingEventEnvelope<MarketDataEvent> trade(
            JsonNode event,
            Context context,
            String streamName,
            boolean aggregate
    ) {
        String symbol = symbol(event, streamName);
        String tradeId = requiredText(event, aggregate ? "a" : "t");
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.TRADE, symbol, event, streamName)
                .setExchangeSequence(longValue(event, aggregate ? "a" : "t"))
                .setTradeId(tradeId)
                .setSide(takerSide(event))
                .setPrice(requiredText(event, "p"))
                .setQuantity(requiredText(event, "q"))
                .setBids(List.of())
                .setAsks(List.of())
                .setAttributes(attributes(event, streamName, tradeAttributes(aggregate)))
                .build();
        return envelope(context, symbol, value);
    }

    private Map<String, String> tradeAttributes(boolean aggregate) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("rawEventType", "e");
        if (aggregate) {
            fields.put("aggregateTradeId", "a");
        }
        fields.put("firstTradeId", "f");
        fields.put("lastTradeId", "l");
        fields.put("tradeTime", "T");
        fields.put("buyerMarketMaker", "m");
        fields.put("normalQuantity", "nq");
        return fields;
    }

    private TradingEventEnvelope<MarketDataEvent> bookTicker(
            JsonNode event,
            Context context,
            String streamName
    ) {
        String symbol = symbol(event, streamName);
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.BOOK_TICKER, symbol, event, streamName)
                .setExchangeSequence(longValue(event, "u"))
                .setTradeId(null)
                .setSide(null)
                .setPrice(null)
                .setQuantity(null)
                .setBids(List.of(bid(requiredText(event, "b"), requiredText(event, "B"))))
                .setAsks(List.of(ask(requiredText(event, "a"), requiredText(event, "A"))))
                .setAttributes(attributes(event, streamName, Map.of(
                        "rawEventType", "e",
                        "transactionTime", "T"
                )))
                .build();
        return envelope(context, symbol, value);
    }

    private TradingEventEnvelope<MarketDataEvent> depthSnapshot(
            JsonNode event,
            Context context,
            String streamName
    ) {
        String symbol = symbol(event, streamName);
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.DEPTH_SNAPSHOT, symbol, event, streamName)
                .setExchangeSequence(requiredLong(event, "lastUpdateId"))
                .setTradeId(null)
                .setSide(null)
                .setPrice(null)
                .setQuantity(null)
                .setBids(bids(event, "bids"))
                .setAsks(asks(event, "asks"))
                .setAttributes(attributes(event, streamName, Map.of()))
                .build();
        return envelope(context, symbol, value);
    }

    private TradingEventEnvelope<MarketDataEvent> depthDelta(
            JsonNode event,
            Context context,
            String streamName
    ) {
        String symbol = symbol(event, streamName);
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.DEPTH_DELTA, symbol, event, streamName)
                .setExchangeSequence(requiredLong(event, "u"))
                .setTradeId(null)
                .setSide(null)
                .setPrice(null)
                .setQuantity(null)
                .setBids(bids(event, "b"))
                .setAsks(asks(event, "a"))
                .setAttributes(attributes(event, streamName, Map.of(
                        "rawEventType", "e",
                        "firstUpdateId", "U",
                        "previousFinalUpdateId", "pu",
                        "transactionTime", "T"
                )))
                .build();
        return envelope(context, symbol, value);
    }

    private TradingEventEnvelope<MarketDataEvent> markPrice(
            JsonNode event,
            Context context,
            String streamName
    ) {
        String symbol = symbol(event, streamName);
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.MARK_PRICE, symbol, event, streamName)
                .setExchangeSequence(null)
                .setTradeId(null)
                .setSide(null)
                .setPrice(requiredText(event, "p"))
                .setQuantity(null)
                .setBids(List.of())
                .setAsks(List.of())
                .setAttributes(attributes(event, streamName, Map.of(
                        "rawEventType", "e",
                        "movingAverageMarkPrice", "ap",
                        "indexPrice", "i",
                        "estimatedSettlePrice", "P",
                        "fundingRate", "r",
                        "nextFundingTime", "T"
                )))
                .build();
        return envelope(context, symbol, value);
    }

    private TradingEventEnvelope<MarketDataEvent> kline(
            JsonNode event,
            Context context,
            String streamName
    ) {
        JsonNode kline = event.required("k");
        String symbol = symbol(event, streamName);
        MarketDataEvent value = baseBuilder(context, MarketDataEventType.KLINE, symbol, event, streamName)
                .setExchangeSequence(longValue(kline, "L"))
                .setTradeId(null)
                .setSide(null)
                .setPrice(requiredText(kline, "c"))
                .setQuantity(requiredText(kline, "v"))
                .setBids(List.of())
                .setAsks(List.of())
                .setAttributes(attributes(kline, streamName, Map.ofEntries(
                        Map.entry("rawEventType", "e"),
                        Map.entry("interval", "i"),
                        Map.entry("startTime", "t"),
                        Map.entry("closeTime", "T"),
                        Map.entry("firstTradeId", "f"),
                        Map.entry("lastTradeId", "L"),
                        Map.entry("openPrice", "o"),
                        Map.entry("highPrice", "h"),
                        Map.entry("lowPrice", "l"),
                        Map.entry("numberOfTrades", "n"),
                        Map.entry("closed", "x"),
                        Map.entry("quoteVolume", "q"),
                        Map.entry("takerBuyBaseVolume", "V"),
                        Map.entry("takerBuyQuoteVolume", "Q")
                ), event))
                .build();
        return envelope(context, symbol, value);
    }

    private MarketDataEvent.Builder baseBuilder(
            Context context,
            MarketDataEventType eventType,
            String symbol,
            JsonNode event,
            String streamName
    ) {
        Instant occurredAt = occurredAt(event, context);
        return MarketDataEvent.newBuilder()
                .setEventId(eventId(context, eventType.name(), symbol, sequencePart(event), streamName))
                .setSchemaVersion(1)
                .setEventType(eventType)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setOccurredAtMicros(occurredAt)
                .setReceivedAtMicros(context.receivedAt());
    }

    private TradingEventEnvelope<MarketDataEvent> envelope(Context context, String symbol, MarketDataEvent value) {
        return TradingEventEnvelope.of(
                TradingEventType.MARKET_DATA,
                TradingEventKeys.symbol(
                        TradingEventType.MARKET_DATA,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol
                ),
                value
        );
    }

    private List<MarketDataBidLevel> bids(JsonNode event, String field) {
        List<MarketDataBidLevel> levels = new ArrayList<>();
        for (PriceLevel level : priceLevels(event, field)) {
            levels.add(bid(level.price(), level.quantity()));
        }
        return List.copyOf(levels);
    }

    private List<MarketDataAskLevel> asks(JsonNode event, String field) {
        List<MarketDataAskLevel> levels = new ArrayList<>();
        for (PriceLevel level : priceLevels(event, field)) {
            levels.add(ask(level.price(), level.quantity()));
        }
        return List.copyOf(levels);
    }

    private MarketDataBidLevel bid(String price, String quantity) {
        return MarketDataBidLevel.newBuilder()
                .setPrice(price)
                .setQuantity(quantity)
                .build();
    }

    private MarketDataAskLevel ask(String price, String quantity) {
        return MarketDataAskLevel.newBuilder()
                .setPrice(price)
                .setQuantity(quantity)
                .build();
    }

    private List<PriceLevel> priceLevels(JsonNode event, String field) {
        if (!event.hasNonNull(field)) {
            return List.of();
        }
        JsonNode levels = event.required(field);
        if (!levels.isArray()) {
            throw new IllegalArgumentException("Binance market-data field " + field + " must be an array");
        }
        List<PriceLevel> result = new ArrayList<>();
        for (JsonNode level : levels) {
            if (!level.isArray()) {
                throw new IllegalArgumentException("Binance market-data field " + field + " must contain arrays");
            }
            Iterator<JsonNode> values = level.iterator();
            if (!values.hasNext()) {
                throw new IllegalArgumentException("Binance market-data price level missing price");
            }
            String price = values.next().asString();
            if (!values.hasNext()) {
                throw new IllegalArgumentException("Binance market-data price level missing quantity");
            }
            result.add(new PriceLevel(price, values.next().asString()));
        }
        return List.copyOf(result);
    }

    private Map<CharSequence, CharSequence> attributes(
            JsonNode node,
            String streamName,
            Map<String, String> fields,
            JsonNode... fallbacks
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (streamName != null && !streamName.isBlank()) {
            attributes.put("stream", streamName);
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getValue() == null) {
                attributes.put(entry.getKey(), "true");
                continue;
            }
            String value = text(node, entry.getValue());
            if (value == null) {
                for (JsonNode fallback : fallbacks) {
                    value = text(fallback, entry.getValue());
                    if (value != null) {
                        break;
                    }
                }
            }
            if (value != null) {
                attributes.put(entry.getKey(), value);
            }
        }
        return attributes;
    }

    private boolean isBookTicker(JsonNode node) {
        return node.hasNonNull("s")
                && node.hasNonNull("u")
                && node.hasNonNull("b")
                && node.hasNonNull("B")
                && node.hasNonNull("a")
                && node.hasNonNull("A");
    }

    private String symbol(JsonNode event, String streamName) {
        String symbol = text(event, "s");
        if (symbol == null && event.hasNonNull("k")) {
            symbol = text(event.required("k"), "s");
        }
        if (symbol == null && streamName != null) {
            int separator = streamName.indexOf('@');
            symbol = separator > 0 ? streamName.substring(0, separator).toUpperCase() : null;
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Binance market-data payload missing symbol");
        }
        return symbol.trim().toUpperCase();
    }

    private Instant occurredAt(JsonNode event, Context context) {
        Instant eventTime = instant(event, "E");
        if (eventTime != null) {
            return eventTime;
        }
        eventTime = instant(event, "T");
        if (eventTime != null) {
            return eventTime;
        }
        if (context.receivedAt() != null) {
            return context.receivedAt();
        }
        throw new IllegalArgumentException("Binance market-data payload missing event time");
    }

    private Instant instant(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : epochInstant(value);
    }

    private Instant epochInstant(long value) {
        if (Math.abs(value) > 10_000_000_000_000L) {
            long seconds = Math.floorDiv(value, 1_000_000L);
            long micros = Math.floorMod(value, 1_000_000L);
            return Instant.ofEpochSecond(seconds, micros * 1_000L);
        }
        return Instant.ofEpochMilli(value);
    }

    private String takerSide(JsonNode event) {
        if (!event.hasNonNull("m")) {
            return null;
        }
        return event.required("m").asBoolean() ? "SELL" : "BUY";
    }

    private String sequencePart(JsonNode event) {
        if (event.hasNonNull("k")) {
            JsonNode kline = event.required("k");
            String value = text(kline, "L");
            if (value != null) {
                return value;
            }
            value = text(kline, "t");
            if (value != null) {
                return value;
            }
        }
        String value = text(event, "u");
        if (value != null) {
            return value;
        }
        value = text(event, "lastUpdateId");
        if (value != null) {
            return value;
        }
        value = text(event, "a");
        if (value != null) {
            return value;
        }
        value = text(event, "t");
        if (value != null) {
            return value;
        }
        value = text(event, "E");
        if (value != null) {
            return value;
        }
        return text(event, "T");
    }

    private String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.required(field).asString();
        return value.isBlank() ? null : value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Binance market-data payload missing " + field);
        }
        return value;
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asLong() : null;
    }

    private Long requiredLong(JsonNode node, String field) {
        Long value = longValue(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Binance market-data payload missing " + field);
        }
        return value;
    }

    private String eventId(Context context, String eventType, String... parts) {
        StringJoiner joiner = new StringJoiner(":");
        joiner.add(context.provider());
        joiner.add(context.environment());
        joiner.add(context.account());
        joiner.add(context.market());
        joiner.add(eventType);
        for (String part : parts) {
            joiner.add(part == null || part.isBlank() ? "-" : part.trim());
        }
        return joiner.toString();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private record PriceLevel(String price, String quantity) {
    }

    record Context(String provider, String environment, String account, String market, Instant receivedAt) {

        Context(String provider, String environment, String account, String market) {
            this(provider, environment, account, market, null);
        }

        Context withReceivedAt(Instant value) {
            return new Context(provider, environment, account, market, value);
        }

        private Context normalize() {
            return new Context(
                    require(provider, "provider"),
                    require(environment, "environment"),
                    require(account, "account"),
                    require(market, "market"),
                    receivedAt
            );
        }

        private static String require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }
}
