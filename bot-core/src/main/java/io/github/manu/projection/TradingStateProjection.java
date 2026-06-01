package io.github.manu.projection;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.messaging.TradingEventHandler;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final Map<String, ManualReviewDecisionState> manualReviewDecisions = new ConcurrentHashMap<>();
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
            case ORDER_COMMAND -> applyOrderCommand(envelope, cast(envelope.value(), OrderCommandEvent.class));
            case ORDER_RESULT -> applyOrderResult(envelope, cast(envelope.value(), OrderResultEvent.class));
            case EXECUTION_REPORT -> applyExecutionReport(envelope, cast(envelope.value(), ExecutionReportEvent.class));
            case RISK_UPDATE -> applyRisk(envelope, cast(envelope.value(), RiskUpdateEvent.class));
            case RISK_DECISION -> applyRiskDecision(envelope, cast(envelope.value(), RiskDecisionEvent.class));
            case INTERVENTION_ACKNOWLEDGEMENT -> applyInterventionAcknowledgement(
                    envelope,
                    cast(envelope.value(), InterventionAcknowledgementEvent.class)
            );
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

    public Optional<OrderState> orderByExchangeOrderId(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String exchangeOrderId
    ) {
        String prefix = key(provider, environment, account, market, symbol);
        String expectedExchangeOrderId = value(exchangeOrderId);
        if (expectedExchangeOrderId == null) {
            return Optional.empty();
        }
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(order -> expectedExchangeOrderId.equals(order.exchangeOrderId()))
                .findFirst();
    }

    public List<OrderState> ordersByCommandId(
            String provider,
            String environment,
            String account,
            String market,
            String commandId
    ) {
        String prefix = key(provider, environment, account, market);
        String expectedCommandId = value(commandId);
        if (expectedCommandId == null) {
            return List.of();
        }
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(order -> expectedCommandId.equals(order.commandId()))
                .toList();
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
        return externalOrderInterventionStates(provider, environment, account, market).size();
    }

    public List<OrderState> externalOrderInterventionStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(OrderState::externalIntervention)
                .toList();
    }

    public boolean hasExternalOrderInterventions(String provider, String environment, String account, String market) {
        return externalOrderInterventions(provider, environment, account, market) > 0;
    }

    public long unknownOrderStatuses(String provider, String environment, String account, String market) {
        return unknownOrderStatusStates(provider, environment, account, market).size();
    }

    public List<OrderState> unknownOrderStatusStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(OrderState::unknownStatus)
                .toList();
    }

    public boolean hasUnknownOrderStatuses(String provider, String environment, String account, String market) {
        return unknownOrderStatuses(provider, environment, account, market) > 0;
    }

    public long unresolvedOrderCommands(String provider, String environment, String account, String market) {
        return unresolvedOrderCommandStates(provider, environment, account, market).size();
    }

    public List<OrderState> unresolvedOrderCommandStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(OrderState::unresolvedCommand)
                .toList();
    }

    public boolean hasUnresolvedOrderCommands(String provider, String environment, String account, String market) {
        return unresolvedOrderCommands(provider, environment, account, market) > 0;
    }

    public long externalPositionInterventions(String provider, String environment, String account, String market) {
        return externalPositionInterventionStates(provider, environment, account, market).size();
    }

    public List<PositionState> externalPositionInterventionStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return positions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(PositionState::externalIntervention)
                .toList();
    }

    public boolean hasExternalPositionInterventions(String provider, String environment, String account, String market) {
        return externalPositionInterventions(provider, environment, account, market) > 0;
    }

    public List<ManualReviewDecisionState> manualReviewDecisionStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return manualReviewDecisions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(this::pendingManualReview)
                .toList();
    }

    private boolean pendingManualReview(ManualReviewDecisionState decision) {
        boolean orderReason = decision.reasons().contains("intervention:external_order");
        boolean positionReason = decision.reasons().contains("intervention:external_position");
        boolean unknownOrderReason = decision.reasons().contains("order_status:unknown");
        boolean unresolvedCommandReason = decision.reasons().contains("order_command:unresolved");
        if (!orderReason && !positionReason && !unknownOrderReason && !unresolvedCommandReason) {
            return true;
        }
        if (orderReason && hasExternalOrderInterventions(
                decision.provider(),
                decision.environment(),
                decision.account(),
                decision.market()
        )) {
            return true;
        }
        if (positionReason && hasExternalPositionInterventions(
                decision.provider(),
                decision.environment(),
                decision.account(),
                decision.market()
        )) {
            return true;
        }
        if (unknownOrderReason && hasUnknownOrderStatuses(
                decision.provider(),
                decision.environment(),
                decision.account(),
                decision.market()
        )) {
            return true;
        }
        return unresolvedCommandReason && hasUnresolvedOrderCommands(
                decision.provider(),
                decision.environment(),
                decision.account(),
                decision.market()
        );
    }

    public TradingStateSnapshot snapshot() {
        synchronized (lock) {
            return new TradingStateSnapshot(
                    valuesByKey(balances),
                    valuesByKey(positions),
                    valuesByKey(orders),
                    valuesByKey(risks),
                    valuesByKey(manualReviewDecisions),
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
            manualReviewDecisions.clear();
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
            for (ManualReviewDecisionState state : snapshot.manualReviewDecisions()) {
                manualReviewDecisions.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.commandId()
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
        PositionState current = positions.get(entityKey);
        PositionIntervention intervention = positionIntervention(
                current,
                event,
                value(event.getPositionAmount()),
                positionUpdateSource(event)
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
                positionUpdateSource(event),
                intervention.externalIntervention(),
                intervention.reason(),
                event.getEventTimeMicros(),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), positions, state);
    }

    private ProjectionUpdate applyOrderCommand(TradingEventEnvelope<?> envelope, OrderCommandEvent event) {
        String eventId = value(event.getEventId());
        OrderCommandProjectionTarget target = orderCommandProjectionTarget(event);
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                target.clientOrderId()
        );
        OrderState current = orders.get(entityKey);
        OrderState state = new OrderState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                value(event.getCommandId()),
                target.clientOrderId(),
                firstText(target.exchangeOrderId(), current == null ? null : current.exchangeOrderId()),
                "COMMAND_RECEIVED",
                current == null ? null : current.exchangeStatus(),
                firstText(value(event.getPrice()), current == null ? null : current.price()),
                firstText(value(event.getQuantity()), current == null ? null : current.originalQuantity()),
                current == null ? null : current.executedQuantity(),
                current == null ? null : current.averagePrice(),
                current == null ? null : current.cumulativeQuote(),
                "ORDER_COMMAND",
                action(event).name(),
                true,
                false,
                null,
                event.getRequestedAtMicros(),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), orders, state);
    }

    private OrderCommandProjectionTarget orderCommandProjectionTarget(OrderCommandEvent event) {
        String commandClientOrderId = value(event.getClientOrderId());
        if (action(event) == OrderCommandAction.NEW) {
            return new OrderCommandProjectionTarget(commandClientOrderId, null);
        }
        String targetClientOrderId = value(event.getTargetClientOrderId());
        String targetExchangeOrderId = value(event.getTargetExchangeOrderId());
        if (targetClientOrderId != null) {
            return new OrderCommandProjectionTarget(targetClientOrderId, targetExchangeOrderId);
        }
        if (targetExchangeOrderId == null) {
            return new OrderCommandProjectionTarget(commandClientOrderId, null);
        }
        return orderByExchangeOrderId(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                targetExchangeOrderId
        )
                .map(order -> new OrderCommandProjectionTarget(order.clientOrderId(), targetExchangeOrderId))
                .orElseGet(() -> new OrderCommandProjectionTarget(commandClientOrderId, targetExchangeOrderId));
    }

    private OrderCommandAction action(OrderCommandEvent event) {
        return event.getAction() == null ? OrderCommandAction.NEW : event.getAction();
    }

    private ProjectionUpdate applyOrderResult(TradingEventEnvelope<?> envelope, OrderResultEvent event) {
        String eventId = value(event.getEventId());
        OrderResultProjectionTarget target = orderResultProjectionTarget(event);
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                target.clientOrderId()
        );
        OrderState current = orders.get(entityKey);
        OrderIntervention intervention = orderResultIntervention(current, event);
        OrderState state = new OrderState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                orderResultCommandId(current, event),
                target.clientOrderId(),
                firstText(target.exchangeOrderId(), event.getExchangeOrderId()),
                event.getStatus() == null ? null : event.getStatus().name(),
                value(event.getExchangeStatus()),
                value(event.getPrice()),
                value(event.getOriginalQuantity()),
                value(event.getExecutedQuantity()),
                value(event.getAveragePrice()),
                value(event.getCumulativeQuote()),
                orderResultUpdateSource(event),
                null,
                intervention.managedByBot(),
                intervention.externalIntervention(),
                intervention.reason(),
                firstInstant(event.getExchangeTransactTimeMicros(), event.getObservedAtMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), orders, state);
    }

    private String orderResultCommandId(OrderState current, OrderResultEvent event) {
        if (isRestSnapshot(event) && current != null && current.commandId() != null) {
            return current.commandId();
        }
        return value(event.getCommandId());
    }

    private String orderResultUpdateSource(OrderResultEvent event) {
        return isRestSnapshot(event) ? "REST_SNAPSHOT" : "ORDER_RESULT";
    }

    private OrderIntervention orderResultIntervention(OrderState current, OrderResultEvent event) {
        if (!isRestSnapshot(event)) {
            return new OrderIntervention(true, false, null);
        }
        if (current == null) {
            return new OrderIntervention(false, true, "external_order_observed");
        }
        if (current.externalIntervention()) {
            return new OrderIntervention(current.managedByBot(), true, current.interventionReason());
        }
        return new OrderIntervention(current.managedByBot(), false, null);
    }

    private OrderResultProjectionTarget orderResultProjectionTarget(OrderResultEvent event) {
        String clientOrderId = value(event.getClientOrderId());
        String exchangeOrderId = value(event.getExchangeOrderId());
        if (!isGatewayFailure(event)) {
            return new OrderResultProjectionTarget(clientOrderId, exchangeOrderId);
        }
        String targetClientOrderId = attribute(event, "target_client_order_id");
        String targetExchangeOrderId = firstText(attribute(event, "target_exchange_order_id"), exchangeOrderId);
        if (targetClientOrderId != null) {
            return new OrderResultProjectionTarget(targetClientOrderId, targetExchangeOrderId);
        }
        if (targetExchangeOrderId == null) {
            return new OrderResultProjectionTarget(clientOrderId, exchangeOrderId);
        }
        return orderByExchangeOrderId(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                targetExchangeOrderId
        )
                .map(order -> new OrderResultProjectionTarget(order.clientOrderId(), targetExchangeOrderId))
                .orElseGet(() -> new OrderResultProjectionTarget(clientOrderId, targetExchangeOrderId));
    }

    private boolean isGatewayFailure(OrderResultEvent event) {
        return "true".equals(attribute(event, "gateway_failure"));
    }

    private boolean isRestSnapshot(OrderResultEvent event) {
        return "rest_snapshot".equals(attribute(event, "source"));
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

    private ProjectionUpdate applyRiskDecision(
            TradingEventEnvelope<?> envelope,
            RiskDecisionEvent event
    ) {
        String eventId = value(event.getEventId());
        String commandId = value(event.getCommandId());
        if (commandId == null) {
            return ProjectionUpdate.ignored(envelope.eventType(), eventId);
        }
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                commandId
        );
        Instant decidedAt = event.getDecidedAtMicros();
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(envelope.eventType(), entityKey, eventId);
            }
            ManualReviewDecisionState current = manualReviewDecisions.get(entityKey);
            if (current != null && decidedAt.isBefore(current.updatedAt())) {
                return ProjectionUpdate.stale(envelope.eventType(), entityKey, eventId);
            }
            if (event.getDecision() != RiskDecision.MANUAL_REVIEW) {
                manualReviewDecisions.remove(entityKey);
                return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
            }
            ManualReviewDecisionState state = new ManualReviewDecisionState(
                    value(event.getProvider()),
                    value(event.getEnvironment()),
                    value(event.getAccount()),
                    value(event.getMarket()),
                    value(event.getSymbol()),
                    commandId,
                    value(event.getSignalId()),
                    value(event.getStrategyId()),
                    value(event.getDecisionId()),
                    stringList(event.getReasons()),
                    stringMap(event.getAttributes()),
                    decidedAt,
                    eventId
            );
            manualReviewDecisions.put(entityKey, state);
            return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
        }
    }

    private ProjectionUpdate applyInterventionAcknowledgement(
            TradingEventEnvelope<?> envelope,
            InterventionAcknowledgementEvent event
    ) {
        if (value(event.getClientOrderId()) == null) {
            return applyPositionInterventionAcknowledgement(envelope, event);
        }
        return applyOrderInterventionAcknowledgement(envelope, event);
    }

    private ProjectionUpdate applyOrderInterventionAcknowledgement(
            TradingEventEnvelope<?> envelope,
            InterventionAcknowledgementEvent event
    ) {
        String eventId = value(event.getEventId());
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getClientOrderId()
        );
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(envelope.eventType(), entityKey, eventId);
            }
            OrderState current = orders.get(entityKey);
            if (current == null) {
                return ProjectionUpdate.ignored(envelope.eventType(), eventId);
            }
            if (!interventionReasonMatches(current, event)) {
                return ProjectionUpdate.ignored(envelope.eventType(), eventId);
            }
            if (event.getAcknowledgedAtMicros().isBefore(current.updatedAt())) {
                return ProjectionUpdate.stale(envelope.eventType(), entityKey, eventId);
            }
            OrderState acknowledged = new OrderState(
                    current.provider(),
                    current.environment(),
                    current.account(),
                    current.market(),
                    current.symbol(),
                    current.commandId(),
                    current.clientOrderId(),
                    current.exchangeOrderId(),
                    current.status(),
                    current.exchangeStatus(),
                    current.price(),
                    current.originalQuantity(),
                    current.executedQuantity(),
                    current.averagePrice(),
                    current.cumulativeQuote(),
                    "INTERVENTION_ACKNOWLEDGEMENT",
                    current.executionType(),
                    current.managedByBot(),
                    false,
                    null,
                    event.getAcknowledgedAtMicros(),
                    eventId
            );
            orders.put(entityKey, acknowledged);
            return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
        }
    }

    private ProjectionUpdate applyPositionInterventionAcknowledgement(
            TradingEventEnvelope<?> envelope,
            InterventionAcknowledgementEvent event
    ) {
        String eventId = value(event.getEventId());
        String positionSide = acknowledgementAttribute(event, "position_side");
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                positionSide
        );
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(envelope.eventType(), entityKey, eventId);
            }
            PositionState current = positions.get(entityKey);
            if (current == null) {
                return ProjectionUpdate.ignored(envelope.eventType(), eventId);
            }
            if (!interventionReasonMatches(current, event)) {
                return ProjectionUpdate.ignored(envelope.eventType(), eventId);
            }
            if (event.getAcknowledgedAtMicros().isBefore(current.updatedAt())) {
                return ProjectionUpdate.stale(envelope.eventType(), entityKey, eventId);
            }
            PositionState acknowledged = new PositionState(
                    current.provider(),
                    current.environment(),
                    current.account(),
                    current.market(),
                    current.symbol(),
                    current.positionSide(),
                    current.positionAmount(),
                    current.entryPrice(),
                    current.markPrice(),
                    current.unrealizedPnl(),
                    "INTERVENTION_ACKNOWLEDGEMENT",
                    false,
                    null,
                    event.getAcknowledgedAtMicros(),
                    eventId
            );
            positions.put(entityKey, acknowledged);
            return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
        }
    }

    private boolean interventionReasonMatches(OrderState current, InterventionAcknowledgementEvent event) {
        String expectedReason = value(event.getInterventionReason());
        return expectedReason == null || expectedReason.equals(current.interventionReason());
    }

    private boolean interventionReasonMatches(PositionState current, InterventionAcknowledgementEvent event) {
        String expectedReason = value(event.getInterventionReason());
        return expectedReason == null || expectedReason.equals(current.interventionReason());
    }

    private String acknowledgementAttribute(InterventionAcknowledgementEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private List<String> stringList(List<? extends CharSequence> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::value)
                .filter(Objects::nonNull)
                .toList();
    }

    private Map<String, String> stringMap(Map<CharSequence, CharSequence> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, entryValue) -> {
            String normalizedKey = value(key);
            String normalizedValue = value(entryValue);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(normalized);
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

    private String attribute(OrderResultEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private String firstText(CharSequence preferred, CharSequence fallback) {
        String value = value(preferred);
        return value == null ? value(fallback) : value;
    }

    private PositionIntervention positionIntervention(
            PositionState current,
            PositionUpdateEvent event,
            String positionAmount,
            String updateSource
    ) {
        if (current == null) {
            return PositionIntervention.none();
        }
        if (current.externalIntervention()) {
            return new PositionIntervention(true, current.interventionReason());
        }
        if (!positionAmountChanged(current.positionAmount(), positionAmount)) {
            return PositionIntervention.none();
        }
        if (!("USER_DATA".equals(updateSource) || "REST_SNAPSHOT".equals(updateSource))) {
            return PositionIntervention.none();
        }
        if (hasManagedOrderForSymbol(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol())
        )) {
            return PositionIntervention.none();
        }
        return new PositionIntervention(true, "external_position_change");
    }

    private String positionUpdateSource(PositionUpdateEvent event) {
        String source = attribute(event, "source");
        if ("rest_snapshot".equals(source)) {
            return "REST_SNAPSHOT";
        }
        if (attribute(event, "rawEventType") != null) {
            return "USER_DATA";
        }
        return "POSITION_UPDATE";
    }

    private boolean hasManagedOrderForSymbol(
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        String prefix = key(provider, environment, account, market, symbol);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .map(Map.Entry::getValue)
                .anyMatch(OrderState::managedByBot);
    }

    private boolean positionAmountChanged(String first, String second) {
        if (first == null || second == null) {
            return !Objects.equals(first, second);
        }
        try {
            return new BigDecimal(first).compareTo(new BigDecimal(second)) != 0;
        } catch (NumberFormatException ignored) {
            return !first.equals(second);
        }
    }

    private String attribute(PositionUpdateEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
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
            String updateSource,
            Boolean externalIntervention,
            String interventionReason,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
        public PositionState {
            externalIntervention = Boolean.TRUE.equals(externalIntervention);
        }

        public boolean open() {
            return positionAmount != null && new BigDecimal(positionAmount).compareTo(BigDecimal.ZERO) != 0;
        }
    }

    private record PositionIntervention(
            boolean externalIntervention,
            String reason
    ) {
        private static PositionIntervention none() {
            return new PositionIntervention(false, null);
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

        public boolean unknownStatus() {
            return "UNKNOWN".equals(status);
        }

        public boolean unresolvedCommand() {
            return "COMMAND_RECEIVED".equals(status);
        }
    }

    private record OrderIntervention(
            boolean managedByBot,
            boolean externalIntervention,
            String reason
    ) {
    }

    private record OrderCommandProjectionTarget(
            String clientOrderId,
            String exchangeOrderId
    ) {
    }

    private record OrderResultProjectionTarget(
            String clientOrderId,
            String exchangeOrderId
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

    public record ManualReviewDecisionState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String commandId,
            String signalId,
            String strategyId,
            String decisionId,
            List<String> reasons,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) implements TimedState {

        public ManualReviewDecisionState {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
