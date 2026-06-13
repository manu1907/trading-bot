package io.github.manu.projection;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.events.v1.MarketDataEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.events.v1.StrategyLifecycleEvent;
import io.github.manu.messaging.TradingEventHandler;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final Map<String, MarketDataState> marketData = new ConcurrentHashMap<>();
    private final Map<String, DailyRealizedPnlState> dailyRealizedPnl = new ConcurrentHashMap<>();
    private final Map<String, ManualReviewDecisionState> manualReviewDecisions = new ConcurrentHashMap<>();
    private final Map<String, RemediationDecisionState> remediationDecisions = new ConcurrentHashMap<>();
    private final Map<String, PauseGovernanceState> pauseGovernance = new ConcurrentHashMap<>();
    private final Map<String, StrategyLifecycleState> strategyLifecycle = new ConcurrentHashMap<>();
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
            case MARKET_DATA -> applyMarketData(envelope, cast(envelope.value(), MarketDataEvent.class));
            case RISK_DECISION -> applyRiskDecision(envelope, cast(envelope.value(), RiskDecisionEvent.class));
            case INTERVENTION_ACKNOWLEDGEMENT -> applyInterventionAcknowledgement(
                    envelope,
                    cast(envelope.value(), InterventionAcknowledgementEvent.class)
            );
            case REMEDIATION_DECISION -> applyRemediationDecision(
                    envelope,
                    cast(envelope.value(), RemediationDecisionEvent.class)
            );
            case STRATEGY_LIFECYCLE -> applyStrategyLifecycle(
                    envelope,
                    cast(envelope.value(), StrategyLifecycleEvent.class)
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

    public Optional<MarketDataState> marketData(String provider, String environment, String market, String symbol) {
        return Optional.ofNullable(marketData.get(key(provider, environment, market, symbol)));
    }

    public Optional<DailyRealizedPnlState> dailyRealizedPnl(
            String provider,
            String environment,
            String account,
            String market,
            String tradingDay
    ) {
        return dailyRealizedPnl(provider, environment, account, market, null, tradingDay);
    }

    public Optional<DailyRealizedPnlState> dailyRealizedPnl(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String tradingDay
    ) {
        return Optional.ofNullable(dailyRealizedPnl.get(dailyRealizedPnlKey(
                provider,
                environment,
                account,
                market,
                symbol,
                tradingDay
        )));
    }

    public boolean hasOpenPositions(String provider, String environment, String account, String market) {
        String prefix = key(provider, environment, account, market);
        return positions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .map(Map.Entry::getValue)
                .anyMatch(PositionState::open);
    }

    public List<PositionState> openPositionStates(
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
                .filter(PositionState::open)
                .toList();
    }

    public List<OrderState> openOrderStates(String provider, String environment, String account, String market) {
        return openOrderStates(provider, environment, account, market, null);
    }

    public List<OrderState> openOrderStates(
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        String prefix = key(provider, environment, account, market);
        String targetSymbol = value(symbol);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .filter(OrderState::open)
                .filter(order -> targetSymbol == null || targetSymbol.equalsIgnoreCase(order.symbol()))
                .toList();
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

    public List<RemediationDecisionState> remediationDecisionStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return remediationDecisions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public List<PauseGovernanceState> pauseGovernanceStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String prefix = key(provider, environment, account, market);
        return pauseGovernance.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public boolean accountPaused(String provider, String environment, String account, String market) {
        return accountPaused(provider, environment, account, market, Instant.now());
    }

    public boolean accountPaused(String provider, String environment, String account, String market, Instant now) {
        return activePause(pauseGovernance.get(key(provider, environment, account, market, "ACCOUNT", account)), now);
    }

    public boolean symbolPaused(String provider, String environment, String account, String market, String symbol) {
        return symbolPaused(provider, environment, account, market, symbol, Instant.now());
    }

    public boolean symbolPaused(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            Instant now
    ) {
        return accountPaused(provider, environment, account, market, now)
                || activePause(pauseGovernance.get(key(provider, environment, account, market, "SYMBOL", symbol)), now);
    }

    public Optional<StrategyLifecycleState> strategyLifecycle(
            String strategyId,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return Optional.ofNullable(strategyLifecycle.get(key(strategyId, provider, environment, account, market)));
    }

    private boolean activePause(PauseGovernanceState state, Instant now) {
        return state != null && state.effectiveActive(now);
    }

    public TradingStateSnapshot snapshot() {
        synchronized (lock) {
            return new TradingStateSnapshot(
                    valuesByKey(balances),
                    valuesByKey(positions),
                    valuesByKey(orders),
                    valuesByKey(risks),
                    valuesByKey(marketData),
                    valuesByKey(dailyRealizedPnl),
                    valuesByKey(manualReviewDecisions),
                    valuesByKey(remediationDecisions),
                    valuesByKey(pauseGovernance),
                    valuesByKey(strategyLifecycle),
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
            marketData.clear();
            dailyRealizedPnl.clear();
            manualReviewDecisions.clear();
            remediationDecisions.clear();
            pauseGovernance.clear();
            strategyLifecycle.clear();
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
            for (MarketDataState state : snapshot.marketData()) {
                marketData.put(key(
                        state.provider(),
                        state.environment(),
                        state.market(),
                        state.symbol()
                ), state);
            }
            for (DailyRealizedPnlState state : snapshot.dailyRealizedPnl()) {
                dailyRealizedPnl.put(dailyRealizedPnlKey(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.symbol(),
                        state.tradingDay()
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
            for (RemediationDecisionState state : snapshot.remediationDecisions()) {
                remediationDecisions.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.remediationId()
                ), state);
            }
            for (PauseGovernanceState state : snapshot.pauseGovernance()) {
                pauseGovernance.put(key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.pauseScope(),
                        state.pauseTarget()
                ), state);
            }
            for (StrategyLifecycleState state : snapshot.strategyLifecycle()) {
                strategyLifecycle.put(key(
                        state.strategyId(),
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market()
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
                positionMode(event),
                value(event.getPositionAmount()),
                value(event.getEntryPrice()),
                value(event.getMarkPrice()),
                value(event.getUnrealizedPnl()),
                value(event.getLeverage()),
                value(event.getMarginType()),
                value(event.getIsolatedMargin()),
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
                event.getSide() == null ? current == null ? null : current.side() : event.getSide().name(),
                event.getOrderType() == null ? current == null ? null : current.orderType() : event.getOrderType().name(),
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
        String targetClientOrderId = targetClientOrderId(event);
        String targetExchangeOrderId = targetExchangeOrderId(event);
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

    private String targetClientOrderId(OrderCommandEvent event) {
        String target = value(event.getTargetClientOrderId());
        if (target != null) {
            return target;
        }
        return attribute(event, "target_client_order_id");
    }

    private String targetExchangeOrderId(OrderCommandEvent event) {
        String target = value(event.getTargetExchangeOrderId());
        if (target != null) {
            return target;
        }
        return attribute(event, "target_exchange_order_id");
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
                current == null ? null : current.side(),
                current == null ? null : current.orderType(),
                value(event.getPrice()),
                value(event.getOriginalQuantity()),
                value(event.getExecutedQuantity()),
                value(event.getAveragePrice()),
                value(event.getCumulativeQuote()),
                orderResultUpdateSource(event),
                orderResultExecutionType(current, event),
                intervention.managedByBot(),
                intervention.externalIntervention(),
                intervention.reason(),
                firstInstant(event.getExchangeTransactTimeMicros(), event.getObservedAtMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), orders, state);
    }

    private void applyDailyRealizedPnl(ExecutionReportEvent event, Instant eventTime, String eventId) {
        BigDecimal realizedPnl = decimal(firstValue(
                attribute(event, "realizedProfit"),
                attribute(event, "realizedPnl"),
                attribute(event, "realized_pnl")
        ));
        if (realizedPnl == null) {
            return;
        }
        String tradingDay = tradingDay(eventTime);
        applyDailyRealizedPnlState(event, eventTime, eventId, tradingDay, null, realizedPnl);
        applyDailyRealizedPnlState(event, eventTime, eventId, tradingDay, value(event.getSymbol()), realizedPnl);
    }

    private void applyDailyRealizedPnlState(
            ExecutionReportEvent event,
            Instant eventTime,
            String eventId,
            String tradingDay,
            String symbol,
            BigDecimal realizedPnl
    ) {
        String entityKey = dailyRealizedPnlKey(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                symbol,
                tradingDay
        );
        DailyRealizedPnlState current = dailyRealizedPnl.get(entityKey);
        BigDecimal currentPnl = current == null ? BigDecimal.ZERO : decimal(current.realizedPnl());
        if (currentPnl == null) {
            currentPnl = BigDecimal.ZERO;
        }
        dailyRealizedPnl.put(entityKey, new DailyRealizedPnlState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                symbol,
                tradingDay,
                normalizeDecimal(currentPnl.add(realizedPnl)),
                eventTime,
                eventId
        ));
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

    private String orderResultExecutionType(OrderState current, OrderResultEvent event) {
        if (event.getStatus() != OrderResultStatus.UNKNOWN) {
            return null;
        }
        String commandAction = attribute(event, "command_action");
        if (commandAction != null) {
            return commandAction;
        }
        return current == null ? null : current.executionType();
    }

    private OrderIntervention orderResultIntervention(OrderState current, OrderResultEvent event) {
        if (!isRestSnapshot(event)) {
            return new OrderIntervention(true, false, null);
        }
        if (current != null
                && current.externalIntervention()
                && !"external_order_observed".equals(current.interventionReason())) {
            return new OrderIntervention(current.managedByBot(), true, current.interventionReason());
        }
        if (resolvedNoFillExternalOrder(event)) {
            return new OrderIntervention(current != null && current.managedByBot(), false, null);
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
                value(event.getSide()),
                value(event.getOrderType()),
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
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(envelope.eventType(), entityKey, eventId);
            }
            OrderState currentState = orders.get(entityKey);
            if (currentState != null && state.updatedAt().isBefore(currentState.updatedAt())) {
                return ProjectionUpdate.stale(envelope.eventType(), entityKey, eventId);
            }
            orders.put(entityKey, state);
            applyDailyRealizedPnl(event, state.updatedAt(), eventId);
            return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
        }
    }

    private OrderIntervention orderIntervention(OrderState current, ExecutionReportEvent event) {
        if (current == null) {
            if (resolvedNoFillExternalOrder(event)) {
                return new OrderIntervention(false, false, null);
            }
            return new OrderIntervention(false, true, "external_order_observed");
        }
        if (Boolean.TRUE.equals(current.externalIntervention())) {
            if (resolvedNoFillExternalOrder(event) && "external_order_observed".equals(current.interventionReason())) {
                return new OrderIntervention(Boolean.TRUE.equals(current.managedByBot()), false, null);
            }
            return new OrderIntervention(
                    Boolean.TRUE.equals(current.managedByBot()),
                    true,
                    current.interventionReason()
            );
        }
        if (Boolean.TRUE.equals(current.managedByBot()) && plannedManagedOrderChange(current, event)) {
            return new OrderIntervention(true, false, null);
        }
        if (Boolean.TRUE.equals(current.managedByBot()) && unplannedManagedOrderChange(event)) {
            return new OrderIntervention(true, true, "unplanned_managed_order_change");
        }
        return new OrderIntervention(Boolean.TRUE.equals(current.managedByBot()), false, null);
    }

    private boolean plannedManagedOrderChange(OrderState current, ExecutionReportEvent event) {
        String executionType = value(event.getExecutionType());
        if (!unplannedManagedOrderChange(event)) {
            return false;
        }
        String orderStatus = value(event.getOrderStatus());
        String currentStatus = value(current.status());
        if (currentStatus != null && currentStatus.equals(orderStatus)) {
            return true;
        }
        String pendingAction = value(current.executionType());
        return switch (pendingAction == null ? "" : pendingAction) {
            case "CANCEL" -> "CANCELED".equals(executionType) || "CANCELED".equals(orderStatus);
            case "MODIFY" -> "AMENDMENT".equals(executionType) || "REPLACED".equals(executionType);
            default -> false;
        };
    }

    private boolean resolvedNoFillExternalOrder(OrderResultEvent event) {
        String status = event.getStatus() == null ? null : event.getStatus().name();
        return noFillTerminalOrder(status, value(event.getExecutedQuantity()));
    }

    private boolean resolvedNoFillExternalOrder(ExecutionReportEvent event) {
        return noFillTerminalOrder(
                value(event.getOrderStatus()),
                value(event.getCumulativeFilledQuantity())
        );
    }

    private boolean noFillTerminalOrder(String status, String executedQuantity) {
        if (!zeroAmount(executedQuantity)) {
            return false;
        }
        return switch (status == null ? "" : status) {
            case "CANCELED", "EXPIRED", "REJECTED" -> true;
            default -> false;
        };
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
        String marginBalance = value(event.getMarginBalance());
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
                marginBalance,
                maxMarginBalance(marginBalance, risks.get(entityKey)),
                value(event.getMaintenanceMargin()),
                firstInstant(event.getTransactionTimeMicros(), event.getEventTimeMicros()),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), risks, state);
    }

    private ProjectionUpdate applyMarketData(TradingEventEnvelope<?> envelope, MarketDataEvent event) {
        String eventId = value(event.getEventId());
        String entityKey = key(event.getProvider(), event.getEnvironment(), event.getMarket(), event.getSymbol());
        Instant eventTime = firstInstant(event.getReceivedAtMicros(), event.getOccurredAtMicros());
        MarketDataState current = marketData.get(entityKey);
        TopOfBook topOfBook = topOfBook(event, current);
        LastTrade lastTrade = lastTrade(event, current);
        MarketDataState state = new MarketDataState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getMarket()),
                value(event.getSymbol()),
                event.getEventType() == null ? null : event.getEventType().name(),
                topOfBook.bestBidPrice(),
                topOfBook.bestBidQuantity(),
                topOfBook.bestAskPrice(),
                topOfBook.bestAskQuantity(),
                topOfBook.updatedAt(),
                lastTrade.price(),
                lastTrade.quantity(),
                lastTrade.side(),
                lastTrade.updatedAt(),
                marketDataAttributes(event, current),
                eventTime,
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), marketData, state);
    }

    private TopOfBook topOfBook(MarketDataEvent event, MarketDataState current) {
        if (event.getEventType() != MarketDataEventType.BOOK_TICKER
                && event.getEventType() != MarketDataEventType.DEPTH_SNAPSHOT
                && event.getEventType() != MarketDataEventType.DEPTH_DELTA) {
            return current == null
                    ? new TopOfBook(null, null, null, null, null)
                    : new TopOfBook(
                            current.bestBidPrice(),
                            current.bestBidQuantity(),
                            current.bestAskPrice(),
                            current.bestAskQuantity(),
                            current.topOfBookUpdatedAt()
                    );
        }
        String bidPrice = event.getBids().isEmpty() ? null : value(event.getBids().getFirst().getPrice());
        String bidQuantity = event.getBids().isEmpty() ? null : value(event.getBids().getFirst().getQuantity());
        String askPrice = event.getAsks().isEmpty() ? null : value(event.getAsks().getFirst().getPrice());
        String askQuantity = event.getAsks().isEmpty() ? null : value(event.getAsks().getFirst().getQuantity());
        return new TopOfBook(
                bidPrice,
                bidQuantity,
                askPrice,
                askQuantity,
                firstInstant(event.getReceivedAtMicros(), event.getOccurredAtMicros())
        );
    }

    private LastTrade lastTrade(MarketDataEvent event, MarketDataState current) {
        if (event.getEventType() != MarketDataEventType.TRADE) {
            return current == null
                    ? new LastTrade(null, null, null, null)
                    : new LastTrade(
                            current.lastTradePrice(),
                            current.lastTradeQuantity(),
                            current.lastTradeSide(),
                            current.lastTradeUpdatedAt()
                    );
        }
        return new LastTrade(
                value(event.getPrice()),
                value(event.getQuantity()),
                value(event.getSide()),
                firstInstant(event.getReceivedAtMicros(), event.getOccurredAtMicros())
        );
    }

    private Map<String, String> marketDataAttributes(MarketDataEvent event, MarketDataState current) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (current != null) {
            preserveMarketDataAttribute(current, attributes, "quoteVolume");
            preserveMarketDataAttribute(current, attributes, "numberOfTrades");
            preserveMarketDataAttribute(current, attributes, "takerBuyBaseVolume");
            preserveMarketDataAttribute(current, attributes, "takerBuyQuoteVolume");
        }
        attributes.putAll(stringMap(event.getAttributes()));
        return Map.copyOf(attributes);
    }

    private void preserveMarketDataAttribute(
            MarketDataState current,
            Map<String, String> attributes,
            String key
    ) {
        String value = current.attributes().get(key);
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
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

    private ProjectionUpdate applyRemediationDecision(
            TradingEventEnvelope<?> envelope,
            RemediationDecisionEvent event
    ) {
        String eventId = value(event.getEventId());
        String remediationId = value(event.getRemediationId());
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                remediationId
        );
        RemediationDecisionState state = new RemediationDecisionState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                remediationId,
                value(event.getScope()),
                value(event.getAction()),
                value(event.getClientOrderId()),
                value(event.getPositionSide()),
                value(event.getInterventionReason()),
                stringList(event.getReasons()),
                value(event.getDecidedBy()),
                value(event.getDecisionReason()),
                stringMap(event.getAttributes()),
                event.getDecidedAtMicros(),
                eventId
        );
        synchronized (lock) {
            if (!rememberEventId(eventId)) {
                return ProjectionUpdate.duplicate(envelope.eventType(), entityKey, eventId);
            }
            RemediationDecisionState current = remediationDecisions.get(entityKey);
            if (current != null && state.updatedAt().isBefore(current.updatedAt())) {
                return ProjectionUpdate.stale(envelope.eventType(), entityKey, eventId);
            }
            remediationDecisions.put(entityKey, state);
            projectPauseGovernance(state);
            clearManualReviewDecision(state);
            return ProjectionUpdate.applied(envelope.eventType(), entityKey, eventId);
        }
    }

    private void projectPauseGovernance(RemediationDecisionState state) {
        String pauseScope = pauseScope(state.action());
        if (pauseScope == null) {
            return;
        }
        String pauseTarget = pauseTarget(pauseScope, state);
        if (pauseTarget == null) {
            return;
        }
        String key = key(state.provider(), state.environment(), state.account(), state.market(), pauseScope, pauseTarget);
        PauseGovernanceState current = pauseGovernance.get(key);
        if (current != null && state.updatedAt().isBefore(current.updatedAt())) {
            return;
        }
        boolean active = !pauseRelease(state.action());
        PauseGovernanceState pauseState = new PauseGovernanceState(
                state.provider(),
                state.environment(),
                state.account(),
                state.market(),
                pauseScope,
                pauseTarget,
                state.symbol(),
                state.remediationId(),
                state.scope(),
                state.action(),
                state.interventionReason(),
                state.reasons(),
                state.decidedBy(),
                state.decisionReason(),
                state.attributes(),
                active,
                state.updatedAt(),
                state.eventId()
        );
        pauseGovernance.put(key, pauseState);
    }

    private String pauseScope(String action) {
        return switch (action == null ? "" : action) {
            case "PAUSE_ACCOUNT" -> "ACCOUNT";
            case "PAUSE_SYMBOL" -> "SYMBOL";
            case "RELEASE_ACCOUNT_PAUSE" -> "ACCOUNT";
            case "RELEASE_SYMBOL_PAUSE" -> "SYMBOL";
            default -> null;
        };
    }

    private boolean pauseRelease(String action) {
        return switch (action == null ? "" : action) {
            case "RELEASE_ACCOUNT_PAUSE", "RELEASE_SYMBOL_PAUSE" -> true;
            default -> false;
        };
    }

    private String pauseTarget(String pauseScope, RemediationDecisionState state) {
        return switch (pauseScope) {
            case "ACCOUNT" -> state.account();
            case "SYMBOL" -> state.symbol();
            default -> null;
        };
    }

    private ProjectionUpdate applyStrategyLifecycle(
            TradingEventEnvelope<?> envelope,
            StrategyLifecycleEvent event
    ) {
        String eventId = value(event.getEventId());
        String entityKey = key(
                event.getStrategyId(),
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket()
        );
        StrategyLifecycleState state = new StrategyLifecycleState(
                value(event.getLifecycleId()),
                value(event.getStrategyId()),
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getPreviousLifecycleState()),
                value(event.getLifecycleState()),
                value(event.getChangedBy()),
                value(event.getReason()),
                stringMap(event.getAttributes()),
                event.getChangedAtMicros(),
                eventId
        );
        return applyState(envelope.eventType(), entityKey, eventId, state.updatedAt(), strategyLifecycle, state);
    }

    private void clearManualReviewDecision(RemediationDecisionState state) {
        if (!"MANUAL_REVIEW".equals(state.scope()) || !"OPERATOR_REVIEW".equals(state.action())) {
            return;
        }
        String commandId = state.attributes().get("command_id");
        if (commandId == null) {
            return;
        }
        String entityKey = key(state.provider(), state.environment(), state.account(), state.market(), commandId);
        ManualReviewDecisionState current = manualReviewDecisions.get(entityKey);
        if (current == null) {
            return;
        }
        if (state.updatedAt().isBefore(current.updatedAt())) {
            return;
        }
        manualReviewDecisions.remove(entityKey);
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
            boolean adoption = orderAdoptionAcknowledgement(event);
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
                    current.side(),
                    current.orderType(),
                    current.price(),
                    current.originalQuantity(),
                    current.executedQuantity(),
                    current.averagePrice(),
                    current.cumulativeQuote(),
                    adoption ? "INTERVENTION_ADOPTION" : "INTERVENTION_ACKNOWLEDGEMENT",
                    current.executionType(),
                    adoption || current.managedByBot(),
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
                    current.positionMode(),
                    current.positionAmount(),
                    current.entryPrice(),
                    current.markPrice(),
                    current.unrealizedPnl(),
                    current.leverage(),
                    current.marginType(),
                    current.isolatedMargin(),
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

    private boolean orderAdoptionAcknowledgement(InterventionAcknowledgementEvent event) {
        return "true".equalsIgnoreCase(acknowledgementAttribute(event, "adoption"))
                || "ADOPT_ORDER".equalsIgnoreCase(acknowledgementAttribute(event, "adoption_operation"));
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

    private String dailyRealizedPnlKey(
            CharSequence provider,
            CharSequence environment,
            CharSequence account,
            CharSequence market,
            CharSequence symbol,
            CharSequence tradingDay
    ) {
        return key(provider, environment, account, market, keyPart(symbol), tradingDay);
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

    private String attribute(OrderCommandEvent event, String key) {
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
        if (hasManagedFillAfterPositionState(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                current.updatedAt(),
                event.getEventTimeMicros()
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

    private boolean hasManagedFillAfterPositionState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            Instant currentPositionUpdatedAt,
            Instant positionEventTime
    ) {
        String prefix = key(provider, environment, account, market, symbol);
        return orders.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + "|"))
                .map(Map.Entry::getValue)
                .anyMatch(order -> managedFillExplainsPositionChange(
                        order,
                        currentPositionUpdatedAt,
                        positionEventTime
                ));
    }

    private boolean managedFillExplainsPositionChange(
            OrderState order,
            Instant currentPositionUpdatedAt,
            Instant positionEventTime
    ) {
        if (!Boolean.TRUE.equals(order.managedByBot())) {
            return false;
        }
        if (!"USER_DATA".equals(order.updateSource()) || !"TRADE".equals(order.executionType())) {
            return false;
        }
        if (!positiveAmount(order.executedQuantity())) {
            return false;
        }
        Instant fillUpdatedAt = order.updatedAt();
        if (fillUpdatedAt == null || currentPositionUpdatedAt == null || positionEventTime == null) {
            return false;
        }
        return fillUpdatedAt.isAfter(currentPositionUpdatedAt) && !fillUpdatedAt.isAfter(positionEventTime);
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

    private boolean positiveAmount(String amount) {
        if (amount == null) {
            return false;
        }
        try {
            return new BigDecimal(amount).compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean zeroAmount(String amount) {
        if (amount == null) {
            return false;
        }
        try {
            return new BigDecimal(amount).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String maxMarginBalance(String marginBalance, RiskState previous) {
        BigDecimal current = positiveDecimal(marginBalance);
        String previousMaxText = previous == null ? null : previous.maxMarginBalance();
        BigDecimal previousMax = positiveDecimal(previousMaxText);
        if (current == null) {
            return previousMaxText;
        }
        if (previousMax == null || current.compareTo(previousMax) > 0) {
            return marginBalance;
        }
        return previousMaxText;
    }

    private BigDecimal positiveDecimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value);
            return decimal.compareTo(BigDecimal.ZERO) > 0 ? decimal : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String tradingDay(Instant eventTime) {
        LocalDate day = eventTime.atZone(ZoneOffset.UTC).toLocalDate();
        return day.toString();
    }

    private String attribute(PositionUpdateEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private String positionMode(PositionUpdateEvent event) {
        String explicit = firstValue(
                attribute(event, "position_mode"),
                attribute(event, "positionMode"),
                attribute(event, "account_position_mode"),
                attribute(event, "accountPositionMode")
        );
        if (explicit != null) {
            return explicit.toUpperCase(Locale.ROOT);
        }
        String dualSidePosition = firstValue(attribute(event, "dualSidePosition"), attribute(event, "dual_side_position"));
        if (dualSidePosition == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(dualSidePosition)) {
            return "HEDGE";
        }
        if ("false".equalsIgnoreCase(dualSidePosition)) {
            return "ONE_WAY";
        }
        return null;
    }

    private String firstValue(String... values) {
        for (String candidate : values) {
            String normalized = value(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
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
            String positionMode,
            String positionAmount,
            String entryPrice,
            String markPrice,
            String unrealizedPnl,
            String leverage,
            String marginType,
            String isolatedMargin,
            String updateSource,
            Boolean externalIntervention,
            String interventionReason,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
        public PositionState(
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
                String leverage,
                String marginType,
                String isolatedMargin,
                String updateSource,
                Boolean externalIntervention,
                String interventionReason,
                Instant updatedAt,
                String eventId
        ) {
            this(
                    provider,
                    environment,
                    account,
                    market,
                    symbol,
                    positionSide,
                    null,
                    positionAmount,
                    entryPrice,
                    markPrice,
                    unrealizedPnl,
                    leverage,
                    marginType,
                    isolatedMargin,
                    updateSource,
                    externalIntervention,
                    interventionReason,
                    updatedAt,
                    eventId
            );
        }

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
            String side,
            String orderType,
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

        public OrderState(
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
        ) {
            this(
                    provider,
                    environment,
                    account,
                    market,
                    symbol,
                    commandId,
                    clientOrderId,
                    exchangeOrderId,
                    status,
                    exchangeStatus,
                    null,
                    null,
                    price,
                    originalQuantity,
                    executedQuantity,
                    averagePrice,
                    cumulativeQuote,
                    updateSource,
                    executionType,
                    managedByBot,
                    externalIntervention,
                    interventionReason,
                    updatedAt,
                    eventId
            );
        }

        public boolean unknownStatus() {
            return "UNKNOWN".equals(status);
        }

        public boolean unresolvedCommand() {
            return "COMMAND_RECEIVED".equals(status);
        }

        public boolean open() {
            return switch (status == null ? "" : status) {
                case "ACCEPTED", "PARTIALLY_FILLED" -> true;
                default -> false;
            };
        }
    }

    private record OrderIntervention(
            boolean managedByBot,
            boolean externalIntervention,
            String reason
    ) {
    }

    private record TopOfBook(
            String bestBidPrice,
            String bestBidQuantity,
            String bestAskPrice,
            String bestAskQuantity,
            Instant updatedAt
    ) {
    }

    private record LastTrade(
            String price,
            String quantity,
            String side,
            Instant updatedAt
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
            String maxMarginBalance,
            String maintenanceMargin,
            Instant updatedAt,
            String eventId
    ) implements TimedState {
    }

    public record MarketDataState(
            String provider,
            String environment,
            String market,
            String symbol,
            String eventType,
            String bestBidPrice,
            String bestBidQuantity,
            String bestAskPrice,
            String bestAskQuantity,
            Instant topOfBookUpdatedAt,
            String lastTradePrice,
            String lastTradeQuantity,
            String lastTradeSide,
            Instant lastTradeUpdatedAt,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) implements TimedState {

        public MarketDataState {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public boolean hasTopOfBook() {
            return bestBidPrice != null && bestAskPrice != null;
        }
    }

    public record DailyRealizedPnlState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String tradingDay,
            String realizedPnl,
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

    public record RemediationDecisionState(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String remediationId,
            String scope,
            String action,
            String clientOrderId,
            String positionSide,
            String interventionReason,
            List<String> reasons,
            String decidedBy,
            String decisionReason,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) implements TimedState {

        public RemediationDecisionState {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record PauseGovernanceState(
            String provider,
            String environment,
            String account,
            String market,
            String pauseScope,
            String pauseTarget,
            String symbol,
            String remediationId,
            String sourceScope,
            String action,
            String interventionReason,
            List<String> reasons,
            String decidedBy,
            String decisionReason,
            Map<String, String> attributes,
            Boolean active,
            Instant updatedAt,
            String eventId
    ) implements TimedState {

        public PauseGovernanceState {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            active = active == null || Boolean.TRUE.equals(active);
        }

        public Instant expiresAt() {
            return instantAttribute("pause_expires_at");
        }

        public boolean expired(Instant now) {
            Instant expiresAt = expiresAt();
            return expiresAt != null && now != null && !expiresAt.isAfter(now);
        }

        public boolean effectiveActive(Instant now) {
            return Boolean.TRUE.equals(active) && !expired(now);
        }

        private Instant instantAttribute(String key) {
            String value = attributes.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value.trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    public record StrategyLifecycleState(
            String lifecycleId,
            String strategyId,
            String provider,
            String environment,
            String account,
            String market,
            String previousLifecycleState,
            String lifecycleState,
            String changedBy,
            String reason,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) implements TimedState {

        public StrategyLifecycleState {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
