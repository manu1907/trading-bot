package io.github.manu.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
            Defaults defaults,
            List<FeatureProfile> featureProfiles,
            InstrumentUniverse instrumentUniverse
    ) {

        @ConstructorBinding
        public SignalPlanner {
            enabled = Boolean.TRUE.equals(enabled);
            defaults = defaults == null ? Defaults.empty() : defaults;
            featureProfiles = featureProfiles == null ? List.of() : List.copyOf(featureProfiles);
            instrumentUniverse = instrumentUniverse == null ? InstrumentUniverse.disabled() : instrumentUniverse;
        }

        public SignalPlanner(Boolean enabled, Defaults defaults) {
            this(enabled, defaults, List.of(), null);
        }

        public SignalPlanner(Boolean enabled, Defaults defaults, List<FeatureProfile> featureProfiles) {
            this(enabled, defaults, featureProfiles, null);
        }

        static SignalPlanner disabled() {
            return new SignalPlanner(false, Defaults.empty(), List.of(), InstrumentUniverse.disabled());
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

        public record FeatureProfile(
                String provider,
                String environment,
                String account,
                String market,
                String symbol,
                String signalType,
                String orderType,
                Double minConfidence,
                Map<String, String> matchFeatures,
                Map<String, String> attributes
        ) {

            @ConstructorBinding
            public FeatureProfile {
                matchFeatures = matchFeatures == null ? Map.of() : Map.copyOf(matchFeatures);
                attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            }
        }

        public record InstrumentUniverse(
                Boolean enabled,
                List<String> includedSymbols,
                List<String> excludedSymbols,
                Boolean refreshExchangeMetadataBeforePlanning,
                Boolean requireExchangeMetadata,
                Boolean requireIncludedSymbol,
                Boolean requireSymbolEnabled,
                Boolean requirePromotionReady,
                String requiredStatus,
                String requiredOrderType,
                List<String> allowedQuoteAssets,
                List<String> allowedContractTypes,
                Integer maxEligibleSymbols,
                Boolean requireMarketData,
                Boolean requireTopOfBook,
                Long maxMarketDataAgeMillis,
                String maxSpreadBps,
                String minTopOfBookQuoteNotional,
                String minDailyQuoteVolume,
                List<SymbolPolicy> symbolPolicies
        ) {

            @ConstructorBinding
            public InstrumentUniverse {
                enabled = Boolean.TRUE.equals(enabled);
                includedSymbols = normalizeSymbols(includedSymbols);
                excludedSymbols = normalizeSymbols(excludedSymbols);
                refreshExchangeMetadataBeforePlanning = Boolean.TRUE.equals(refreshExchangeMetadataBeforePlanning);
                requireExchangeMetadata = Boolean.TRUE.equals(requireExchangeMetadata);
                requireIncludedSymbol = Boolean.TRUE.equals(requireIncludedSymbol);
                requireSymbolEnabled = requireSymbolEnabled == null || Boolean.TRUE.equals(requireSymbolEnabled);
                requirePromotionReady = Boolean.TRUE.equals(requirePromotionReady);
                requiredStatus = normalizeText(requiredStatus, "TRADING");
                requiredOrderType = normalizeText(requiredOrderType, null);
                allowedQuoteAssets = normalizeTexts(allowedQuoteAssets);
                allowedContractTypes = normalizeTexts(allowedContractTypes);
                if (maxEligibleSymbols != null && maxEligibleSymbols.intValue() <= 0) {
                    throw new IllegalArgumentException("instrument universe maxEligibleSymbols must be positive when configured");
                }
                requireMarketData = Boolean.TRUE.equals(requireMarketData);
                requireTopOfBook = Boolean.TRUE.equals(requireTopOfBook);
                if (maxMarketDataAgeMillis != null && maxMarketDataAgeMillis.longValue() <= 0L) {
                    throw new IllegalArgumentException(
                            "instrument universe maxMarketDataAgeMillis must be positive when configured"
                    );
                }
                validatePositiveDecimal("instrument universe maxSpreadBps", maxSpreadBps);
                validatePositiveDecimal("instrument universe minTopOfBookQuoteNotional", minTopOfBookQuoteNotional);
                validatePositiveDecimal("instrument universe minDailyQuoteVolume", minDailyQuoteVolume);
                symbolPolicies = symbolPolicies == null ? List.of() : List.copyOf(symbolPolicies);
            }

            public InstrumentUniverse(
                    Boolean enabled,
                    List<String> includedSymbols,
                    List<String> excludedSymbols,
                    Boolean refreshExchangeMetadataBeforePlanning,
                    Boolean requireExchangeMetadata,
                    Boolean requireIncludedSymbol,
                    Boolean requireSymbolEnabled,
                    Boolean requirePromotionReady,
                    String requiredStatus,
                    String requiredOrderType,
                    List<String> allowedQuoteAssets,
                    List<String> allowedContractTypes,
                    Integer maxEligibleSymbols,
                    Boolean requireMarketData,
                    Boolean requireTopOfBook,
                    Long maxMarketDataAgeMillis,
                    String maxSpreadBps,
                    List<SymbolPolicy> symbolPolicies
            ) {
                this(
                        enabled,
                        includedSymbols,
                        excludedSymbols,
                        refreshExchangeMetadataBeforePlanning,
                        requireExchangeMetadata,
                        requireIncludedSymbol,
                        requireSymbolEnabled,
                        requirePromotionReady,
                        requiredStatus,
                        requiredOrderType,
                        allowedQuoteAssets,
                        allowedContractTypes,
                        maxEligibleSymbols,
                        requireMarketData,
                        requireTopOfBook,
                        maxMarketDataAgeMillis,
                        maxSpreadBps,
                        null,
                        null,
                        symbolPolicies
                );
            }

            public InstrumentUniverse(
                    Boolean enabled,
                    List<String> includedSymbols,
                    List<String> excludedSymbols,
                    Boolean requireIncludedSymbol,
                    Boolean requireSymbolEnabled,
                    Boolean requirePromotionReady,
                    List<SymbolPolicy> symbolPolicies
            ) {
                this(
                        enabled,
                        includedSymbols,
                        excludedSymbols,
                        false,
                        false,
                        requireIncludedSymbol,
                        requireSymbolEnabled,
                        requirePromotionReady,
                        "TRADING",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        false,
                        60000L,
                        null,
                        null,
                        null,
                        symbolPolicies
                );
            }

            static InstrumentUniverse disabled() {
                return new InstrumentUniverse(
                        false,
                        List.of(),
                        List.of(),
                        false,
                        false,
                        false,
                        true,
                        false,
                        "TRADING",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        false,
                        60000L,
                        null,
                        null,
                        null,
                        List.of()
                );
            }

            @Override
            public List<String> includedSymbols() {
                return List.copyOf(includedSymbols);
            }

            @Override
            public List<String> excludedSymbols() {
                return List.copyOf(excludedSymbols);
            }

            @Override
            public List<String> allowedQuoteAssets() {
                return List.copyOf(allowedQuoteAssets);
            }

            @Override
            public List<String> allowedContractTypes() {
                return List.copyOf(allowedContractTypes);
            }

            private static List<String> normalizeSymbols(List<String> symbols) {
                if (symbols == null || symbols.isEmpty()) {
                    return List.of();
                }
                return List.copyOf(symbols.stream()
                        .map(InstrumentUniverse::normalizeSymbol)
                        .distinct()
                        .toList());
            }

            private static String normalizeSymbol(String symbol) {
                if (symbol == null || symbol.isBlank()) {
                    throw new IllegalArgumentException("instrument universe symbols must be non-blank");
                }
                return symbol.trim().toUpperCase(java.util.Locale.ROOT);
            }

            private static List<String> normalizeTexts(List<String> values) {
                if (values == null || values.isEmpty()) {
                    return List.of();
                }
                return List.copyOf(values.stream()
                        .map(value -> normalizeText(value, null))
                        .distinct()
                        .toList());
            }

            private static String normalizeText(String value, String defaultValue) {
                if (value == null || value.isBlank()) {
                    return defaultValue;
                }
                return value.trim().toUpperCase(java.util.Locale.ROOT);
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

        public record SymbolPolicy(
                String provider,
                String environment,
                String account,
                String market,
                String symbol,
                Boolean enabled,
                Boolean promotionReady,
                String minDailyQuoteVolume,
                String maxSpreadBps,
                String minTopOfBookQuoteNotional,
                String maxOrderNotional
        ) {

            @ConstructorBinding
            public SymbolPolicy {
                if (symbol == null || symbol.isBlank()) {
                    throw new IllegalArgumentException("symbolPolicies.symbol is required");
                }
                symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
                validatePositiveDecimal("symbolPolicies.minDailyQuoteVolume", minDailyQuoteVolume);
                validatePositiveDecimal("symbolPolicies.maxSpreadBps", maxSpreadBps);
                validatePositiveDecimal("symbolPolicies.minTopOfBookQuoteNotional", minTopOfBookQuoteNotional);
                validatePositiveDecimal("symbolPolicies.maxOrderNotional", maxOrderNotional);
            }

            public SymbolPolicy(
                    String provider,
                    String environment,
                    String account,
                    String market,
                    String symbol,
                    Boolean enabled,
                    Boolean promotionReady,
                    String minDailyQuoteVolume,
                    String maxSpreadBps,
                    String maxOrderNotional
            ) {
                this(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        enabled,
                        promotionReady,
                        minDailyQuoteVolume,
                        maxSpreadBps,
                        null,
                        maxOrderNotional
                );
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
    }

    public record RiskGate(
            Boolean enabled,
            Reconciliation reconciliation,
            ManualIntervention manualIntervention,
            UnknownOrderStatus unknownOrderStatus,
            PendingOrderCommand pendingOrderCommand,
            OrderLimit orderLimit,
            TargetOrder targetOrder,
            PauseGovernance pauseGovernance
    ) {

        @ConstructorBinding
        public RiskGate {
            enabled = enabled == null || enabled;
            reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
            manualIntervention = manualIntervention == null ? ManualIntervention.defaults() : manualIntervention;
            unknownOrderStatus = unknownOrderStatus == null ? UnknownOrderStatus.defaults() : unknownOrderStatus;
            pendingOrderCommand = pendingOrderCommand == null ? PendingOrderCommand.defaults() : pendingOrderCommand;
            orderLimit = orderLimit == null ? OrderLimit.defaults() : orderLimit;
            targetOrder = targetOrder == null ? TargetOrder.defaults() : targetOrder;
            pauseGovernance = pauseGovernance == null ? PauseGovernance.defaults() : pauseGovernance;
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation) {
            this(enabled, reconciliation, null, null, null, null, null, null);
        }

        public RiskGate(Boolean enabled, Reconciliation reconciliation, ManualIntervention manualIntervention) {
            this(enabled, reconciliation, manualIntervention, null, null, null, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus
        ) {
            this(enabled, reconciliation, manualIntervention, unknownOrderStatus, null, null, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus,
                PendingOrderCommand pendingOrderCommand
        ) {
            this(enabled, reconciliation, manualIntervention, unknownOrderStatus, pendingOrderCommand, null, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus,
                PendingOrderCommand pendingOrderCommand,
                OrderLimit orderLimit
        ) {
            this(enabled, reconciliation, manualIntervention, unknownOrderStatus, pendingOrderCommand, orderLimit, null, null);
        }

        public RiskGate(
                Boolean enabled,
                Reconciliation reconciliation,
                ManualIntervention manualIntervention,
                UnknownOrderStatus unknownOrderStatus,
                PendingOrderCommand pendingOrderCommand,
                OrderLimit orderLimit,
                TargetOrder targetOrder
        ) {
            this(
                    enabled,
                    reconciliation,
                    manualIntervention,
                    unknownOrderStatus,
                    pendingOrderCommand,
                    orderLimit,
                    targetOrder,
                    null
            );
        }

        static RiskGate defaults() {
            return new RiskGate(
                    true,
                    Reconciliation.defaults(),
                    ManualIntervention.defaults(),
                    UnknownOrderStatus.defaults(),
                    PendingOrderCommand.defaults(),
                    OrderLimit.defaults(),
                    TargetOrder.defaults(),
                    PauseGovernance.defaults()
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
            InterventionAction externalPositionAction,
            Boolean externalOrderApplyToTargetCommands,
            Boolean externalPositionApplyToTargetCommands
    ) {

        @ConstructorBinding
        public ManualIntervention {
            externalOrderApplyToTargetCommands = Boolean.TRUE.equals(externalOrderApplyToTargetCommands);
            externalPositionApplyToTargetCommands = Boolean.TRUE.equals(externalPositionApplyToTargetCommands);
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
                Boolean rejectExternalPositionInterventions,
                InterventionAction externalOrderAction,
                InterventionAction externalPositionAction
        ) {
            this(
                    rejectExternalOrderInterventions,
                    rejectExternalPositionInterventions,
                    externalOrderAction,
                    externalPositionAction,
                    null,
                    null
            );
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
                    InterventionAction.MANUAL_REVIEW,
                    false,
                    false
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
            InterventionAction action,
            Boolean applyToTargetCommands
    ) {

        @ConstructorBinding
        public UnknownOrderStatus {
            applyToTargetCommands = Boolean.TRUE.equals(applyToTargetCommands);
            action = ManualIntervention.resolveAction(
                    rejectUnknownOrderStatus,
                    action,
                    "unknown order status"
            );
            rejectUnknownOrderStatus = action.blocksNewCommands();
        }

        public UnknownOrderStatus(Boolean rejectUnknownOrderStatus, InterventionAction action) {
            this(rejectUnknownOrderStatus, action, null);
        }

        static UnknownOrderStatus defaults() {
            return new UnknownOrderStatus(true, InterventionAction.MANUAL_REVIEW, false);
        }
    }

    public record PendingOrderCommand(
            Boolean rejectUnresolvedOrderCommands,
            InterventionAction action,
            Boolean applyToTargetCommands
    ) {

        @ConstructorBinding
        public PendingOrderCommand {
            applyToTargetCommands = Boolean.TRUE.equals(applyToTargetCommands);
            action = ManualIntervention.resolveAction(
                    rejectUnresolvedOrderCommands,
                    action,
                    "pending order command"
            );
            rejectUnresolvedOrderCommands = action.blocksNewCommands();
        }

        public PendingOrderCommand(Boolean rejectUnresolvedOrderCommands, InterventionAction action) {
            this(rejectUnresolvedOrderCommands, action, null);
        }

        static PendingOrderCommand defaults() {
            return new PendingOrderCommand(true, InterventionAction.MANUAL_REVIEW, false);
        }
    }

    public record OrderLimit(
            Boolean enabled,
            Boolean rejectInvalidNumericFields,
            String maxQuantity,
            String maxNotional,
            Boolean rejectUnboundedNotional,
            Integer maxOpenOrders,
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
            validatePositiveInteger("maxOpenOrders", maxOpenOrders);
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
            this(
                    enabled,
                    rejectInvalidNumericFields,
                    maxQuantity,
                    maxNotional,
                    rejectUnboundedNotional,
                    null,
                    action,
                    List.of()
            );
        }

        public OrderLimit(
                Boolean enabled,
                Boolean rejectInvalidNumericFields,
                String maxQuantity,
                String maxNotional,
                Boolean rejectUnboundedNotional,
                Integer maxOpenOrders,
                InterventionAction action
        ) {
            this(
                    enabled,
                    rejectInvalidNumericFields,
                    maxQuantity,
                    maxNotional,
                    rejectUnboundedNotional,
                    maxOpenOrders,
                    action,
                    List.of()
            );
        }

        public OrderLimit(
                Boolean enabled,
                Boolean rejectInvalidNumericFields,
                String maxQuantity,
                String maxNotional,
                Boolean rejectUnboundedNotional,
                InterventionAction action,
                List<TargetLimit> targetLimits
        ) {
            this(
                    enabled,
                    rejectInvalidNumericFields,
                    maxQuantity,
                    maxNotional,
                    rejectUnboundedNotional,
                    null,
                    action,
                    targetLimits
            );
        }

        static OrderLimit defaults() {
            return new OrderLimit(true, true, null, null, true, null, InterventionAction.REJECT_NEW_COMMANDS, List.of());
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
                Integer maxOpenOrders,
                InterventionAction action
        ) {

            @ConstructorBinding
            public TargetLimit {
                validatePositiveDecimal("targetLimits.maxQuantity", maxQuantity);
                validatePositiveDecimal("targetLimits.maxNotional", maxNotional);
                validatePositiveInteger("targetLimits.maxOpenOrders", maxOpenOrders);
            }

            public TargetLimit(
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
                this(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        maxQuantity,
                        maxNotional,
                        rejectUnboundedNotional,
                        null,
                        action
                );
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

        private static void validatePositiveInteger(String field, Integer value) {
            if (value == null) {
                return;
            }
            if (value.intValue() <= 0) {
                throw new IllegalArgumentException(field + " must be positive when configured");
            }
        }
    }

    public record TargetOrder(
            Boolean enabled,
            Boolean requireTargetClientOrderId,
            Boolean requireProjectedTarget,
            Boolean requireManagedTarget,
            Boolean rejectClosedTarget,
            Boolean rejectExternalIntervention,
            InterventionAction action,
            Boolean requireTargetOrderId,
            Boolean allowExternalRemediationCancel,
            Boolean allowAdoptedTargetOrders
    ) {

        @ConstructorBinding
        public TargetOrder {
            enabled = enabled == null || enabled;
            requireTargetClientOrderId = Boolean.TRUE.equals(requireTargetClientOrderId);
            requireProjectedTarget = requireProjectedTarget == null || requireProjectedTarget;
            requireManagedTarget = requireManagedTarget == null || requireManagedTarget;
            rejectClosedTarget = rejectClosedTarget == null || rejectClosedTarget;
            rejectExternalIntervention = rejectExternalIntervention == null || rejectExternalIntervention;
            action = action == null ? InterventionAction.MANUAL_REVIEW : action;
            requireTargetOrderId = requireTargetOrderId == null || requireTargetOrderId;
            allowExternalRemediationCancel = allowExternalRemediationCancel == null
                    || Boolean.TRUE.equals(allowExternalRemediationCancel);
            allowAdoptedTargetOrders = Boolean.TRUE.equals(allowAdoptedTargetOrders);
        }

        public TargetOrder(
                Boolean enabled,
                Boolean requireTargetClientOrderId,
                Boolean requireProjectedTarget,
                Boolean requireManagedTarget,
                Boolean rejectClosedTarget,
                Boolean rejectExternalIntervention,
                InterventionAction action,
                Boolean requireTargetOrderId
        ) {
            this(
                    enabled,
                    requireTargetClientOrderId,
                    requireProjectedTarget,
                    requireManagedTarget,
                    rejectClosedTarget,
                    rejectExternalIntervention,
                    action,
                    requireTargetOrderId,
                    true,
                    false
            );
        }

        public TargetOrder(
                Boolean enabled,
                Boolean requireTargetClientOrderId,
                Boolean requireProjectedTarget,
                Boolean requireManagedTarget,
                Boolean rejectClosedTarget,
                Boolean rejectExternalIntervention,
                InterventionAction action
        ) {
            this(
                    enabled,
                    requireTargetClientOrderId,
                    requireProjectedTarget,
                    requireManagedTarget,
                    rejectClosedTarget,
                    rejectExternalIntervention,
                    action,
                    requireTargetClientOrderId,
                    true,
                    false
            );
        }

        static TargetOrder defaults() {
            return new TargetOrder(
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    InterventionAction.MANUAL_REVIEW,
                    true,
                    true,
                    false
            );
        }
    }

    public record PauseGovernance(
            Boolean overrideEnabled,
            Boolean requireOverrideActor,
            Boolean requireOverrideReason,
            Boolean requireOverrideExpiry,
            Integer maxOverrideSeconds
    ) {

        private static final int DEFAULT_MAX_OVERRIDE_SECONDS = 900;

        @ConstructorBinding
        public PauseGovernance {
            overrideEnabled = Boolean.TRUE.equals(overrideEnabled);
            requireOverrideActor = requireOverrideActor == null || Boolean.TRUE.equals(requireOverrideActor);
            requireOverrideReason = requireOverrideReason == null || Boolean.TRUE.equals(requireOverrideReason);
            requireOverrideExpiry = requireOverrideExpiry == null || Boolean.TRUE.equals(requireOverrideExpiry);
            int normalizedMaxOverrideSeconds = maxOverrideSeconds == null
                    ? DEFAULT_MAX_OVERRIDE_SECONDS
                    : maxOverrideSeconds;
            if (normalizedMaxOverrideSeconds <= 0) {
                throw new IllegalArgumentException("maxOverrideSeconds must be positive");
            }
            maxOverrideSeconds = normalizedMaxOverrideSeconds;
        }

        static PauseGovernance defaults() {
            return new PauseGovernance(false, true, true, true, DEFAULT_MAX_OVERRIDE_SECONDS);
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
