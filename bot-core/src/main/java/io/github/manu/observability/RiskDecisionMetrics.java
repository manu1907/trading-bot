package io.github.manu.observability;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class RiskDecisionMetrics {

    static final String DECISION_EVENTS = "trading.risk_gate.decision.events";

    private final MeterRegistry meterRegistry;

    public RiskDecisionMetrics() {
        this(Metrics.globalRegistry);
    }

    @Autowired
    public RiskDecisionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void riskDecision(OrderCommandEvent command, RiskDecisionEvent decision) {
        Counter.builder(DECISION_EVENTS)
                .description("Order risk-gate decisions before provider mapping")
                .tag("provider", value(command.getProvider()))
                .tag("environment", value(command.getEnvironment()))
                .tag("account", value(command.getAccount()))
                .tag("market", value(command.getMarket()))
                .tag("action", command.getAction() == null ? "unknown" : command.getAction().name())
                .tag("decision", decision.getDecision() == null ? "unknown" : decision.getDecision().name())
                .tag("primary_reason", primaryReason(decision))
                .tag("reduce_only", Boolean.toString(command.getReduceOnly()))
                .tag("close_position", Boolean.toString(command.getClosePosition()))
                .register(meterRegistry)
                .increment();
    }

    private String primaryReason(RiskDecisionEvent decision) {
        if (decision.getReasons() == null || decision.getReasons().isEmpty()) {
            return "unknown";
        }
        return tagValue(text(decision.getReasons().getFirst()), "unknown");
    }

    private String value(CharSequence value) {
        return tagValue(text(value), "unknown");
    }

    private String text(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
