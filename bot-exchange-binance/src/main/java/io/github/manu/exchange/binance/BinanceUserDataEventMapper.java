package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

final class BinanceUserDataEventMapper {

    private final ObjectMapper jsonMapper;

    BinanceUserDataEventMapper() {
        this(JsonMapperFactory.create());
    }

    BinanceUserDataEventMapper(ObjectMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    List<TradingEventEnvelope<?>> map(String payload, Context context) {
        requireText(payload, "payload");
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        JsonNode root = jsonMapper.readTree(payload);
        JsonNode event = eventNode(root);
        if (!event.hasNonNull("e")) {
            throw new IllegalArgumentException("Binance user-data payload missing e");
        }
        return switch (event.required("e").asString()) {
            case "executionReport" -> List.of(spotExecutionReport(event, normalized));
            case "ORDER_TRADE_UPDATE" -> List.of(futuresOrderTradeUpdate(event, normalized));
            case "outboundAccountPosition" -> outboundAccountPosition(event, normalized);
            case "balanceUpdate" -> List.of(spotBalanceDelta(event, normalized, "balanceUpdate"));
            case "externalLockUpdate" -> List.of(spotBalanceDelta(event, normalized, "externalLockUpdate"));
            case "ACCOUNT_UPDATE" -> accountUpdate(event, normalized);
            case "BALANCE_POSITION_UPDATE" -> optionsBalancePositionUpdate(event, normalized);
            default -> List.of();
        };
    }

    private JsonNode eventNode(JsonNode root) {
        if (root.hasNonNull("event")) {
            return root.required("event");
        }
        if (root.hasNonNull("data")) {
            return root.required("data");
        }
        return root;
    }

    private TradingEventEnvelope<ExecutionReportEvent> spotExecutionReport(JsonNode event, Context context) {
        String symbol = requiredText(event, "s");
        String clientOrderId = requiredText(event, "c");
        String exchangeOrderId = requiredText(event, "i");
        Long tradeId = longValue(event, "t");
        ExecutionReportEvent value = ExecutionReportEvent.newBuilder()
                .setEventId(eventId(context, "executionReport", symbol, clientOrderId, exchangeOrderId, text(event, "I"), text(event, "E")))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(exchangeOrderId)
                .setExecutionId(text(event, "I"))
                .setTradeId(tradeId == null || tradeId < 0 ? null : String.valueOf(tradeId))
                .setSide(requiredText(event, "S"))
                .setOrderType(requiredText(event, "o"))
                .setOrderStatus(requiredText(event, "X"))
                .setExecutionType(requiredText(event, "x"))
                .setLastExecutedQuantity(requiredText(event, "l"))
                .setLastExecutedPrice(requiredText(event, "L"))
                .setCumulativeFilledQuantity(requiredText(event, "z"))
                .setCumulativeQuoteQuantity(text(event, "Z"))
                .setCommissionAsset(text(event, "N"))
                .setCommissionAmount(text(event, "n"))
                .setMaker(booleanValue(event, "m").orElse(null))
                .setEventTimeMicros(requiredInstant(event, "E"))
                .setTransactionTimeMicros(instant(event, "T"))
                .setAttributes(attributes(event, Map.ofEntries(
                        Map.entry("rawEventType", "e"),
                        Map.entry("timeInForce", "f"),
                        Map.entry("orderQuantity", "q"),
                        Map.entry("orderPrice", "p"),
                        Map.entry("stopPrice", "P"),
                        Map.entry("icebergQuantity", "F"),
                        Map.entry("orderListId", "g"),
                        Map.entry("originalClientOrderId", "C"),
                        Map.entry("rejectReason", "r"),
                        Map.entry("orderCreationTime", "O"),
                        Map.entry("workingTime", "W"),
                        Map.entry("preventedMatchId", "v"),
                        Map.entry("preventedQuantity", "A"),
                        Map.entry("lastPreventedQuantity", "B"),
                        Map.entry("bidQuantity", "b"),
                        Map.entry("askQuantity", "a"),
                        Map.entry("tradeGroupId", "u"),
                        Map.entry("counterOrderId", "U"),
                        Map.entry("counterSymbol", "Cs"),
                        Map.entry("preventedExecutionQuantity", "pl"),
                        Map.entry("preventedExecutionPrice", "pL"),
                        Map.entry("preventedExecutionQuoteQuantity", "pY"),
                        Map.entry("selfTradePreventionMode", "V"),
                        Map.entry("expiryReason", "eR")
                )))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.EXECUTION_REPORT,
                TradingEventKeys.order(
                        TradingEventType.EXECUTION_REPORT,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol,
                        clientOrderId
                ),
                value
        );
    }

    private TradingEventEnvelope<ExecutionReportEvent> futuresOrderTradeUpdate(JsonNode event, Context context) {
        JsonNode order = event.required("o");
        String symbol = requiredText(order, "s");
        String clientOrderId = requiredText(order, "c");
        String exchangeOrderId = requiredText(order, "i");
        Long tradeId = longValue(order, "t");
        ExecutionReportEvent value = ExecutionReportEvent.newBuilder()
                .setEventId(eventId(context, "ORDER_TRADE_UPDATE", symbol, clientOrderId, exchangeOrderId, text(order, "t"), text(event, "E")))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(exchangeOrderId)
                .setExecutionId(null)
                .setTradeId(tradeId == null || tradeId <= 0 ? null : String.valueOf(tradeId))
                .setSide(requiredText(order, "S"))
                .setOrderType(requiredText(order, "o"))
                .setOrderStatus(requiredText(order, "X"))
                .setExecutionType(requiredText(order, "x"))
                .setLastExecutedQuantity(requiredText(order, "l"))
                .setLastExecutedPrice(requiredText(order, "L"))
                .setCumulativeFilledQuantity(requiredText(order, "z"))
                .setCumulativeQuoteQuantity(null)
                .setCommissionAsset(text(order, "N"))
                .setCommissionAmount(text(order, "n"))
                .setMaker(booleanValue(order, "m").orElse(null))
                .setEventTimeMicros(requiredInstant(event, "E"))
                .setTransactionTimeMicros(firstInstant(order, event, "T"))
                .setAttributes(attributes(order, Map.ofEntries(
                        Map.entry("rawEventType", "e"),
                        Map.entry("timeInForce", "f"),
                        Map.entry("orderQuantity", "q"),
                        Map.entry("orderPrice", "p"),
                        Map.entry("averagePrice", "ap"),
                        Map.entry("stopPrice", "sp"),
                        Map.entry("realizedProfit", "rp"),
                        Map.entry("bidQuantity", "b"),
                        Map.entry("askQuantity", "a"),
                        Map.entry("positionSide", "ps"),
                        Map.entry("reduceOnly", "R"),
                        Map.entry("workingType", "wt"),
                        Map.entry("originalOrderType", "ot"),
                        Map.entry("closeAll", "cp"),
                        Map.entry("activationPrice", "AP"),
                        Map.entry("callbackRate", "cr"),
                        Map.entry("priceProtect", "pP"),
                        Map.entry("selfTradePreventionMode", "V"),
                        Map.entry("priceMatch", "pm"),
                        Map.entry("goodTillDate", "gtd"),
                        Map.entry("expiryReason", "er"),
                        Map.entry("marginAsset", "ma")
                ), event))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.EXECUTION_REPORT,
                TradingEventKeys.order(
                        TradingEventType.EXECUTION_REPORT,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol,
                        clientOrderId
                ),
                value
        );
    }

    private List<TradingEventEnvelope<?>> outboundAccountPosition(JsonNode event, Context context) {
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        int index = 0;
        for (JsonNode balance : array(event, "B")) {
            String asset = requiredText(balance, "a");
            BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                    .setEventId(eventId(context, "outboundAccountPosition", asset, String.valueOf(index), text(event, "E")))
                    .setSchemaVersion(1)
                    .setProvider(context.provider())
                    .setEnvironment(context.environment())
                    .setAccount(context.account())
                    .setMarket(context.market())
                    .setAsset(asset)
                    .setWalletBalance(null)
                    .setCrossWalletBalance(null)
                    .setAvailableBalance(text(balance, "f"))
                    .setBalanceDelta(null)
                    .setUpdateReason("outboundAccountPosition")
                    .setEventTimeMicros(requiredInstant(event, "E"))
                    .setAttributes(attributes(balance, Map.of(
                            "lockedBalance", "l",
                            "lastAccountUpdateTime", "u"
                    ), event))
                    .build();
            envelopes.add(balanceEnvelope(context, value));
            index++;
        }
        return envelopes;
    }

    private TradingEventEnvelope<BalanceUpdateEvent> spotBalanceDelta(JsonNode event, Context context, String reason) {
        String asset = requiredText(event, "a");
        BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                .setEventId(eventId(context, reason, asset, text(event, "T"), text(event, "E")))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setAsset(asset)
                .setWalletBalance(null)
                .setCrossWalletBalance(null)
                .setAvailableBalance(null)
                .setBalanceDelta(requiredText(event, "d"))
                .setUpdateReason(reason)
                .setEventTimeMicros(requiredInstant(event, "E"))
                .setAttributes(attributes(event, Map.of(
                        "transactionTime", "T"
                )))
                .build();
        return balanceEnvelope(context, value);
    }

    private List<TradingEventEnvelope<?>> accountUpdate(JsonNode event, Context context) {
        if (!event.hasNonNull("a")) {
            if (!"options".equals(context.market())) {
                throw new IllegalArgumentException("Binance user-data ACCOUNT_UPDATE payload missing a");
            }
            return List.of(optionsAccountUpdate(event, context));
        }
        JsonNode accountUpdate = event.required("a");
        String reason = text(accountUpdate, "m");
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        int balanceIndex = 0;
        for (JsonNode balance : array(accountUpdate, "B")) {
            String asset = requiredText(balance, "a");
            BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                    .setEventId(eventId(context, "ACCOUNT_UPDATE_BALANCE", asset, String.valueOf(balanceIndex), text(event, "E")))
                    .setSchemaVersion(1)
                    .setProvider(context.provider())
                    .setEnvironment(context.environment())
                    .setAccount(context.account())
                    .setMarket(context.market())
                    .setAsset(asset)
                    .setWalletBalance(text(balance, "wb"))
                    .setCrossWalletBalance(text(balance, "cw"))
                    .setAvailableBalance(null)
                    .setBalanceDelta(text(balance, "bc"))
                    .setUpdateReason(reason)
                    .setEventTimeMicros(requiredInstant(event, "E"))
                    .setAttributes(attributes(balance, Map.of(
                            "rawEventType", "e",
                            "transactionTime", "T",
                            "accountAlias", "i"
                    ), event))
                    .build();
            envelopes.add(balanceEnvelope(context, value));
            balanceIndex++;
        }
        int positionIndex = 0;
        for (JsonNode position : array(accountUpdate, "P")) {
            String symbol = requiredText(position, "s");
            String positionSide = requiredText(position, "ps");
            PositionUpdateEvent value = PositionUpdateEvent.newBuilder()
                    .setEventId(eventId(context, "ACCOUNT_UPDATE_POSITION", symbol, positionSide, String.valueOf(positionIndex), text(event, "E")))
                    .setSchemaVersion(1)
                    .setProvider(context.provider())
                    .setEnvironment(context.environment())
                    .setAccount(context.account())
                    .setMarket(context.market())
                    .setSymbol(symbol)
                    .setPositionSide(positionSide)
                    .setPositionAmount(requiredText(position, "pa"))
                    .setEntryPrice(text(position, "ep"))
                    .setMarkPrice(null)
                    .setLiquidationPrice(null)
                    .setUnrealizedPnl(text(position, "up"))
                    .setLeverage(null)
                    .setMarginType(text(position, "mt"))
                    .setIsolatedMargin(text(position, "iw"))
                    .setEventTimeMicros(requiredInstant(event, "E"))
                    .setAttributes(attributes(position, Map.of(
                            "rawEventType", "e",
                            "updateReason", "m",
                            "transactionTime", "T",
                            "accountAlias", "i",
                            "breakEvenPrice", "bep",
                            "accumulatedRealized", "cr"
                    ), event, accountUpdate))
                    .build();
            envelopes.add(TradingEventEnvelope.of(
                    TradingEventType.POSITION_UPDATE,
                    TradingEventKeys.symbol(
                            TradingEventType.POSITION_UPDATE,
                            context.provider(),
                            context.environment(),
                            context.account(),
                            context.market(),
                            symbol
                    ),
                    value
            ));
            positionIndex++;
        }
        return envelopes;
    }

    private TradingEventEnvelope<BalanceUpdateEvent> optionsAccountUpdate(JsonNode event, Context context) {
        BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                .setEventId(eventId(context, "OPTIONS_ACCOUNT_UPDATE", "USDT", text(event, "E")))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setAsset("USDT")
                .setWalletBalance(text(event, "b"))
                .setCrossWalletBalance(null)
                .setAvailableBalance(null)
                .setBalanceDelta(null)
                .setUpdateReason("ACCOUNT_UPDATE")
                .setEventTimeMicros(requiredInstant(event, "E"))
                .setAttributes(attributes(event, Map.of(
                        "rawEventType", "e",
                        "transactionTime", "T",
                        "equity", "eq",
                        "adjustedEquity", "aeq",
                        "positionValue", "m",
                        "unrealizedPnl", "u",
                        "initialMargin", "i",
                        "maintenanceMargin", "M"
                )))
                .build();
        return balanceEnvelope(context, value);
    }

    private List<TradingEventEnvelope<?>> optionsBalancePositionUpdate(JsonNode event, Context context) {
        String reason = text(event, "m");
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        int balanceIndex = 0;
        for (JsonNode balance : array(event, "B")) {
            String asset = requiredText(balance, "a");
            BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                    .setEventId(eventId(context, "BALANCE_POSITION_UPDATE_BALANCE", asset, String.valueOf(balanceIndex), text(event, "E")))
                    .setSchemaVersion(1)
                    .setProvider(context.provider())
                    .setEnvironment(context.environment())
                    .setAccount(context.account())
                    .setMarket(context.market())
                    .setAsset(asset)
                    .setWalletBalance(text(balance, "b"))
                    .setCrossWalletBalance(null)
                    .setAvailableBalance(null)
                    .setBalanceDelta(text(balance, "bc"))
                    .setUpdateReason(reason)
                    .setEventTimeMicros(requiredInstant(event, "E"))
                    .setAttributes(attributes(balance, Map.of(
                            "rawEventType", "e",
                            "transactionTime", "T"
                    ), event))
                    .build();
            envelopes.add(balanceEnvelope(context, value));
            balanceIndex++;
        }
        int positionIndex = 0;
        for (JsonNode position : array(event, "P")) {
            String symbol = requiredText(position, "s");
            String positionAmount = requiredText(position, "c");
            PositionUpdateEvent value = PositionUpdateEvent.newBuilder()
                    .setEventId(eventId(context, "BALANCE_POSITION_UPDATE_POSITION", symbol, String.valueOf(positionIndex), text(event, "E")))
                    .setSchemaVersion(1)
                    .setProvider(context.provider())
                    .setEnvironment(context.environment())
                    .setAccount(context.account())
                    .setMarket(context.market())
                    .setSymbol(symbol)
                    .setPositionSide(positionSide(positionAmount))
                    .setPositionAmount(positionAmount)
                    .setEntryPrice(text(position, "a"))
                    .setMarkPrice(null)
                    .setLiquidationPrice(null)
                    .setUnrealizedPnl(null)
                    .setLeverage(null)
                    .setMarginType(null)
                    .setIsolatedMargin(null)
                    .setEventTimeMicros(requiredInstant(event, "E"))
                    .setAttributes(attributes(position, Map.of(
                            "rawEventType", "e",
                            "updateReason", "m",
                            "transactionTime", "T",
                            "positionValue", "p"
                    ), event))
                    .build();
            envelopes.add(positionEnvelope(context, symbol, value));
            positionIndex++;
        }
        return envelopes;
    }

    private TradingEventEnvelope<BalanceUpdateEvent> balanceEnvelope(Context context, BalanceUpdateEvent value) {
        return TradingEventEnvelope.of(
                TradingEventType.BALANCE_UPDATE,
                TradingEventKeys.account(
                        TradingEventType.BALANCE_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market()
                ),
                value
        );
    }

    private TradingEventEnvelope<PositionUpdateEvent> positionEnvelope(
            Context context,
            String symbol,
            PositionUpdateEvent value
    ) {
        return TradingEventEnvelope.of(
                TradingEventType.POSITION_UPDATE,
                TradingEventKeys.symbol(
                        TradingEventType.POSITION_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol
                ),
                value
        );
    }

    private Map<CharSequence, CharSequence> attributes(JsonNode node, Map<String, String> fields) {
        return attributes(node, fields, node);
    }

    private Map<CharSequence, CharSequence> attributes(JsonNode node, Map<String, String> fields, JsonNode... fallbacks) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
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

    private Iterable<JsonNode> array(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return List.of();
        }
        JsonNode child = node.required(field);
        if (!child.isArray()) {
            throw new IllegalArgumentException("Binance user-data field " + field + " must be an array");
        }
        return child;
    }

    private Instant requiredInstant(JsonNode node, String field) {
        return Instant.ofEpochMilli(requiredLong(node, field));
    }

    private Instant instant(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private Instant firstInstant(JsonNode first, JsonNode second, String field) {
        Instant value = instant(first, field);
        return value == null ? instant(second, field) : value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Binance user-data payload missing " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.required(field).asString();
        return value.isBlank() ? null : value;
    }

    private Long requiredLong(JsonNode node, String field) {
        Long value = longValue(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Binance user-data payload missing " + field);
        }
        return value;
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asLong() : null;
    }

    private Optional<Boolean> booleanValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(node.required(field).asBoolean()) : Optional.empty();
    }

    private String positionSide(String positionAmount) {
        int sign = new BigDecimal(positionAmount).signum();
        if (sign > 0) {
            return "LONG";
        }
        if (sign < 0) {
            return "SHORT";
        }
        return "FLAT";
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

    record Context(String provider, String environment, String account, String market) {

        private Context normalize() {
            return new Context(
                    require(provider, "provider"),
                    require(environment, "environment"),
                    require(account, "account"),
                    require(market, "market")
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
