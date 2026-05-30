package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
            reasons.addAll(reconciliationReasons);
            reasons.addAll(manualInterventionDecision.reasons());
            reasons.addAll(unknownOrderStatusDecision.reasons());
            reasons.addAll(pendingOrderCommandDecision.reasons());
            reasons.addAll(projectionIdempotencyDecision.reasons());
            if (!reconciliationReasons.isEmpty()
                    || manualInterventionDecision.reject()
                    || unknownOrderStatusDecision.reject()
                    || pendingOrderCommandDecision.reject()
                    || projectionIdempotencyDecision.reject()) {
                decision = RiskDecision.REJECTED;
            } else if (manualInterventionDecision.manualReview()
                    || unknownOrderStatusDecision.manualReview()
                    || pendingOrderCommandDecision.manualReview()) {
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
                .setMaxQuantity(decision == RiskDecision.APPROVED ? value(command.getQuantity()) : null)
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
        if (tradingStateProjection.hasExternalOrderInterventions(
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
        if (tradingStateProjection.hasExternalPositionInterventions(
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
        long unknownOrderStatuses = tradingStateProjection.unknownOrderStatuses(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("unknown_order_statuses", Long.toString(unknownOrderStatuses));
        long unresolvedOrderCommands = tradingStateProjection.unresolvedOrderCommands(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        );
        attributes.put("unresolved_order_commands", Long.toString(unresolvedOrderCommands));
        attributes.put(
                "external_order_intervention_action",
                properties.riskGate().manualIntervention().externalOrderAction().name()
        );
        attributes.put(
                "external_position_intervention_action",
                properties.riskGate().manualIntervention().externalPositionAction().name()
        );
        attributes.put(
                "unknown_order_status_action",
                properties.riskGate().unknownOrderStatus().action().name()
        );
        attributes.put(
                "pending_order_command_action",
                properties.riskGate().pendingOrderCommand().action().name()
        );
        attributes.put(
                "projected_idempotency_reject_duplicates",
                Boolean.toString(properties.idempotency().rejectProjectedDuplicates())
        );
        attributes.putAll(projectionIdempotencyDecision(command).attributes());
        return Map.copyOf(attributes);
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

    private String signalId(OrderCommandEvent command) {
        if (command.getAttributes() == null) {
            return null;
        }
        return value(command.getAttributes().get("signal_id"));
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
