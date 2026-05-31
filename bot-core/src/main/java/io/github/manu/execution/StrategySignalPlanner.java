package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandPositionSide;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandTimeInForce;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.messaging.TradingEventHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class StrategySignalPlanner implements TradingEventHandler {

    private static final String FEATURE_ORDER_TYPE = "order_type";
    private static final String FEATURE_TIME_IN_FORCE = "time_in_force";
    private static final String FEATURE_POSITION_SIDE = "position_side";
    private static final String FEATURE_CLIENT_ORDER_ID = "client_order_id";
    private static final String FEATURE_CLOSE_POSITION = "close_position";

    private final ExecutionProperties properties;
    private final TradingEventBus eventBus;
    private final Clock clock;

    public StrategySignalPlanner(ExecutionProperties properties, TradingEventBus eventBus) {
        this(properties, eventBus, Clock.systemUTC());
    }

    StrategySignalPlanner(ExecutionProperties properties, TradingEventBus eventBus, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.STRATEGY_SIGNAL) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected STRATEGY_SIGNAL envelope"));
        }
        return handleSignal((StrategySignalEvent) envelope.value());
    }

    public CompletableFuture<Void> handleSignal(StrategySignalEvent signal) {
        Objects.requireNonNull(signal, "signal");
        return plan(signal)
                .map(order -> eventBus.publish(envelope(order)).thenRun(() -> { }))
                .orElseGet(() -> CompletableFuture.<Void>completedFuture(null));
    }

    Optional<OrderCommandEvent> plan(StrategySignalEvent signal) {
        StrategySignalType signalType = Objects.requireNonNull(signal.getSignalType(), "signalType");
        if (signalType == StrategySignalType.HOLD || signalType == StrategySignalType.CANCEL) {
            return Optional.empty();
        }

        Map<CharSequence, CharSequence> features = signal.getFeatures() == null ? Map.of() : signal.getFeatures();
        String signalId = requireText(value(signal.getSignalId()), "signalId");
        String provider = resolve(signal.getProvider(), properties.signalPlanner().defaults().provider(), "provider");
        String environment = resolve(signal.getEnvironment(), properties.signalPlanner().defaults().environment(), "environment");
        String account = resolve(signal.getAccount(), properties.signalPlanner().defaults().account(), "account");
        String market = resolve(signal.getMarket(), properties.signalPlanner().defaults().market(), "market");
        String symbol = resolve(signal.getSymbol(), properties.signalPlanner().defaults().symbol(), "symbol");
        OrderCommandType orderType = orderType(signal, features);
        String quantity = value(signal.getTargetQuantity());
        String quoteOrderQuantity = quantity == null ? value(signal.getTargetNotional()) : null;

        OrderCommandEvent command = OrderCommandEvent.newBuilder()
                .setEventId("order-command:" + signalId)
                .setSchemaVersion(1)
                .setCommandId("cmd:" + signalId)
                .setStrategyId(requireText(value(signal.getStrategyId()), "strategyId"))
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setSide(side(signalType))
                .setOrderType(orderType)
                .setPositionSide(positionSide(features).orElse(null))
                .setTimeInForce(timeInForce(orderType, features).orElse(null))
                .setQuantity(quantity)
                .setQuoteOrderQuantity(quoteOrderQuantity)
                .setPrice(value(signal.getLimitPrice()))
                .setStopPrice(value(signal.getStopPrice()))
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(reduceOnly(signalType))
                .setClosePosition(booleanFeature(features, FEATURE_CLOSE_POSITION))
                .setClientOrderId(clientOrderId(signalId, features))
                .setIdempotencyKey("signal:" + signalId)
                .setRequestedAtMicros(Instant.now(clock))
                .setAttributes(attributes(signal, features))
                .build();
        return Optional.of(command);
    }

    private TradingEventEnvelope<OrderCommandEvent> envelope(OrderCommandEvent command) {
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(
                        TradingEventType.ORDER_COMMAND,
                        command.getProvider().toString(),
                        command.getEnvironment().toString(),
                        command.getAccount().toString(),
                        command.getMarket().toString(),
                        command.getSymbol().toString(),
                        command.getClientOrderId().toString()
                ),
                command
        );
    }

    private OrderCommandType orderType(StrategySignalEvent signal, Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_ORDER_TYPE);
        if (configured != null) {
            return OrderCommandType.valueOf(configured.toUpperCase(Locale.ROOT));
        }
        return value(signal.getLimitPrice()) == null ? OrderCommandType.MARKET : OrderCommandType.LIMIT;
    }

    private Optional<OrderCommandTimeInForce> timeInForce(
            OrderCommandType orderType,
            Map<CharSequence, CharSequence> features
    ) {
        String configured = feature(features, FEATURE_TIME_IN_FORCE);
        if (configured != null) {
            return Optional.of(OrderCommandTimeInForce.valueOf(configured.toUpperCase(Locale.ROOT)));
        }
        if (orderType == OrderCommandType.LIMIT) {
            return Optional.of(OrderCommandTimeInForce.valueOf(
                    properties.signalPlanner().defaults().limitOrderTimeInForce().toUpperCase(Locale.ROOT)
            ));
        }
        return Optional.empty();
    }

    private Optional<OrderCommandPositionSide> positionSide(Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_POSITION_SIDE);
        if (configured != null) {
            return Optional.of(OrderCommandPositionSide.valueOf(configured.toUpperCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    private OrderCommandSide side(StrategySignalType signalType) {
        return switch (signalType) {
            case ENTER_LONG, EXIT_SHORT, REDUCE_SHORT -> OrderCommandSide.BUY;
            case ENTER_SHORT, EXIT_LONG, REDUCE_LONG -> OrderCommandSide.SELL;
            case HOLD, CANCEL -> throw new IllegalArgumentException(signalType + " does not map to a new order side");
        };
    }

    private boolean reduceOnly(StrategySignalType signalType) {
        return switch (signalType) {
            case EXIT_LONG, EXIT_SHORT, REDUCE_LONG, REDUCE_SHORT -> true;
            case ENTER_LONG, ENTER_SHORT, HOLD, CANCEL -> false;
        };
    }

    private String clientOrderId(String signalId, Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_CLIENT_ORDER_ID);
        if (configured != null) {
            return configured;
        }
        return properties.signalPlanner().defaults().clientOrderIdPrefix() + "-" + signalId;
    }

    private Map<CharSequence, CharSequence> attributes(
            StrategySignalEvent signal,
            Map<CharSequence, CharSequence> features
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (signal.getAttributes() != null) {
            attributes.putAll(signal.getAttributes());
        }
        attributes.putAll(features);
        attributes.put("signal_id", requireText(value(signal.getSignalId()), "signalId"));
        attributes.put("signal_type", signal.getSignalType().name());
        attributes.put("source", "strategy_signal_planner");
        if (signal.getConfidence() != null) {
            attributes.put("signal_confidence", signal.getConfidence().toString());
        }
        if (signal.getTargetNotional() != null) {
            attributes.put("signal_target_notional", signal.getTargetNotional().toString());
        }
        return Map.copyOf(attributes);
    }

    private boolean booleanFeature(Map<CharSequence, CharSequence> features, String key) {
        return Boolean.parseBoolean(Optional.ofNullable(feature(features, key)).orElse("false"));
    }

    private String feature(Map<CharSequence, CharSequence> features, String key) {
        CharSequence value = features.get(key);
        return value(value);
    }

    private String resolve(CharSequence signalValue, String defaultValue, String field) {
        String value = value(signalValue);
        if (value != null) {
            return value;
        }
        return requireText(defaultValue, field);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
