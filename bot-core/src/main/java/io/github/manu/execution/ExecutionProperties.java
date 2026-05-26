package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.execution")
public record ExecutionProperties(
        RiskGate riskGate
) {

    public ExecutionProperties {
        riskGate = riskGate == null ? RiskGate.defaults() : riskGate;
    }

    public record RiskGate(
            Boolean enabled,
            Reconciliation reconciliation
    ) {

        public RiskGate {
            enabled = enabled == null || enabled;
            reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
        }

        static RiskGate defaults() {
            return new RiskGate(true, Reconciliation.defaults());
        }
    }

    public record Reconciliation(
            Boolean required,
            Boolean rejectNoObservations,
            Boolean rejectDegraded
    ) {

        public Reconciliation {
            required = required == null || required;
            rejectNoObservations = rejectNoObservations == null || rejectNoObservations;
            rejectDegraded = rejectDegraded == null || rejectDegraded;
        }

        static Reconciliation defaults() {
            return new Reconciliation(true, true, true);
        }
    }
}
