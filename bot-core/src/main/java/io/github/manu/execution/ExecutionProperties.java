package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "trading.execution")
public record ExecutionProperties(
        Pipeline pipeline,
        RiskGate riskGate,
        Idempotency idempotency
) {

    @ConstructorBinding
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
            ManualIntervention manualIntervention,
            UnknownOrderStatus unknownOrderStatus
    ) {

        @ConstructorBinding
        public RiskGate {
            enabled = enabled == null || enabled;
            reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
            manualIntervention = manualIntervention == null ? ManualIntervention.defaults() : manualIntervention;
            unknownOrderStatus = unknownOrderStatus == null ? UnknownOrderStatus.defaults() : unknownOrderStatus;
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation) {
            this(enabled, reconciliation, null, null);
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation, ManualIntervention manualIntervention) {
            this(enabled, reconciliation, manualIntervention, null);
        }

        static RiskGate defaults() {
            return new RiskGate(
                    true,
                    Reconciliation.defaults(),
                    ManualIntervention.defaults(),
                    UnknownOrderStatus.defaults()
            );
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
            Boolean rejectExternalOrderInterventions,
            Boolean rejectExternalPositionInterventions,
            InterventionAction externalOrderAction,
            InterventionAction externalPositionAction
    ) {

        @ConstructorBinding
        public ManualIntervention {
            externalOrderAction = resolveAction(
                    rejectExternalOrderInterventions,
                    externalOrderAction,
                    "manual intervention"
            );
            externalPositionAction = resolveAction(
                    rejectExternalPositionInterventions,
                    externalPositionAction,
                    "manual intervention"
            );
            rejectExternalOrderInterventions = externalOrderAction.blocksNewCommands();
            rejectExternalPositionInterventions = externalPositionAction.blocksNewCommands();
        }

        public ManualIntervention(
                Boolean rejectExternalOrderInterventions,
                Boolean rejectExternalPositionInterventions
        ) {
            this(rejectExternalOrderInterventions, rejectExternalPositionInterventions, null, null);
        }

        public ManualIntervention(Boolean rejectExternalOrderInterventions) {
            this(rejectExternalOrderInterventions, null);
        }

        static ManualIntervention defaults() {
            return new ManualIntervention(
                    true,
                    true,
                    InterventionAction.MANUAL_REVIEW,
                    InterventionAction.MANUAL_REVIEW
            );
        }

        private static InterventionAction resolveAction(
                Boolean legacyRejectFlag,
                InterventionAction action,
                String policyName
        ) {
            if (action != null) {
                if (legacyRejectFlag != null && legacyRejectFlag != action.blocksNewCommands()) {
                    throw new IllegalArgumentException(policyName + " reject flag conflicts with remediation action");
                }
                return action;
            }
            if (legacyRejectFlag == null || legacyRejectFlag) {
                return InterventionAction.MANUAL_REVIEW;
            }
            return InterventionAction.ALLOW_NEW_COMMANDS;
        }
    }

    public record UnknownOrderStatus(
            Boolean rejectUnknownOrderStatus,
            InterventionAction action
    ) {

        @ConstructorBinding
        public UnknownOrderStatus {
            action = ManualIntervention.resolveAction(
                    rejectUnknownOrderStatus,
                    action,
                    "unknown order status"
            );
            rejectUnknownOrderStatus = action.blocksNewCommands();
        }

        static UnknownOrderStatus defaults() {
            return new UnknownOrderStatus(true, InterventionAction.MANUAL_REVIEW);
        }
    }

    public enum InterventionAction {
        ALLOW_NEW_COMMANDS(false),
        REJECT_NEW_COMMANDS(true),
        MANUAL_REVIEW(true);

        private final boolean blocksNewCommands;

        InterventionAction(boolean blocksNewCommands) {
            this.blocksNewCommands = blocksNewCommands;
        }

        boolean blocksNewCommands() {
            return blocksNewCommands;
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
