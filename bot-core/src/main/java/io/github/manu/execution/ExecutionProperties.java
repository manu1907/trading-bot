package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "trading.execution")
public record ExecutionProperties(
        Pipeline pipeline,
        SignalPlanner signalPlanner,
        RiskGate riskGate,
        Idempotency idempotency
) {

    @ConstructorBinding
    public ExecutionProperties {
        pipeline = pipeline == null ? Pipeline.disabled() : pipeline;
        signalPlanner = signalPlanner == null ? SignalPlanner.disabled() : signalPlanner;
        riskGate = riskGate == null ? RiskGate.defaults() : riskGate;
        idempotency = idempotency == null ? Idempotency.defaults() : idempotency;
    }

    public ExecutionProperties(RiskGate riskGate) {
        this(null, null, riskGate, null);
    }

    public ExecutionProperties(SignalPlanner signalPlanner, RiskGate riskGate) {
        this(null, signalPlanner, riskGate, null);
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

    public record SignalPlanner(
            Boolean enabled,
            Defaults defaults
    ) {

        @ConstructorBinding
        public SignalPlanner {
            enabled = Boolean.TRUE.equals(enabled);
            defaults = defaults == null ? Defaults.empty() : defaults;
        }

        static SignalPlanner disabled() {
            return new SignalPlanner(false, Defaults.empty());
        }

        public record Defaults(
                String provider,
                String environment,
                String account,
                String market,
                String symbol,
                String limitOrderTimeInForce,
                String clientOrderIdPrefix
        ) {

            @ConstructorBinding
            public Defaults {
                limitOrderTimeInForce = limitOrderTimeInForce == null || limitOrderTimeInForce.isBlank()
                        ? "GTC"
                        : limitOrderTimeInForce.trim();
                clientOrderIdPrefix = clientOrderIdPrefix == null || clientOrderIdPrefix.isBlank()
                        ? "tb"
                        : clientOrderIdPrefix.trim();
            }

            static Defaults empty() {
                return new Defaults(null, null, null, null, null, "GTC", "tb");
            }
        }
    }

    public record RiskGate(
            Boolean enabled,
            Reconciliation reconciliation,
            ManualIntervention manualIntervention,
            UnknownOrderStatus unknownOrderStatus,
            PendingOrderCommand pendingOrderCommand,
            OrderLimit orderLimit
    ) {

        @ConstructorBinding
        public RiskGate {
            enabled = enabled == null || enabled;
            reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
            manualIntervention = manualIntervention == null ? ManualIntervention.defaults() : manualIntervention;
            unknownOrderStatus = unknownOrderStatus == null ? UnknownOrderStatus.defaults() : unknownOrderStatus;
            pendingOrderCommand = pendingOrderCommand == null ? PendingOrderCommand.defaults() : pendingOrderCommand;
            orderLimit = orderLimit == null ? OrderLimit.defaults() : orderLimit;
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation) {
            this(enabled, reconciliation, null, null, null, null);
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation, ManualIntervention manualIntervention) {
            this(enabled, reconciliation, manualIntervention, null, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus
        ) {
            this(enabled, reconciliation, manualIntervention, unknownOrderStatus, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus,
                PendingOrderCommand pendingOrderCommand
        ) {
            this(enabled, reconciliation, manualIntervention, unknownOrderStatus, pendingOrderCommand, null);
        }

        static RiskGate defaults() {
            return new RiskGate(
                    true,
                    Reconciliation.defaults(),
                    ManualIntervention.defaults(),
                    UnknownOrderStatus.defaults(),
                    PendingOrderCommand.defaults(),
                    OrderLimit.defaults()
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

    public record PendingOrderCommand(
            Boolean rejectUnresolvedOrderCommands,
            InterventionAction action
    ) {

        @ConstructorBinding
        public PendingOrderCommand {
            action = ManualIntervention.resolveAction(
                    rejectUnresolvedOrderCommands,
                    action,
                    "pending order command"
            );
            rejectUnresolvedOrderCommands = action.blocksNewCommands();
        }

        static PendingOrderCommand defaults() {
            return new PendingOrderCommand(true, InterventionAction.MANUAL_REVIEW);
        }
    }

    public record OrderLimit(
            Boolean enabled,
            Boolean rejectInvalidNumericFields,
            String maxQuantity,
            String maxNotional,
            Boolean rejectUnboundedNotional,
            InterventionAction action,
            List<TargetLimit> targetLimits
    ) {

        @ConstructorBinding
        public OrderLimit {
            enabled = enabled == null || enabled;
            rejectInvalidNumericFields = rejectInvalidNumericFields == null || rejectInvalidNumericFields;
            rejectUnboundedNotional = rejectUnboundedNotional == null || rejectUnboundedNotional;
            action = action == null ? InterventionAction.REJECT_NEW_COMMANDS : action;
            validatePositiveDecimal("maxQuantity", maxQuantity);
            validatePositiveDecimal("maxNotional", maxNotional);
            targetLimits = targetLimits == null ? List.of() : List.copyOf(targetLimits);
        }

        public OrderLimit(
                Boolean enabled,
                Boolean rejectInvalidNumericFields,
                String maxQuantity,
                String maxNotional,
                Boolean rejectUnboundedNotional,
                InterventionAction action
        ) {
            this(enabled, rejectInvalidNumericFields, maxQuantity, maxNotional, rejectUnboundedNotional, action, List.of());
        }

        static OrderLimit defaults() {
            return new OrderLimit(true, true, null, null, true, InterventionAction.REJECT_NEW_COMMANDS, List.of());
        }

        public record TargetLimit(
                String provider,
                String environment,
                String account,
                String market,
                String symbol,
                String maxQuantity,
                String maxNotional,
                Boolean rejectUnboundedNotional,
                InterventionAction action
        ) {

            @ConstructorBinding
            public TargetLimit {
                validatePositiveDecimal("targetLimits.maxQuantity", maxQuantity);
                validatePositiveDecimal("targetLimits.maxNotional", maxNotional);
            }
        }

        private static void validatePositiveDecimal(String field, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            try {
                if (new BigDecimal(value).compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException(field + " must be positive when configured");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be a decimal number", e);
            }
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
            Integer maxTrackedKeys,
            Boolean rejectProjectedDuplicates
    ) {

        private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

        @ConstructorBinding
        public Idempotency {
            enabled = enabled == null || enabled;
            maxTrackedKeys = maxTrackedKeys == null
                    ? Integer.valueOf(DEFAULT_MAX_TRACKED_KEYS)
                    : maxTrackedKeys;
            rejectProjectedDuplicates = rejectProjectedDuplicates == null || rejectProjectedDuplicates;
            if (maxTrackedKeys.intValue() < 1) {
                throw new IllegalArgumentException("maxTrackedKeys must be positive");
            }
        }

        public Idempotency(Boolean enabled, Integer maxTrackedKeys) {
            this(enabled, maxTrackedKeys, null);
        }

        static Idempotency defaults() {
            return new Idempotency(true, DEFAULT_MAX_TRACKED_KEYS, true);
        }
    }
}
