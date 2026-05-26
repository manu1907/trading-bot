package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.messaging.TradingEventHandler;
import org.apache.avro.specific.SpecificRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class OrderExecutionPipeline implements TradingEventHandler {

    private static final String NO_GATEWAY_REASON = "execution:no_gateway";

    private final OrderRiskGate riskGate;
    private final TradingEventBus eventBus;
    private final List<OrderExecutionGateway> gateways;
    private final Clock clock;

    public OrderExecutionPipeline(
            OrderRiskGate riskGate,
            TradingEventBus eventBus,
            List<OrderExecutionGateway> gateways
    ) {
        this(riskGate, eventBus, gateways, Clock.systemUTC());
    }

    OrderExecutionPipeline(
            OrderRiskGate riskGate,
            TradingEventBus eventBus,
            List<OrderExecutionGateway> gateways,
            Clock clock
    ) {
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.gateways = List.copyOf(Objects.requireNonNull(gateways, "gateways"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.ORDER_COMMAND) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected ORDER_COMMAND envelope"));
        }
        return handleOrderCommand(cast(envelope.value(), OrderCommandEvent.class));
    }

    public CompletableFuture<Void> handleOrderCommand(OrderCommandEvent command) {
        Objects.requireNonNull(command, "command");
        RiskDecisionEvent riskDecision = riskGate.evaluate(command);
        Optional<OrderExecutionGateway> gateway = Optional.empty();
        if (riskDecision.getDecision() == RiskDecision.APPROVED) {
            gateway = gatewayFor(command);
            if (gateway.isEmpty()) {
                riskDecision = noGatewayDecision(command);
            }
        }

        CompletableFuture<Void> publishedDecision = publish(riskDecision).thenApply(ignored -> null);
        if (riskDecision.getDecision() != RiskDecision.APPROVED) {
            return publishedDecision;
        }

        OrderExecutionGateway selectedGateway = gateway.orElseThrow();
        return publishedDecision
                .thenCompose(ignored -> selectedGateway.submit(command))
                .thenCompose(eventBus::publish)
                .thenApply(ignored -> null);
    }

    private Optional<OrderExecutionGateway> gatewayFor(OrderCommandEvent command) {
        return gateways.stream()
                .filter(gateway -> gateway.supports(
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket())
                ))
                .findFirst();
    }

    private CompletableFuture<?> publish(SpecificRecord value) {
        TradingEventType eventType = TradingEventType.fromRecordClass(value.getClass())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported event class: " + value.getClass()));
        return eventBus.publish(envelope(eventType, value));
    }

    @SuppressWarnings("unchecked")
    private <T extends SpecificRecord> TradingEventEnvelope<T> envelope(TradingEventType eventType, SpecificRecord value) {
        if (value instanceof RiskDecisionEvent event) {
            return (TradingEventEnvelope<T>) TradingEventEnvelope.of(
                    eventType,
                    TradingEventKeys.symbol(
                            eventType,
                            value(event.getProvider()),
                            value(event.getEnvironment()),
                            value(event.getAccount()),
                            value(event.getMarket()),
                            value(event.getSymbol())
                    ),
                    event
            );
        }
        if (value instanceof OrderResultEvent event) {
            return (TradingEventEnvelope<T>) TradingEventEnvelope.of(
                    eventType,
                    TradingEventKeys.order(
                            eventType,
                            value(event.getProvider()),
                            value(event.getEnvironment()),
                            value(event.getAccount()),
                            value(event.getMarket()),
                            value(event.getSymbol()),
                            value(event.getClientOrderId())
                    ),
                    event
            );
        }
        throw new IllegalArgumentException("Unsupported event class: " + value.getClass());
    }

    private RiskDecisionEvent noGatewayDecision(OrderCommandEvent command) {
        List<String> reasons = new ArrayList<>();
        reasons.add(NO_GATEWAY_REASON);
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("execution_provider", value(command.getProvider()));
        attributes.put("execution_environment", value(command.getEnvironment()));
        attributes.put("execution_account", value(command.getAccount()));
        attributes.put("execution_market", value(command.getMarket()));
        return RiskDecisionEvent.newBuilder()
                .setEventId("risk-decision:" + value(command.getCommandId()))
                .setSchemaVersion(1)
                .setDecisionId("risk-decision:" + value(command.getCommandId()))
                .setCommandId(value(command.getCommandId()))
                .setSignalId(signalId(command))
                .setStrategyId(value(command.getStrategyId()))
                .setProvider(value(command.getProvider()))
                .setEnvironment(value(command.getEnvironment()))
                .setAccount(value(command.getAccount()))
                .setMarket(value(command.getMarket()))
                .setSymbol(value(command.getSymbol()))
                .setDecision(RiskDecision.REJECTED)
                .setReasons(List.copyOf(reasons))
                .setMaxQuantity(null)
                .setMaxNotional(null)
                .setDecidedAtMicros(Instant.now(clock))
                .setAttributes(Map.copyOf(attributes))
                .build();
    }

    private String signalId(OrderCommandEvent command) {
        if (command.getAttributes() == null) {
            return null;
        }
        return value(command.getAttributes().get("signal_id"));
    }

    private <T> T cast(Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
