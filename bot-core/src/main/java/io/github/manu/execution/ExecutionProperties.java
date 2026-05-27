package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.execution")
public record ExecutionProperties(
        Pipeline pipeline,
        RiskGate riskGate,
        Idempotency idempotency
) {

    public ExecutionProperties {
        pipeline = pipeline == null ? Pipeline.disabled() : pipeline;
        riskGate = riskGate == null ? RiskGate.defaults() : riskGate;
        idempotency = idempotency == null ? Idempotency.defaults() : idempotency;
    }

    public ExecutionProperties(RiskGate riskGate) {
        this(null, riskGate, null);
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
            Reconciliation reconciliation,
            ManualIntervention manualIntervention
    ) {

        public RiskGate {
            enabled = enabled == null || enabled;
            reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
            manualIntervention = manualIntervention == null ? ManualIntervention.defaults() : manualIntervention;
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation) {
            this(enabled, reconciliation, null);
        }

        static RiskGate defaults() {
            return new RiskGate(true, Reconciliation.defaults(), ManualIntervention.defaults());
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

    public record ManualIntervention(
            Boolean rejectExternalOrderInterventions
    ) {

        public ManualIntervention {
            rejectExternalOrderInterventions = rejectExternalOrderInterventions == null
                    || rejectExternalOrderInterventions;
        }

        static ManualIntervention defaults() {
            return new ManualIntervention(true);
        }
    }

    public record Idempotency(
            Boolean enabled,
            Integer maxTrackedKeys
    ) {

        private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

        public Idempotency {
            enabled = enabled == null || enabled;
            maxTrackedKeys = maxTrackedKeys == null
                    ? Integer.valueOf(DEFAULT_MAX_TRACKED_KEYS)
                    : maxTrackedKeys;
            if (maxTrackedKeys.intValue() < 1) {
                throw new IllegalArgumentException("maxTrackedKeys must be positive");
            }
        }

        static Idempotency defaults() {
            return new Idempotency(true, DEFAULT_MAX_TRACKED_KEYS);
        }
    }
}
