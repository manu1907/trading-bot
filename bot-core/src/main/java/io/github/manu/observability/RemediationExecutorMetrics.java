package io.github.manu.observability;

import io.github.manu.intervention.InterventionRemediationCommandPlanner;
import io.github.manu.intervention.InterventionRemediationExecutorService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class RemediationExecutorMetrics {

    static final String OUTCOME_EVENTS = "trading.remediation_executor.outcome.events";

    private static final String DISABLED_REASON = "executor:policy_disabled";

    private final MeterRegistry meterRegistry;

    public RemediationExecutorMetrics() {
        this(Metrics.globalRegistry);
    }

    @Autowired
    public RemediationExecutorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void executorDisabled(String provider, String environment, String account, String market, String mode) {
        Counter.builder(OUTCOME_EVENTS)
                .description("Remediation executor evaluation outcomes")
                .tag("provider", tagValue(provider, "unknown"))
                .tag("environment", tagValue(environment, "unknown"))
                .tag("account", tagValue(account, "unknown"))
                .tag("market", tagValue(market, "unknown"))
                .tag("mode", tagValue(mode, "unknown"))
                .tag("operation", "NONE")
                .tag("status", "DISABLED")
                .tag("reason", DISABLED_REASON)
                .register(meterRegistry)
                .increment();
    }

    public void executorOutcome(
            InterventionRemediationExecutorService.RemediationExecutionReport report,
            String mode
    ) {
        Counter.builder(OUTCOME_EVENTS)
                .description("Remediation executor evaluation outcomes")
                .tag("provider", tagValue(report.provider(), "unknown"))
                .tag("environment", tagValue(report.environment(), "unknown"))
                .tag("account", tagValue(report.account(), "unknown"))
                .tag("market", tagValue(report.market(), "unknown"))
                .tag("mode", tagValue(mode, "unknown"))
                .tag("operation", operation(report.operation()))
                .tag("status", report.status() == null ? "unknown" : report.status().name())
                .tag("reason", reason(report))
                .register(meterRegistry)
                .increment();
    }

    private String operation(InterventionRemediationCommandPlanner.Operation operation) {
        return operation == null ? "UNKNOWN" : operation.name();
    }

    private String reason(InterventionRemediationExecutorService.RemediationExecutionReport report) {
        if (report.attributes() != null) {
            String attributeReason = tagValue(report.attributes().get("executor_reason"), null);
            if (attributeReason != null) {
                return attributeReason;
            }
        }
        if (report.reasons() != null && !report.reasons().isEmpty()) {
            return tagValue(report.reasons().get(report.reasons().size() - 1), "unknown");
        }
        return "unknown";
    }

    private String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
