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

@Component
public final class OrderRiskGate {

    private static final String APPROVED_REASON = "risk_gate:approved";
    private static final String DISABLED_REASON = "risk_gate:disabled";
    private static final String NO_OBSERVATIONS_REASON = "reconciliation:no_observations";
    private static final String DEGRADED_REASON = "reconciliation:degraded";
    private static final String EXTERNAL_ORDER_INTERVENTION_REASON = "intervention:external_order";
    private static final String EXTERNAL_POSITION_INTERVENTION_REASON = "intervention:external_position";

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
            reasons.addAll(reconciliationReasons(properties.riskGate().reconciliation(), reconciliationConfidence));
            reasons.addAll(manualInterventionReasons(properties.riskGate().manualIntervention(), command));
            if (!reasons.isEmpty()) {
                decision = RiskDecision.REJECTED;
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

    private List<String> manualInterventionReasons(
            ExecutionProperties.ManualIntervention manualIntervention,
            OrderCommandEvent command
    ) {
        List<String> reasons = new ArrayList<>();
        if (manualIntervention.rejectExternalOrderInterventions()
                && tradingStateProjection.hasExternalOrderInterventions(
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket())
                )) {
            reasons.add(EXTERNAL_ORDER_INTERVENTION_REASON);
        }
        if (manualIntervention.rejectExternalPositionInterventions()
                && tradingStateProjection.hasExternalPositionInterventions(
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket())
                )) {
            reasons.add(EXTERNAL_POSITION_INTERVENTION_REASON);
        }
        return List.copyOf(reasons);
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
        return Map.copyOf(attributes);
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
