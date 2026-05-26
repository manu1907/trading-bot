package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.execution")
public record ExecutionProperties(
        Pipeline pipeline,
        RiskGate riskGate
) {

    public ExecutionProperties {
        pipeline = pipeline == null ? Pipeline.disabled() : pipeline;
        riskGate = riskGate == null ? RiskGate.defaults() : riskGate;
    }

    public ExecutionProperties(RiskGate riskGate) {
        this(null, riskGate);
    }

    public record Pipeline(
            Boolean enabled
    ) {

        public Pipeline {
            enabled = Boolean.TRUE.equals(enabled);
        }

        static Pipeline disabled() {
            return new Pipeline(false);
        }
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
