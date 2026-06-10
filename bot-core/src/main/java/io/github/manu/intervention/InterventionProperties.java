package io.github.manu.intervention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "trading.intervention")
public record InterventionProperties(
        OperatorApi operatorApi,
        RemediationOrchestrator remediationOrchestrator,
        AutomatedPolicy automatedPolicy,
        AutomatedDecisionService automatedDecisionService,
        AutomatedRemediationRunner automatedRemediationRunner,
        RemediationExecutorPolicy remediationExecutorPolicy
) {

    @ConstructorBinding
    public InterventionProperties {
        operatorApi = operatorApi == null ? OperatorApi.disabled() : operatorApi;
        remediationOrchestrator = remediationOrchestrator == null
                ? RemediationOrchestrator.disabled()
                : remediationOrchestrator;
        automatedPolicy = automatedPolicy == null ? AutomatedPolicy.defaults() : automatedPolicy;
        automatedDecisionService = automatedDecisionService == null
                ? AutomatedDecisionService.disabled()
                : automatedDecisionService;
        automatedRemediationRunner = automatedRemediationRunner == null
                ? AutomatedRemediationRunner.disabled()
                : automatedRemediationRunner;
        remediationExecutorPolicy = remediationExecutorPolicy == null
                ? RemediationExecutorPolicy.disabled()
                : remediationExecutorPolicy;
    }

    public InterventionProperties(
            OperatorApi operatorApi,
            RemediationOrchestrator remediationOrchestrator,
            AutomatedPolicy automatedPolicy,
            AutomatedDecisionService automatedDecisionService,
            RemediationExecutorPolicy remediationExecutorPolicy
    ) {
        this(
                operatorApi,
                remediationOrchestrator,
                automatedPolicy,
                automatedDecisionService,
                null,
                remediationExecutorPolicy
        );
    }

    public record OperatorApi(
            Boolean enabled,
            String operatorToken
    ) {

        public OperatorApi {
            enabled = Boolean.TRUE.equals(enabled);
            operatorToken = text(operatorToken);
        }

        static OperatorApi disabled() {
            return new OperatorApi(false, null);
        }

        private static String text(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }

    public record RemediationOrchestrator(
            Boolean enabled,
            Boolean operatorReviewAcknowledgementEnabled,
            Integer maxTrackedDecisionIds
    ) {

        public RemediationOrchestrator {
            enabled = Boolean.TRUE.equals(enabled);
            operatorReviewAcknowledgementEnabled = operatorReviewAcknowledgementEnabled == null
                    || Boolean.TRUE.equals(operatorReviewAcknowledgementEnabled);
            int normalizedMaxTrackedDecisionIds = maxTrackedDecisionIds == null ? 100_000 : maxTrackedDecisionIds;
            if (normalizedMaxTrackedDecisionIds <= 0) {
                throw new IllegalArgumentException("maxTrackedDecisionIds must be positive");
            }
            maxTrackedDecisionIds = normalizedMaxTrackedDecisionIds;
        }

        static RemediationOrchestrator disabled() {
            return new RemediationOrchestrator(false, true, 100_000);
        }
    }

    public record AutomatedPolicy(
            RemediationAction externalOrderAction,
            RemediationAction managedOrderChangeAction,
            RemediationAction flatPositionAction,
            RemediationAction openPositionAction,
            RemediationAction unknownPositionAction
    ) {

        public AutomatedPolicy {
            externalOrderAction = externalOrderAction == null
                    ? RemediationAction.OPERATOR_REVIEW
                    : externalOrderAction;
            managedOrderChangeAction = managedOrderChangeAction == null
                    ? RemediationAction.REPLAN_FROM_PROJECTION
                    : managedOrderChangeAction;
            flatPositionAction = flatPositionAction == null
                    ? RemediationAction.REPLAN_FROM_PROJECTION
                    : flatPositionAction;
            openPositionAction = openPositionAction == null
                    ? RemediationAction.HEDGE_OR_REPLAN
                    : openPositionAction;
            unknownPositionAction = unknownPositionAction == null
                    ? RemediationAction.OPERATOR_REVIEW
                    : unknownPositionAction;
        }

        static AutomatedPolicy defaults() {
            return new AutomatedPolicy(null, null, null, null, null);
        }
    }

    public record AutomatedDecisionService(
            Boolean enabled,
            Boolean includeOperatorReviewActions,
            Integer maxDecisionsPerRun,
            String decidedBy,
            String decisionReason
    ) {

        public AutomatedDecisionService {
            enabled = Boolean.TRUE.equals(enabled);
            includeOperatorReviewActions = Boolean.TRUE.equals(includeOperatorReviewActions);
            int normalizedMaxDecisionsPerRun = maxDecisionsPerRun == null ? 100 : maxDecisionsPerRun;
            if (normalizedMaxDecisionsPerRun <= 0) {
                throw new IllegalArgumentException("maxDecisionsPerRun must be positive");
            }
            maxDecisionsPerRun = normalizedMaxDecisionsPerRun;
            decidedBy = decidedBy == null || decidedBy.isBlank()
                    ? "automated_remediation_policy"
                    : decidedBy.trim();
            decisionReason = decisionReason == null || decisionReason.isBlank()
                    ? "automated policy selected remediation action"
                    : decisionReason.trim();
        }

        static AutomatedDecisionService disabled() {
            return new AutomatedDecisionService(false, false, 100, null, null);
        }
    }

    public record AutomatedRemediationRunner(
            Boolean enabled,
            Long intervalMillis,
            Long initialDelayMillis,
            Boolean publishDecisions,
            Boolean executeRemediation,
            Target target
    ) {

        public AutomatedRemediationRunner {
            enabled = Boolean.TRUE.equals(enabled);
            long normalizedIntervalMillis = intervalMillis == null ? 30_000L : intervalMillis;
            if (normalizedIntervalMillis <= 0L) {
                throw new IllegalArgumentException("intervalMillis must be positive");
            }
            intervalMillis = normalizedIntervalMillis;
            long normalizedInitialDelayMillis = initialDelayMillis == null ? 30_000L : initialDelayMillis;
            if (normalizedInitialDelayMillis < 0L) {
                throw new IllegalArgumentException("initialDelayMillis must be zero or positive");
            }
            initialDelayMillis = normalizedInitialDelayMillis;
            publishDecisions = publishDecisions == null || Boolean.TRUE.equals(publishDecisions);
            executeRemediation = executeRemediation == null || Boolean.TRUE.equals(executeRemediation);
            target = target == null ? Target.active() : target;
            if (Boolean.TRUE.equals(enabled)
                    && !Boolean.TRUE.equals(publishDecisions)
                    && !Boolean.TRUE.equals(executeRemediation)) {
                throw new IllegalArgumentException(
                        "automatedRemediationRunner requires publishDecisions or executeRemediation when enabled"
                );
            }
        }

        static AutomatedRemediationRunner disabled() {
            return new AutomatedRemediationRunner(false, 30_000L, 30_000L, true, true, Target.active());
        }
    }

    public record Target(
            String provider,
            String environment,
            String account,
            String market
    ) {

        public Target {
            provider = normalize(provider);
            environment = normalize(environment);
            account = normalize(account);
            market = normalize(market);
        }

        static Target active() {
            return new Target(null, null, null, null);
        }

        private static String normalize(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }

    public record RemediationExecutorPolicy(
            Boolean enabled,
            Boolean exchangeExecutionEnabled,
            Boolean reportOnly,
            Boolean allowRealEnvironment,
            Boolean requireReadyPlan,
            Boolean requireFreshProjectionMatch,
            Boolean requireProjectionTargetIdentity,
            Boolean requireManagedExecutionPipeline,
            Boolean rejectStaleProjection,
            Boolean rejectUnsupportedPlans,
            Boolean rejectOperatorReviewPlans,
            Boolean rejectInsufficientDataPlans,
            Integer maxPlansPerRun,
            List<ExecutableOperation> allowedOperations,
            PositionOrderPolicy positionOrderPolicy
    ) {
        public RemediationExecutorPolicy {
            enabled = Boolean.TRUE.equals(enabled);
            exchangeExecutionEnabled = Boolean.TRUE.equals(exchangeExecutionEnabled);
            reportOnly = reportOnly == null || Boolean.TRUE.equals(reportOnly);
            allowRealEnvironment = Boolean.TRUE.equals(allowRealEnvironment);
            requireReadyPlan = requireReadyPlan == null || Boolean.TRUE.equals(requireReadyPlan);
            requireFreshProjectionMatch = requireFreshProjectionMatch == null
                    || Boolean.TRUE.equals(requireFreshProjectionMatch);
            requireProjectionTargetIdentity = requireProjectionTargetIdentity == null
                    || Boolean.TRUE.equals(requireProjectionTargetIdentity);
            requireManagedExecutionPipeline = requireManagedExecutionPipeline == null
                    || Boolean.TRUE.equals(requireManagedExecutionPipeline);
            rejectStaleProjection = rejectStaleProjection == null || Boolean.TRUE.equals(rejectStaleProjection);
            rejectUnsupportedPlans = rejectUnsupportedPlans == null || Boolean.TRUE.equals(rejectUnsupportedPlans);
            rejectOperatorReviewPlans = rejectOperatorReviewPlans == null
                    || Boolean.TRUE.equals(rejectOperatorReviewPlans);
            rejectInsufficientDataPlans = rejectInsufficientDataPlans == null
                    || Boolean.TRUE.equals(rejectInsufficientDataPlans);
            int normalizedMaxPlansPerRun = maxPlansPerRun == null ? 25 : maxPlansPerRun;
            if (normalizedMaxPlansPerRun <= 0) {
                throw new IllegalArgumentException("maxPlansPerRun must be positive");
            }
            maxPlansPerRun = normalizedMaxPlansPerRun;
            if (allowedOperations == null) {
                allowedOperations = List.of();
            } else {
                for (ExecutableOperation operation : allowedOperations) {
                    if (operation == null) {
                        throw new IllegalArgumentException("allowedOperations must not contain null values");
                    }
                }
                allowedOperations = List.copyOf(allowedOperations);
            }
            positionOrderPolicy = positionOrderPolicy == null ? PositionOrderPolicy.disabled() : positionOrderPolicy;
            if (Boolean.TRUE.equals(exchangeExecutionEnabled)) {
                if (!Boolean.TRUE.equals(enabled)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires remediation executor policy to be enabled");
                }
                if (Boolean.TRUE.equals(reportOnly)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires reportOnly=false");
                }
                if (allowedOperations.isEmpty()) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires at least one allowed operation");
                }
                if (!Boolean.TRUE.equals(requireReadyPlan)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires requireReadyPlan=true");
                }
                if (!Boolean.TRUE.equals(requireFreshProjectionMatch)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires requireFreshProjectionMatch=true");
                }
                if (!Boolean.TRUE.equals(requireProjectionTargetIdentity)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires requireProjectionTargetIdentity=true");
                }
                if (!Boolean.TRUE.equals(requireManagedExecutionPipeline)) {
                    throw new IllegalArgumentException("exchangeExecutionEnabled requires requireManagedExecutionPipeline=true");
                }
            }
            if (!Boolean.TRUE.equals(exchangeExecutionEnabled) && Boolean.FALSE.equals(reportOnly)) {
                throw new IllegalArgumentException("reportOnly cannot be false unless exchangeExecutionEnabled is true");
            }
        }

        static RemediationExecutorPolicy disabled() {
            return new RemediationExecutorPolicy(
                    false,
                    false,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    25,
                    List.of(),
                    PositionOrderPolicy.disabled()
            );
        }
    }

    public record PositionOrderPolicy(
            Boolean oneWayReduceOnlyEnabled,
            String provider,
            String market,
            String positionSide,
            String orderType,
            Boolean requireReduceOnly,
            Boolean requireClosePositionFalse,
            Boolean hedgeModeExecutionEnabled,
            Boolean hedgePositionOrderEnabled,
            List<String> allowedSymbols,
            String maxPositionQuantity,
            Boolean chunkCloseWhenMaxQuantityExceeded,
            String maxPositionNotional,
            Boolean rejectUnboundedPositionNotional,
            String requiredMarginType,
            String requiredPositionMode,
            String minLeverage,
            String maxLeverage,
            String maxAccountPositionNotional,
            String maxSymbolPositionNotional,
            String maxAccountUnrealizedLoss,
            String maxSymbolUnrealizedLoss,
            String minAccountMarginBalance,
            String maxAccountMarginUtilization,
            Boolean rejectMissingAccountRiskMetadata
    ) {

        public PositionOrderPolicy {
            oneWayReduceOnlyEnabled = Boolean.TRUE.equals(oneWayReduceOnlyEnabled);
            provider = normalize(provider, "binance");
            market = normalize(market, "usdm_futures");
            positionSide = normalize(positionSide, "BOTH");
            orderType = normalize(orderType, "MARKET");
            requireReduceOnly = requireReduceOnly == null || Boolean.TRUE.equals(requireReduceOnly);
            requireClosePositionFalse = requireClosePositionFalse == null || Boolean.TRUE.equals(requireClosePositionFalse);
            hedgeModeExecutionEnabled = Boolean.TRUE.equals(hedgeModeExecutionEnabled);
            hedgePositionOrderEnabled = Boolean.TRUE.equals(hedgePositionOrderEnabled);
            allowedSymbols = normalizeSymbols(allowedSymbols);
            validatePositiveDecimal("maxPositionQuantity", maxPositionQuantity);
            validatePositiveDecimal("maxPositionNotional", maxPositionNotional);
            maxPositionQuantity = text(maxPositionQuantity);
            chunkCloseWhenMaxQuantityExceeded = Boolean.TRUE.equals(chunkCloseWhenMaxQuantityExceeded);
            maxPositionNotional = text(maxPositionNotional);
            rejectUnboundedPositionNotional = rejectUnboundedPositionNotional == null
                    || Boolean.TRUE.equals(rejectUnboundedPositionNotional);
            requiredMarginType = normalizeOptionalUpper(requiredMarginType);
            requiredPositionMode = normalizeOptionalUpper(requiredPositionMode == null ? "HEDGE" : requiredPositionMode);
            validatePositiveInteger("minLeverage", minLeverage);
            validatePositiveInteger("maxLeverage", maxLeverage);
            minLeverage = text(minLeverage);
            maxLeverage = text(maxLeverage);
            validateRange("leverage", minLeverage, maxLeverage);
            validatePositiveDecimal("maxAccountPositionNotional", maxAccountPositionNotional);
            validatePositiveDecimal("maxSymbolPositionNotional", maxSymbolPositionNotional);
            maxAccountPositionNotional = text(maxAccountPositionNotional);
            maxSymbolPositionNotional = text(maxSymbolPositionNotional);
            validatePositiveDecimal("maxAccountUnrealizedLoss", maxAccountUnrealizedLoss);
            validatePositiveDecimal("maxSymbolUnrealizedLoss", maxSymbolUnrealizedLoss);
            maxAccountUnrealizedLoss = text(maxAccountUnrealizedLoss);
            maxSymbolUnrealizedLoss = text(maxSymbolUnrealizedLoss);
            validatePositiveDecimal("minAccountMarginBalance", minAccountMarginBalance);
            minAccountMarginBalance = text(minAccountMarginBalance);
            validatePositiveDecimal("maxAccountMarginUtilization", maxAccountMarginUtilization);
            maxAccountMarginUtilization = text(maxAccountMarginUtilization);
            rejectMissingAccountRiskMetadata = rejectMissingAccountRiskMetadata == null
                    || Boolean.TRUE.equals(rejectMissingAccountRiskMetadata);
        }

        static PositionOrderPolicy disabled() {
            return new PositionOrderPolicy(
                    false,
                    "binance",
                    "usdm_futures",
                    "BOTH",
                    "MARKET",
                    true,
                    true,
                    false,
                    false,
                    List.of(),
                    null,
                    false,
                    null,
                    true,
                    null,
                    "HEDGE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }

        @Override
        public List<String> allowedSymbols() {
            return List.copyOf(allowedSymbols);
        }

        private static String normalize(String value, String defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return value.trim();
        }

        private static String text(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }

        private static String normalizeOptionalUpper(String value) {
            String text = text(value);
            return text == null ? null : text.toUpperCase(Locale.ROOT);
        }

        private static List<String> normalizeSymbols(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<String> normalized = new ArrayList<>();
            for (String value : values) {
                String symbol = text(value);
                if (symbol == null) {
                    throw new IllegalArgumentException("allowedSymbols must not contain blank values");
                }
                normalized.add(symbol.toUpperCase(Locale.ROOT));
            }
            return List.copyOf(normalized);
        }

        private static void validatePositiveDecimal(String field, String value) {
            String text = text(value);
            if (text == null) {
                return;
            }
            try {
                if (new BigDecimal(text).compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException(field + " must be positive when configured");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be a decimal number", e);
            }
        }

        private static void validatePositiveInteger(String field, String value) {
            String text = text(value);
            if (text == null) {
                return;
            }
            try {
                if (Integer.parseInt(text) <= 0) {
                    throw new IllegalArgumentException(field + " must be positive when configured");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be an integer", e);
            }
        }

        private static void validateRange(String field, String min, String max) {
            if (min == null || max == null) {
                return;
            }
            if (Integer.parseInt(min) > Integer.parseInt(max)) {
                throw new IllegalArgumentException(field + " min must be less than or equal to max");
            }
        }
    }

    public enum RemediationAction {
        OPERATOR_REVIEW,
        REPLAN_FROM_PROJECTION,
        HEDGE_OR_REPLAN,
        ADOPT,
        AMEND,
        REDUCE,
        CLOSE,
        HEDGE,
        PAUSE_SYMBOL,
        PAUSE_ACCOUNT,
        IGNORE
    }

    public enum ExecutableOperation {
        CANCEL_ORDER,
        ADOPT_ORDER,
        AMEND_ORDER,
        REPLAN_FROM_PROJECTION,
        CLOSE_POSITION,
        REDUCE_POSITION,
        HEDGE_POSITION,
        ADOPT_POSITION,
        PAUSE_SYMBOL,
        PAUSE_ACCOUNT,
        IGNORE
    }
}
