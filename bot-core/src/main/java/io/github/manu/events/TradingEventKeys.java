package io.github.manu.events;

import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.events.v1.TradingEventKeyEntityType;

import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

public final class TradingEventKeys {

    private TradingEventKeys() {
    }

    public static TradingEventKey runtime(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return key(eventType, provider, environment, account, market, null, TradingEventKeyEntityType.RUNTIME, null);
    }

    public static TradingEventKey account(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return key(eventType, provider, environment, account, market, null, TradingEventKeyEntityType.ACCOUNT, account);
    }

    public static TradingEventKey symbol(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        return key(eventType, provider, environment, account, market, symbol, TradingEventKeyEntityType.SYMBOL, symbol);
    }

    public static TradingEventKey order(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId
    ) {
        return key(eventType, provider, environment, account, market, symbol, TradingEventKeyEntityType.ORDER, clientOrderId);
    }

    public static TradingEventKey strategy(TradingEventType eventType, String strategyId) {
        return key(eventType, null, null, null, null, null, TradingEventKeyEntityType.STRATEGY, strategyId);
    }

    public static TradingEventKey config(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market,
            String path
    ) {
        return key(eventType, provider, environment, account, market, null, TradingEventKeyEntityType.CONFIG, path);
    }

    private static TradingEventKey key(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            TradingEventKeyEntityType entityType,
            String entityId
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(entityType, "entityType");
        return TradingEventKey.newBuilder()
                .setSchemaVersion(1)
                .setEventType(eventType.name())
                .setProvider(normalizeNullable(provider))
                .setEnvironment(normalizeNullable(environment))
                .setAccount(normalizeNullable(account))
                .setMarket(normalizeNullable(market))
                .setSymbol(normalizeNullable(symbol))
                .setEntityType(entityType)
                .setEntityId(normalizeNullable(entityId))
                .setPartitionKey(partitionKey(eventType, provider, environment, account, market, symbol, entityType, entityId))
                .build();
    }

    private static String partitionKey(
            TradingEventType eventType,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            TradingEventKeyEntityType entityType,
            String entityId
    ) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(eventType.name().toLowerCase(Locale.ROOT));
        joiner.add(entityType.name().toLowerCase(Locale.ROOT));
        add(joiner, provider);
        add(joiner, environment);
        add(joiner, account);
        add(joiner, market);
        add(joiner, symbol);
        add(joiner, entityId);
        return joiner.toString();
    }

    private static void add(StringJoiner joiner, String value) {
        if (value == null || value.isBlank()) {
            joiner.add("-");
        } else {
            joiner.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
