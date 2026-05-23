package io.github.manu.messaging;

public final class TradingEventHeaders {

    public static final String EVENT_TYPE = "trading-event-type";
    public static final String VALUE_SCHEMA_FINGERPRINT = "trading-value-schema-fingerprint";
    public static final String DEAD_LETTER_REASON = "trading-dead-letter-reason";

    private TradingEventHeaders() {
    }
}
