package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public final class OrderRiskGate {

    private static final String APPROVED_REASON = "risk_gate:approved";
    private static final String DISABLED_REASON = "risk_gate:disabled";
    private static final String NO_OBSERVATIONS_REASON = "reconciliation:no_observations";
    private static final String DEGRADED_REASON = "reconciliation:degraded";
    private static final String EXTERNAL_ORDER_INTERVENTION_REASON = "intervention:external_order";
    private static final String EXTERNAL_POSITION_INTERVENTION_REASON = "intervention:external_position";
    private static final String UNKNOWN_ORDER_STATUS_REASON = "order_status:unknown";
    private static final String UNRESOLVED_ORDER_COMMAND_REASON = "order_command:unresolved";
    private static final String PROJECTED_DUPLICATE_COMMAND_ID_REASON = "execution:projected_duplicate_command_id";
    private static final String PROJECTED_DUPLICATE_CLIENT_ORDER_ID_REASON =
            "execution:projected_duplicate_client_order_id";
    private static final String INVALID_NUMERIC_FIELD_REASON = "order_limit:invalid_numeric";
    private static final String NON_POSITIVE_QUANTITY_REASON = "order_limit:non_positive_quantity";
    private static final String NON_POSITIVE_QUOTE_QUANTITY_REASON = "order_limit:non_positive_quote_order_quantity";
    private static final String NON_POSITIVE_PRICE_REASON = "order_limit:non_positive_price";
    private static final String MAX_QUANTITY_REASON = "order_limit:max_quantity";
    private static final String MAX_NOTIONAL_REASON = "order_limit:max_notional";
    private static final String UNBOUNDED_NOTIONAL_REASON = "order_limit:unbounded_notional";
    private static final String MISSING_TARGET_CLIENT_ORDER_ID_REASON = "order_target:missing_client_order_id";
    private static final String MISSING_TARGET_ORDER_ID_REASON = "order_target:missing_order_id";
    private static final String PROJECTED_TARGET_MISSING_REASON = "order_target:not_projected";
    private static final String PROJECTED_TARGET_IDENTITY_MISMATCH_REASON = "order_target:identity_mismatch";
    private static final String PROJECTED_TARGET_UNMANAGED_REASON = "order_target:not_managed";
    private static final String PROJECTED_TARGET_CLOSED_REASON = "order_target:closed";
    private static final String PROJECTED_TARGET_EXTERNAL_INTERVENTION_REASON = "order_target:external_intervention";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ExecutionProperties properties;
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker;
    private final TradingStateProjection tradingStateProjection;
    private final Clock clock;

    @Autowired
    public OrderRiskGate(
            ExecutionProperties properties,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            TradingStateProjection tradingStateProjection
    ) {
        this(properties, reconciliationConfidenceTracker, tradingStateProjection, Clock.systemUTC());
    }

    OrderRiskGate(
            ExecutionProperties properties,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            Clock clock
    ) {
        this(properties, reconciliationConfidenceTracker, new TradingStateProjection(), clock);
    }

    OrderRiskGate(
            ExecutionProperties properties,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            TradingStateProjection tradingStateProjection,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.reconciliationConfidenceTracker = Objects.requireNonNull(
                reconciliationConfidenceTracker,
                "reconciliationConfidenceTracker"
        );
        this.tradingStateProjection = Objects.requireNonNull(tradingStateProjection, "tradingStateProjection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TradingEventEnvelope<RiskDecisionEvent> evaluate(TradingEventEnvelope<OrderCommandEvent> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.ORDER_COMMAND) {
            throw new IllegalArgumentException("Expected ORDER_COMMAND envelope");
        }
        OrderCommandEvent command = envelope.value();
        RiskDecisionEvent decision = evaluate(command);
        return TradingEventEnvelope.of(
                TradingEventType.RISK_DECISION,
                TradingEventKeys.symbol(
                        TradingEventType.RISK_DECISION,
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket()),
                        value(command.getSymbol())
                ),
                decision
        );
    }

    public RiskDecisionEvent evaluate(OrderCommandEvent command) {
        Objects.requireNonNull(command, "command");
        ReconciliationTargetConfidence reconciliationConfidence = reconciliationConfidence(command);
        RiskDecision decision = RiskDecision.APPROVED;
        List<String> reasons = new ArrayList<>();
        if (!properties.riskGate().enabled()) {
            reasons.add(DISABLED_REASON);
        } else {
            List<String> reconciliationReasons =
                    reconciliationReasons(properties.riskGate().reconciliation(), reconciliationConfidence);
            ManualInterventionDecision manualInterventionDecision =
                    manualInterventionDecision(properties.riskGate().manualIntervention(), command);
            ManualInterventionDecision unknownOrderStatusDecision =
                    unknownOrderStatusDecision(properties.riskGate().unknownOrderStatus(), command);
            ManualInterventionDecision pendingOrderCommandDecision =
                    pendingOrderCommandDecision(properties.riskGate().pendingOrderCommand(), command);
            ProjectionIdempotencyDecision projectionIdempotencyDecision = projectionIdempotencyDecision(command);
            OrderLimitDecision orderLimitDecision = orderLimitDecision(properties.riskGate().orderLimit(), command);
            TargetOrderDecision targetOrderDecision = targetOrderDecision(properties.riskGate().targetOrder(), command);
            reasons.addAll(reconciliationReasons);
            reasons.addAll(manualInterventionDecision.reasons());
            reasons.addAll(unknownOrderStatusDecision.reasons());
            reasons.addAll(pendingOrderCommandDecision.reasons());
            reasons.addAll(projectionIdempotencyDecision.reasons());
            reasons.addAll(orderLimitDecision.reasons());
            reasons.addAll(targetOrderDecision.reasons());
            if (!reconciliationReasons.isEmpty()
                    || manualInterventionDecision.reject()
                    || unknownOrderStatusDecision.reject()
                    || pendingOrderCommandDecision.reject()
                    || projectionIdempotencyDecision.reject()
                    || orderLimitDecision.reject()
                    || targetOrderDecision.reject()) {
                decision = RiskDecision.REJECTED;
            } else if (manualInterventionDecision.manualReview()
                    || unknownOrderStatusDecision.manualReview()
                    || pendingOrderCommandDecision.manualReview()
                    || orderLimitDecision.manualReview()
                    || targetOrderDecision.manualReview()) {
                decision = RiskDecision.MANUAL_REVIEW;
            } else {
                reasons.add(APPROVED_REASON);
            }
        }
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
                .setDecision(decision)
                .setReasons(List.copyOf(reasons))
                .setMaxQuantity(decision == RiskDecision.APPROVED ? approvedMaxQuantity(command) : null)
                .setMaxNotional(null)
                .setDecidedAtMicros(Instant.now(clock))
                .setAttributes(attributes(command, reconciliationConfidence))
                .build();
    }

    private List<String> reconciliationReasons(
            ExecutionProperties.Reconciliation reconciliation,
            ReconciliationTargetConfidence confidence
    ) {
        if (!reconciliation.required()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        if (confidence.status() == ReconciliationTargetConfidence.Status.NO_OBSERVATIONS
                && reconciliation.rejectNoObservations()) {
            reasons.add(NO_OBSERVATIONS_REASON);
        }
        if (confidence.status() == ReconciliationTargetConfidence.Status.DEGRADED
                && reconciliation.rejectDegraded()) {
            reasons.add(DEGRADED_REASON);
        }
        return List.copyOf(reasons);
    }

    private ManualInterventionDecision manualInterventionDecision(
            ExecutionProperties.ManualIntervention manualIntervention,
            OrderCommandEvent command
    ) {
        List<String> reasons = new ArrayList<>();
        boolean reject = false;
        boolean manualReview = false;
        boolean targetCommand = targetCommand(command);
        if ((!targetCommand || manualIntervention.externalOrderApplyToTargetCommands())
                && tradingStateProjection.hasExternalOrderInterventions(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        )) {
            ManualInterventionDecision decision = decisionFor(
                    manualIntervention.externalOrderAction(),
                    EXTERNAL_ORDER_INTERVENTION_REASON
            );
            reasons.addAll(decision.reasons());
            reject = decision.reject();
            manualReview = decision.manualReview();
        }
        if ((!targetCommand || manualIntervention.externalPositionApplyToTargetCommands())
                && tradingStateProjection.hasExternalPositionInterventions(
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket())
                )) {
            ManualInterventionDecision decision = decisionFor(
                    manualIntervention.externalPositionAction(),
                    EXTERNAL_POSITION_INTERVENTION_REASON
            );
            reasons.addAll(decision.reasons());
            reject = reject || decision.reject();
            manualReview = manualReview || decision.manualReview();
        }
        return new ManualInterventionDecision(List.copyOf(reasons), reject, manualReview);
    }

    private ManualInterventionDecision unknownOrderStatusDecision(
            ExecutionProperties.UnknownOrderStatus unknownOrderStatus,
            OrderCommandEvent command
    ) {
        if (targetCommand(command) && !unknownOrderStatus.applyToTargetCommands()) {
            return new ManualInterventionDecision(List.of(), false, false);
        }
        if (!tradingStateProjection.hasUnknownOrderStatuses(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        )) {
            return new ManualInterventionDecision(List.of(), false, false);
        }
        return decisionFor(unknownOrderStatus.action(), UNKNOWN_ORDER_STATUS_REASON);
    }

    private ManualInterventionDecision pendingOrderCommandDecision(
            ExecutionProperties.PendingOrderCommand pendingOrderCommand,
            OrderCommandEvent command
    ) {
        if (targetCommand(command) && !pendingOrderCommand.applyToTargetCommands()) {
            return new ManualInterventionDecision(List.of(), false, false);
        }
        if (!tradingStateProjection.hasUnresolvedOrderCommands(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        )) {
            return new ManualInterventionDecision(List.of(), false, false);
        }
        return decisionFor(pendingOrderCommand.action(), UNRESOLVED_ORDER_COMMAND_REASON);
    }

    private ProjectionIdempotencyDecision projectionIdempotencyDecision(OrderCommandEvent command) {
        if (!properties.idempotency().enabled() || !properties.idempotency().rejectProjectedDuplicates()) {
            return ProjectionIdempotencyDecision.none();
        }
        List<TradingStateProjection.OrderState> commandMatches = tradingStateProjection.ordersByCommandId(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket()),
                value(command.getCommandId())
        );
        if (!commandMatches.isEmpty()) {
            TradingStateProjection.OrderState match = commandMatches.getFirst();
            return ProjectionIdempotencyDecision.reject(
                    PROJECTED_DUPLICATE_COMMAND_ID_REASON,
                    duplicateAttributes(
                            "projected_duplicate_command_id",
                            value(command.getCommandId()),
                            "projected_duplicate_client_order_id",
                            match.clientOrderId()
                    )
            );
        }
        Optional<TradingStateProjection.OrderState> clientOrderMatch = tradingStateProjection.order(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket()),
                value(command.getSymbol()),
                value(command.getClientOrderId())
        );
        if (clientOrderMatch.isPresent()) {
            TradingStateProjection.OrderState match = clientOrderMatch.orElseThrow();
            return ProjectionIdempotencyDecision.reject(
                    PROJECTED_DUPLICATE_CLIENT_ORDER_ID_REASON,
                    duplicateAttributes(
                            "projected_duplicate_client_order_id",
                            value(command.getClientOrderId()),
                            "projected_duplicate_command_id",
                            match.commandId()
                    )
            );
        }
        return ProjectionIdempotencyDecision.none();
    }

    private OrderLimitDecision orderLimitDecision(
            ExecutionProperties.OrderLimit orderLimit,
            OrderCommandEvent command
    ) {
        if (!orderLimit.enabled()) {
            return OrderLimitDecision.none();
        }
        if (action(command) == OrderCommandAction.CANCEL) {
            return OrderLimitDecision.none();
        }
        EffectiveOrderLimit effectiveLimit = effectiveOrderLimit(orderLimit, command);
        List<String> reasons = new ArrayList<>();
        boolean reject = false;
        boolean manualReview = false;
        DecimalField quantity = decimalField("quantity", command.getQuantity());
        DecimalField quoteOrderQuantity = decimalField("quoteOrderQuantity", command.getQuoteOrderQuantity());
        DecimalField price = decimalField("price", command.getPrice());
        DecimalField stopPrice = decimalField("stopPrice", command.getStopPrice());
        DecimalField activationPrice = decimalField("activationPrice", command.getActivationPrice());
        DecimalField callbackRate = decimalField("callbackRate", command.getCallbackRate());
        List<DecimalField> fields = List.of(
                quantity,
                quoteOrderQuantity,
                price,
                stopPrice,
                activationPrice,
                callbackRate
        );
        if (orderLimit.rejectInvalidNumericFields() && fields.stream().anyMatch(field -> field.invalid())) {
            reasons.add(INVALID_NUMERIC_FIELD_REASON);
            reject = true;
        }
        if (isNonPositive(quantity)) {
            reasons.add(NON_POSITIVE_QUANTITY_REASON);
            reject = true;
        }
        if (isNonPositive(quoteOrderQuantity)) {
            reasons.add(NON_POSITIVE_QUOTE_QUANTITY_REASON);
            reject = true;
        }
        if (List.of(price, stopPrice, activationPrice, callbackRate).stream().anyMatch(this::isNonPositive)) {
            reasons.add(NON_POSITIVE_PRICE_REASON);
            reject = true;
        }

        BigDecimal maxQuantity = decimal(effectiveLimit.maxQuantity());
        if (maxQuantity != null && quantity.value() != null && quantity.value().compareTo(maxQuantity) > 0) {
            ManualInterventionDecision decision = decisionFor(effectiveLimit.action(), MAX_QUANTITY_REASON);
            reasons.addAll(decision.reasons());
            reject = reject || decision.reject();
            manualReview = manualReview || decision.manualReview();
        }

        BigDecimal maxNotional = decimal(effectiveLimit.maxNotional());
        if (maxNotional != null) {
            BigDecimal notional = notional(quantity.value(), quoteOrderQuantity.value(), price.value());
            if (notional == null && effectiveLimit.rejectUnboundedNotional()) {
                ManualInterventionDecision decision = decisionFor(effectiveLimit.action(), UNBOUNDED_NOTIONAL_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            } else if (notional != null && notional.compareTo(maxNotional) > 0) {
                ManualInterventionDecision decision = decisionFor(effectiveLimit.action(), MAX_NOTIONAL_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            }
        }
        return new OrderLimitDecision(List.copyOf(reasons), reject, manualReview);
    }

    private TargetOrderDecision targetOrderDecision(
            ExecutionProperties.TargetOrder targetOrder,
            OrderCommandEvent command
    ) {
        if (!targetOrder.enabled() || action(command) == OrderCommandAction.NEW) {
            return TargetOrderDecision.none();
        }
        List<String> reasons = new ArrayList<>();
        boolean reject = false;
        boolean manualReview = false;
        String targetClientOrderId = targetClientOrderId(command);
        String targetExchangeOrderId = targetExchangeOrderId(command);
        if (targetOrder.requireTargetOrderId() && targetClientOrderId == null && targetExchangeOrderId == null) {
            ManualInterventionDecision decision = decisionFor(targetOrder.action(), MISSING_TARGET_ORDER_ID_REASON);
            reasons.addAll(decision.reasons());
            reject = reject || decision.reject();
            manualReview = manualReview || decision.manualReview();
            return new TargetOrderDecision(List.copyOf(reasons), reject, manualReview);
        }
        if (targetOrder.requireTargetClientOrderId() && targetClientOrderId == null) {
            ManualInterventionDecision decision = decisionFor(targetOrder.action(), MISSING_TARGET_CLIENT_ORDER_ID_REASON);
            reasons.addAll(decision.reasons());
            reject = reject || decision.reject();
            manualReview = manualReview || decision.manualReview();
            return new TargetOrderDecision(List.copyOf(reasons), reject, manualReview);
        }

        Optional<TradingStateProjection.OrderState> target = projectedTarget(command, targetClientOrderId, targetExchangeOrderId);
        if (targetOrder.requireProjectedTarget() && target.isEmpty()) {
            ManualInterventionDecision decision = decisionFor(targetOrder.action(), PROJECTED_TARGET_MISSING_REASON);
            reasons.addAll(decision.reasons());
            reject = reject || decision.reject();
            manualReview = manualReview || decision.manualReview();
            return new TargetOrderDecision(List.copyOf(reasons), reject, manualReview);
        }
        if (target.isPresent()) {
            TradingStateProjection.OrderState state = target.orElseThrow();
            if (targetIdentityMismatch(state, targetClientOrderId, targetExchangeOrderId)) {
                ManualInterventionDecision decision =
                        decisionFor(targetOrder.action(), PROJECTED_TARGET_IDENTITY_MISMATCH_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            }
            if (targetOrder.requireManagedTarget() && !state.managedByBot()) {
                ManualInterventionDecision decision = decisionFor(targetOrder.action(), PROJECTED_TARGET_UNMANAGED_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            }
            if (targetOrder.rejectClosedTarget() && closedOrderStatus(state.status())) {
                ManualInterventionDecision decision = decisionFor(targetOrder.action(), PROJECTED_TARGET_CLOSED_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            }
            if (targetOrder.rejectExternalIntervention() && state.externalIntervention()) {
                ManualInterventionDecision decision =
                        decisionFor(targetOrder.action(), PROJECTED_TARGET_EXTERNAL_INTERVENTION_REASON);
                reasons.addAll(decision.reasons());
                reject = reject || decision.reject();
                manualReview = manualReview || decision.manualReview();
            }
        }
        return new TargetOrderDecision(List.copyOf(reasons), reject, manualReview);
    }

    private boolean targetIdentityMismatch(
            TradingStateProjection.OrderState state,
            String targetClientOrderId,
            String targetExchangeOrderId
    ) {
        String projectedClientOrderId = value(state.clientOrderId());
        String projectedExchangeOrderId = value(state.exchangeOrderId());
        return targetClientOrderId != null && projectedClientOrderId != null && !targetClientOrderId.equals(projectedClientOrderId)
                || targetExchangeOrderId != null
                && projectedExchangeOrderId != null
                && !targetExchangeOrderId.equals(projectedExchangeOrderId);
    }

    private Optional<TradingStateProjection.OrderState> projectedTarget(
            OrderCommandEvent command,
            String targetClientOrderId,
            String targetExchangeOrderId
    ) {
        if (targetClientOrderId != null) {
            Optional<TradingStateProjection.OrderState> target = tradingStateProjection.order(
                    value(command.getProvider()),
                    value(command.getEnvironment()),
                    value(command.getAccount()),
                    value(command.getMarket()),
                    value(command.getSymbol()),
                    targetClientOrderId
            );
            if (target.isPresent()) {
                return target;
            }
        }
        if (targetExchangeOrderId == null) {
            return Optional.empty();
        }
        return tradingStateProjection.orderByExchangeOrderId(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket()),
                value(command.getSymbol()),
                targetExchangeOrderId
        );
    }

    private OrderCommandAction action(OrderCommandEvent command) {
        return command.getAction() == null ? OrderCommandAction.NEW : command.getAction();
    }

    private boolean targetCommand(OrderCommandEvent command) {
        return action(command) != OrderCommandAction.NEW;
    }

    private String approvedMaxQuantity(OrderCommandEvent command) {
        return action(command) == OrderCommandAction.CANCEL ? null : value(command.getQuantity());
    }

    private boolean closedOrderStatus(String status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case "CANCELED", "EXPIRED", "FILLED", "REJECTED" -> true;
            default -> false;
        };
    }

    private EffectiveOrderLimit effectiveOrderLimit(
            ExecutionProperties.OrderLimit orderLimit,
            OrderCommandEvent command
    ) {
        ExecutionProperties.OrderLimit.TargetLimit selected = null;
        int selectedSpecificity = -1;
        for (ExecutionProperties.OrderLimit.TargetLimit candidate : orderLimit.targetLimits()) {
            int specificity = specificity(candidate, command);
            if (specificity > selectedSpecificity) {
                selected = candidate;
                selectedSpecificity = specificity;
            }
        }
        if (selected == null) {
            return new EffectiveOrderLimit(
                    orderLimit.maxQuantity(),
                    orderLimit.maxNotional(),
                    orderLimit.rejectUnboundedNotional(),
                    orderLimit.action(),
                    "global"
            );
        }
        return new EffectiveOrderLimit(
                selected.maxQuantity() == null ? orderLimit.maxQuantity() : selected.maxQuantity(),
                selected.maxNotional() == null ? orderLimit.maxNotional() : selected.maxNotional(),
                selected.rejectUnboundedNotional() == null
                        ? orderLimit.rejectUnboundedNotional()
                        : selected.rejectUnboundedNotional(),
                selected.action() == null ? orderLimit.action() : selected.action(),
                targetLimitScope(selected)
        );
    }

    private int specificity(ExecutionProperties.OrderLimit.TargetLimit candidate, OrderCommandEvent command) {
        int specificity = 0;
        specificity = match(candidate.provider(), value(command.getProvider()), specificity);
        specificity = match(candidate.environment(), value(command.getEnvironment()), specificity);
        specificity = match(candidate.account(), value(command.getAccount()), specificity);
        specificity = match(candidate.market(), value(command.getMarket()), specificity);
        specificity = match(candidate.symbol(), value(command.getSymbol()), specificity);
        return specificity;
    }

    private int match(String expected, String actual, int specificity) {
        if (specificity < 0) {
            return -1;
        }
        if (expected == null || expected.isBlank()) {
            return specificity;
        }
        if (actual != null && expected.equalsIgnoreCase(actual)) {
            return specificity + 1;
        }
        return -1;
    }

    private Map<CharSequence, CharSequence> duplicateAttributes(
            String firstKey,
            String firstValue,
            String secondKey,
            String secondValue
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (firstValue != null) {
            attributes.put(firstKey, firstValue);
        }
        if (secondValue != null) {
            attributes.put(secondKey, secondValue);
        }
        return Map.copyOf(attributes);
    }

    private ManualInterventionDecision decisionFor(
            ExecutionProperties.InterventionAction action,
            String reason
    ) {
        return switch (action) {
            case ALLOW_NEW_COMMANDS -> new ManualInterventionDecision(List.of(), false, false);
            case REJECT_NEW_COMMANDS -> new ManualInterventionDecision(List.of(reason), true, false);
            case MANUAL_REVIEW -> new ManualInterventionDecision(List.of(reason), false, true);
        };
    }

    private ReconciliationTargetConfidence reconciliationConfidence(OrderCommandEvent command) {
        return reconciliationConfidenceTracker.targetConfidence(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
    }

    private Map<CharSequence, CharSequence> attributes(
            OrderCommandEvent command,
            ReconciliationTargetConfidence confidence
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("reconciliation_status", confidence.status().name());
        attributes.put("reconciliation_observed_states", Integer.toString(confidence.observedStates()));
        attributes.put("reconciliation_degraded_states", Integer.toString(confidence.degradedStates()));
        if (confidence.observedAt() != null) {
            attributes.put("reconciliation_observed_at", confidence.observedAt().toString());
        }
        long interventions = tradingStateProjection.externalOrderInterventions(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("external_order_interventions", Long.toString(interventions));
        long positionInterventions = tradingStateProjection.externalPositionInterventions(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("external_position_interventions", Long.toString(positionInterventions));
        List<TradingStateProjection.OrderState> unknownOrderStatusStates = tradingStateProjection.unknownOrderStatusStates(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("unknown_order_statuses", Integer.toString(unknownOrderStatusStates.size()));
        putOrderIdentities(attributes, "unknown_order", unknownOrderStatusStates);
        List<TradingStateProjection.OrderState> unresolvedOrderCommandStates = tradingStateProjection.unresolvedOrderCommandStates(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("unresolved_order_commands", Integer.toString(unresolvedOrderCommandStates.size()));
        putOrderIdentities(attributes, "unresolved_order", unresolvedOrderCommandStates);
        attributes.put(
                "external_order_intervention_action",
                properties.riskGate().manualIntervention().externalOrderAction().name()
        );
        attributes.put(
                "external_order_intervention_apply_to_target_commands",
                Boolean.toString(properties.riskGate().manualIntervention().externalOrderApplyToTargetCommands())
        );
        attributes.put(
                "external_position_intervention_action",
                properties.riskGate().manualIntervention().externalPositionAction().name()
        );
        attributes.put(
                "external_position_intervention_apply_to_target_commands",
                Boolean.toString(properties.riskGate().manualIntervention().externalPositionApplyToTargetCommands())
        );
        attributes.put(
                "unknown_order_status_action",
                properties.riskGate().unknownOrderStatus().action().name()
        );
        attributes.put(
                "unknown_order_status_apply_to_target_commands",
                Boolean.toString(properties.riskGate().unknownOrderStatus().applyToTargetCommands())
        );
        attributes.put(
                "pending_order_command_action",
                properties.riskGate().pendingOrderCommand().action().name()
        );
        attributes.put(
                "pending_order_command_apply_to_target_commands",
                Boolean.toString(properties.riskGate().pendingOrderCommand().applyToTargetCommands())
        );
        attributes.put(
                "projected_idempotency_reject_duplicates",
                Boolean.toString(properties.idempotency().rejectProjectedDuplicates())
        );
        attributes.putAll(projectionIdempotencyDecision(command).attributes());
        attributes.putAll(orderLimitAttributes(command));
        attributes.putAll(targetOrderAttributes(command));
        return Map.copyOf(attributes);
    }

    private void putOrderIdentities(
            Map<CharSequence, CharSequence> attributes,
            String prefix,
            List<TradingStateProjection.OrderState> states
    ) {
        putIfPresent(attributes, prefix + "_command_ids", joined(states.stream()
                .map(TradingStateProjection.OrderState::commandId)
                .toList()));
        putIfPresent(attributes, prefix + "_client_order_ids", joined(states.stream()
                .map(TradingStateProjection.OrderState::clientOrderId)
                .toList()));
        putIfPresent(attributes, prefix + "_exchange_order_ids", joined(states.stream()
                .map(TradingStateProjection.OrderState::exchangeOrderId)
                .toList()));
    }

    private String joined(List<String> values) {
        List<String> normalized = values.stream()
                .map(this::value)
                .filter(Objects::nonNull)
                .toList();
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private Map<CharSequence, CharSequence> orderLimitAttributes(OrderCommandEvent command) {
        ExecutionProperties.OrderLimit orderLimit = properties.riskGate().orderLimit();
        EffectiveOrderLimit effectiveLimit = effectiveOrderLimit(orderLimit, command);
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("order_limit_enabled", Boolean.toString(orderLimit.enabled()));
        attributes.put("order_limit_reject_invalid_numeric_fields", Boolean.toString(orderLimit.rejectInvalidNumericFields()));
        attributes.put("order_limit_reject_unbounded_notional", Boolean.toString(effectiveLimit.rejectUnboundedNotional()));
        attributes.put("order_limit_action", effectiveLimit.action().name());
        attributes.put("order_limit_scope", effectiveLimit.scope());
        if (effectiveLimit.maxQuantity() != null) {
            attributes.put("order_limit_max_quantity", effectiveLimit.maxQuantity());
        }
        if (effectiveLimit.maxNotional() != null) {
            attributes.put("order_limit_max_notional", effectiveLimit.maxNotional());
        }
        BigDecimal computedNotional = notional(
                decimal(command.getQuantity()),
                decimal(command.getQuoteOrderQuantity()),
                decimal(command.getPrice())
        );
        if (computedNotional != null) {
            attributes.put("order_limit_computed_notional", decimalText(computedNotional));
        }
        return Map.copyOf(attributes);
    }

    private Map<CharSequence, CharSequence> targetOrderAttributes(OrderCommandEvent command) {
        ExecutionProperties.TargetOrder targetOrder = properties.riskGate().targetOrder();
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("target_order_policy_enabled", Boolean.toString(targetOrder.enabled()));
        attributes.put("target_order_action", targetOrder.action().name());
        attributes.put("target_order_require_order_id", Boolean.toString(targetOrder.requireTargetOrderId()));
        attributes.put("target_order_require_client_order_id", Boolean.toString(targetOrder.requireTargetClientOrderId()));
        attributes.put("target_order_require_projection", Boolean.toString(targetOrder.requireProjectedTarget()));
        attributes.put("target_order_require_managed", Boolean.toString(targetOrder.requireManagedTarget()));
        attributes.put("target_order_reject_closed", Boolean.toString(targetOrder.rejectClosedTarget()));
        attributes.put("target_order_reject_external_intervention", Boolean.toString(targetOrder.rejectExternalIntervention()));
        String targetClientOrderId = targetClientOrderId(command);
        String targetExchangeOrderId = targetExchangeOrderId(command);
        if (targetClientOrderId != null || targetExchangeOrderId != null) {
            putIfPresent(attributes, "target_client_order_id", targetClientOrderId);
            putIfPresent(attributes, "target_exchange_order_id", targetExchangeOrderId);
            projectedTarget(command, targetClientOrderId, targetExchangeOrderId).ifPresent(target -> {
                putIfPresent(attributes, "target_projected_client_order_id", target.clientOrderId());
                putIfPresent(attributes, "target_projected_exchange_order_id", target.exchangeOrderId());
                putIfPresent(attributes, "target_order_status", target.status());
                putIfPresent(attributes, "target_order_exchange_status", target.exchangeStatus());
                attributes.put("target_order_managed_by_bot", Boolean.toString(target.managedByBot()));
                attributes.put("target_order_external_intervention", Boolean.toString(target.externalIntervention()));
            });
        }
        return Map.copyOf(attributes);
    }

    private void putIfPresent(Map<CharSequence, CharSequence> attributes, String name, String value) {
        String normalized = value(value);
        if (normalized != null) {
            attributes.put(name, normalized);
        }
    }

    private String targetLimitScope(ExecutionProperties.OrderLimit.TargetLimit targetLimit) {
        return String.join(
                "|",
                valueOrWildcard(targetLimit.provider()),
                valueOrWildcard(targetLimit.environment()),
                valueOrWildcard(targetLimit.account()),
                valueOrWildcard(targetLimit.market()),
                valueOrWildcard(targetLimit.symbol())
        );
    }

    private String valueOrWildcard(String value) {
        return value == null || value.isBlank() ? "*" : value;
    }

    private DecimalField decimalField(String name, CharSequence raw) {
        String value = value(raw);
        if (value == null) {
            return new DecimalField(name, null, false);
        }
        try {
            return new DecimalField(name, new BigDecimal(value), false);
        } catch (NumberFormatException e) {
            return new DecimalField(name, null, true);
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimal(CharSequence value) {
        return decimal(value(value));
    }

    private boolean isNonPositive(DecimalField field) {
        return field.value() != null && field.value().compareTo(ZERO) <= 0;
    }

    private BigDecimal notional(BigDecimal quantity, BigDecimal quoteOrderQuantity, BigDecimal price) {
        if (quoteOrderQuantity != null && quoteOrderQuantity.compareTo(ZERO) > 0) {
            return quoteOrderQuantity;
        }
        if (quantity != null
                && price != null
                && quantity.compareTo(ZERO) > 0
                && price.compareTo(ZERO) > 0) {
            return quantity.multiply(price);
        }
        return null;
    }

    private String decimalText(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record ManualInterventionDecision(
            List<String> reasons,
            boolean reject,
            boolean manualReview
    ) {
    }

    private record ProjectionIdempotencyDecision(
            List<String> reasons,
            boolean reject,
            Map<CharSequence, CharSequence> attributes
    ) {

        private static ProjectionIdempotencyDecision none() {
            return new ProjectionIdempotencyDecision(List.of(), false, Map.of());
        }

        private static ProjectionIdempotencyDecision reject(
                String reason,
                Map<CharSequence, CharSequence> attributes
        ) {
            return new ProjectionIdempotencyDecision(List.of(reason), true, attributes);
        }
    }

    private record OrderLimitDecision(
            List<String> reasons,
            boolean reject,
            boolean manualReview
    ) {

        private static OrderLimitDecision none() {
            return new OrderLimitDecision(List.of(), false, false);
        }
    }

    private record TargetOrderDecision(
            List<String> reasons,
            boolean reject,
            boolean manualReview
    ) {

        private static TargetOrderDecision none() {
            return new TargetOrderDecision(List.of(), false, false);
        }
    }

    private record DecimalField(String name, BigDecimal value, boolean invalid) {
    }

    private record EffectiveOrderLimit(
            String maxQuantity,
            String maxNotional,
            Boolean rejectUnboundedNotional,
            ExecutionProperties.InterventionAction action,
            String scope
    ) {
    }

    private String signalId(OrderCommandEvent command) {
        if (command.getAttributes() == null) {
            return null;
        }
        return value(command.getAttributes().get("signal_id"));
    }

    private String targetClientOrderId(OrderCommandEvent command) {
        String target = value(command.getTargetClientOrderId());
        if (target != null) {
            return target;
        }
        return attribute(command, "target_client_order_id");
    }

    private String targetExchangeOrderId(OrderCommandEvent command) {
        String target = value(command.getTargetExchangeOrderId());
        if (target != null) {
            return target;
        }
        return attribute(command, "target_exchange_order_id");
    }

    private String attribute(OrderCommandEvent command, String... names) {
        if (command.getAttributes() == null) {
            return null;
        }
        for (String name : names) {
            String candidate = value(command.getAttributes().get(name));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
