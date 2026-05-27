package io.github.manu.projection;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.messaging.TradingEventHandler;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class TradingStateProjection implements TradingEventHandler {

    private static final int DEFAULT_MAX_APPLIED_EVENT_IDS = 100_000;

    private final Map<String, BalanceState> balances = new ConcurrentHashMap<>();
    private final Map<String, PositionState> positions = new ConcurrentHashMap<>();
    private final Map<String, OrderState> orders = new ConcurrentHashMap<>();
    private final Map<String, RiskState> risks = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> appliedEventIds = new LinkedHashSet<>();
    private final int maxAppliedEventIds;
    private final Object lock = new Object();

    public TradingStateProjection() {
        this(DEFAULT_MAX_APPLIED_EVENT_IDS);
    }

    TradingStateProjection(int maxAppliedEventIds) {
        if (maxAppliedEventIds <= 0) {
            throw new IllegalArgumentException("maxAppliedEventIds must be positive");
        }
        this.maxAppliedEventIds = maxAppliedEventIds;
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        apply(envelope);
        return CompletableFuture.completedFuture(null);
    }

    public ProjectionUpdate apply(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return switch (envelope.eventType()) {
            case BALANCE_UPDATE -> applyBalance(envelope, cast(envelope.value(), BalanceUpdateEvent.class));
            case POSITION_UPDATE -> applyPosition(envelope, cast(envelope.value(), PositionUpdateEvent.class));
            case ORDER_RESULT -> applyOrderResult(envelope, cast(envelope.value(), OrderResultEvent.class));
            case EXECUTION_REPORT -> applyExecutionReport(envelope, cast(envelope.value(), ExecutionReportEvent.class));
            case RISK_UPDATE -> applyRisk(envelope, cast(envelope.value(), RiskUpdateEvent.class));
            default -> ProjectionUpdate.ignored(envelope.eventType(), null);
        };
    }

    public Optional<BalanceState> balance(String provider, String environment, String account, String market, String asset) {
        return Optional.ofNullable(balances.get(key(provider, environment, account, market, asset)));
    }

    public Optional<PositionState> position(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String positionSide
    ) {
        return Optional.ofNullable(positions.get(key(provider, environment, account, market, symbol, positionSide)));
    }

    public Optional<OrderState> order(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId
    ) {
        return Optional.ofNullable(orders.get(key(provider, environment, account, market, symbol, clientOrderId)));
    }

    public Optional<RiskState> risk(
            String provider,
            String environment,
            String account,
            String market,
            String riskScope,
            String entityId
    ) {
        return Optional.ofNullable(risks.get(key(provider, environment, account, market, riskScope, entityId)));
    }

    public boolean hasOpenPositions(String provider, String environment, String account, String market) {
        String prefix = key(provider, environment, account, market);
        return positions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .map(Map.Entry::getValue)
                .anyMatch(PositionState::open);
    }

    public long externalOrderInterventions(String provider, String environment, String account, String market) {
        String prefix = key(provider, environment, account, market);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .map(Map.Entry::getValue)
                .filter(OrderState::externalIntervention)
                .count();
    }

    public boolean hasExternalOrderInterventions(String provider, String environment, String account, String market) {
        return externalOrderInterventions(provider, environment, account, market) > 0;
    }

    public TradingStateSnapshot snapshot() {
        synchronized (lock) {
            return new TradingStateSnapshot(
                    valuesByKey(balances),
                    valuesByKey(positions),
                    valuesByKey(orders),
                    valuesByKey(risks),
                    List.copyOf(appliedEventIds)
            );
        }
    }

    public void restore(TradingStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lock) {
            balances.clear();
            positions.clear();
            orders.clear();
            risks.clear();
            appliedEventIds.clear();
            for (BalanceState state : snapshot.balances()) {
                balances.put(key(state.provider(), state.environment(), state.account(), state.market(), state.asset()), state);
            }
            for (PositionState state : snapshot.positions()) {
                positions.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.symbol(),
                        state.positionSide()
                ), state);
            }
            for (OrderState state : snapshot.orders()) {
                orders.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.symbol(),
                        state.clientOrderId()
                ), state);
            }
            for (RiskState state : snapshot.risks()) {
                String entityId = state.symbol() == null ? state.underlying() : state.symbol();
                if (entityId == null) {
                    entityId = state.account();
                }
                risks.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.riskScope(),
                        entityId
                ), state);
            }
            for (String eventId : snapshot.appliedEventIds()) {
                rememberEventId(eventId);
            }
        }
    }

    private ProjectionUpdate applyBalance(TradingEventEnvelope<?> envelope, BalanceUpdateEvent event) {
        String eventId = value(event.getEventId());
        String entityKey = key(event.getProvider(), event.getEnvironment(), event.getAccount(), event.getMarket(), event.getAsset());
        BalanceState state = new BalanceState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getAsset()),
                value(event.getWalletBalance()),
                value(event.getCrossWalletBalance()),
                value(event.getAvailableBalance()),
                value(event.getBalanceDelta()),
                value(event.getUpdateReason()),
                event.getEventTimeMicros(),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), balances, state);
    }

    private ProjectionUpdate applyPosition(TradingEventEnvelope<?> envelope, PositionUpdateEvent event) {
        String eventId = value(event.getEventId());
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getPositionSide()
        );
        PositionState state = new PositionState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                value(event.getPositionSide()),
                value(event.getPositionAmount()),
                value(event.getEntryPrice()),
                value(event.getMarkPrice()),
                value(event.getUnrealizedPnl()),
                event.getEventTimeMicros(),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), positions, state);
    }

    private ProjectionUpdate applyOrderResult(TradingEventEnvelope<?> envelope, OrderResultEvent event) {
        String eventId = value(event.getEventId());
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getClientOrderId()
        );
        OrderState state = new OrderState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                value(event.getCommandId()),
                value(event.getClientOrderId()),
                value(event.getExchangeOrderId()),
                event.getStatus() == null ? null : event.getStatus().name(),
                value(event.getExchangeStatus()),
                value(event.getPrice()),
                value(event.getOriginalQuantity()),
                value(event.getExecutedQuantity()),
                value(event.getAveragePrice()),
                value(event.getCumulativeQuote()),
                "ORDER_RESULT",
                null,
                true,
                false,
                null,
                firstInstant(event.getExchangeTransactTimeMicros(), event.getObservedAtMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), orders, state);
    }

    private ProjectionUpdate applyExecutionReport(TradingEventEnvelope<?> envelope, ExecutionReportEvent event) {
        String eventId = value(event.getEventId());
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getClientOrderId()
        );
        OrderState current = orders.get(entityKey);
        OrderIntervention intervention = orderIntervention(current, event);
        OrderState state = new OrderState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                current == null ? null : current.commandId(),
                value(event.getClientOrderId()),
                value(event.getExchangeOrderId()),
                value(event.getOrderStatus()),
                value(event.getOrderStatus()),
                firstText(attribute(event, "orderPrice"), event.getLastExecutedPrice()),
                attribute(event, "orderQuantity"),
                value(event.getCumulativeFilledQuantity()),
                attribute(event, "averagePrice"),
                value(event.getCumulativeQuoteQuantity()),
                "USER_DATA",
                value(event.getExecutionType()),
                intervention.managedByBot(),
                intervention.externalIntervention(),
                intervention.reason(),
                firstInstant(event.getTransactionTimeMicros(), event.getEventTimeMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), orders, state);
    }

    private OrderIntervention orderIntervention(OrderState current, ExecutionReportEvent event) {
        if (current == null) {
            return new OrderIntervention(false, true, "external_order_observed");
        }
        if (Boolean.TRUE.equals(current.externalIntervention())) {
            return new OrderIntervention(
                    Boolean.TRUE.equals(current.managedByBot()),
                    true,
                    current.interventionReason()
            );
        }
        if (Boolean.TRUE.equals(current.managedByBot()) && unplannedManagedOrderChange(event)) {
            return new OrderIntervention(true, true, "unplanned_managed_order_change");
        }
        return new OrderIntervention(Boolean.TRUE.equals(current.managedByBot()), false, null);
    }

    private boolean unplannedManagedOrderChange(ExecutionReportEvent event) {
        String executionType = value(event.getExecutionType());
        if (executionType == null) {
            return false;
        }
        return switch (executionType) {
            case "CANCELED", "REPLACED", "AMENDMENT" -> true;
            default -> false;
        };
    }

    private ProjectionUpdate applyRisk(TradingEventEnvelope<?> envelope, RiskUpdateEvent event) {
        String eventId = value(event.getEventId());
        String entityId = value(event.getSymbol());
        if (entityId == null) {
            entityId = value(event.getUnderlying());
        }
        if (entityId == null) {
            entityId = value(event.getAccount());
        }
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getRiskScope(),
                entityId
        );
        RiskState state = new RiskState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getRiskScope()),
                value(event.getSymbol()),
                value(event.getUnderlying()),
                value(event.getRiskLevel()),
                value(event.getDelta()),
                value(event.getGamma()),
                value(event.getTheta()),
                value(event.getVega()),
                value(event.getMarginBalance()),
                value(event.getMaintenanceMargin()),
                firstInstant(event.getTransactionTimeMicros(), event.getEventTimeMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), risks, state);
    }

    private <T extends TimedState> ProjectionUpdate applyState(
            TradingEventType eventType,
            String entityKey,
            String eventId,
            Instant eventTime,
            Map<String, T> states,
            T state
    ) {
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(eventType, entityKey, eventId);
            }
            T current = states.get(entityKey);
            if (current != null && eventTime.isBefore(current.updatedAt())) {
                return ProjectionUpdate.stale(eventType, entityKey, eventId);
            }
            states.put(entityKey, state);
            return ProjectionUpdate.applied(eventType, entityKey, eventId);
        }
    }

    private boolean rememberEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        boolean added = appliedEventIds.add(eventId);
        while (appliedEventIds.size() > maxAppliedEventIds) {
            appliedEventIds.remove(appliedEventIds.getFirst());
        }
        return added;
    }

    private <T> List<T> valuesByKey(Map<String, T> states) {
        return states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private <T extends SpecificRecord> T cast(Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private Instant firstInstant(Instant preferred, Instant fallback) {
        return preferred == null ? Objects.requireNonNull(fallback, "fallback") : preferred;
    }

    private String key(CharSequence... parts) {
        StringBuilder builder = new StringBuilder();
        for (CharSequence part : parts) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(keyPart(part));
        }
        return builder.toString();
    }

    private String keyPart(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return "-";
        }
        return value.toString().trim();
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private String attribute(ExecutionReportEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private String firstText(CharSequence preferred, CharSequence fallback) {
        String value = value(preferred);
        return value == null ? value(fallback) : value;
    }

    public interface TimedState {
        Instant updatedAt();
    }

    public record BalanceState(
            String provider,
            String environment,
            String account,
            String market,
            String asset,
            String walletBalance,
            String crossWalletBalance,
            String availableBalance,
            String balanceDelta,
            String updateReason,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
    }

    public record PositionState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String positionSide,
            String positionAmount,
            String entryPrice,
            String markPrice,
            String unrealizedPnl,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
        public boolean open() {
            return positionAmount != null && new BigDecimal(positionAmount).compareTo(BigDecimal.ZERO) != 0;
        }
    }

    public record OrderState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String commandId,
            String clientOrderId,
            String exchangeOrderId,
            String status,
            String exchangeStatus,
            String price,
            String originalQuantity,
            String executedQuantity,
            String averagePrice,
            String cumulativeQuote,
            String updateSource,
            String executionType,
            Boolean managedByBot,
            Boolean externalIntervention,
            String interventionReason,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
        public OrderState {
            managedByBot = Boolean.TRUE.equals(managedByBot);
            externalIntervention = Boolean.TRUE.equals(externalIntervention);
        }
    }

    private record OrderIntervention(
            boolean managedByBot,
            boolean externalIntervention,
            String reason
    ) {
    }

    public record RiskState(
            String provider,
            String environment,
            String account,
            String market,
            String riskScope,
            String symbol,
            String underlying,
            String riskLevel,
            String delta,
            String gamma,
            String theta,
            String vega,
            String marginBalance,
            String maintenanceMargin,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
    }
}
