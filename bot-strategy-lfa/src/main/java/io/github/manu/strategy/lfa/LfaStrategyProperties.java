package io.github.manu.strategy.lfa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties(prefix = "trading.strategy.lfa")
public record LfaStrategyProperties(
        SignalRunner signalRunner
) {

    @ConstructorBinding
    public LfaStrategyProperties {
        signalRunner = signalRunner == null ? SignalRunner.disabled() : signalRunner;
    }

    public record SignalRunner(
            Boolean enabled,
            Long initialDelayMillis,
            Long intervalMillis,
            String strategyId,
            String provider,
            String environment,
            String account,
            String market,
            String lifecycleState,
            List<String> allowedLifecycleStates,
            Map<String, List<String>> allowedLifecycleTransitions,
            Boolean allowEmergencyStopReactivation,
            Boolean requireWarmupMarketData,
            Integer minWarmupMarketDataSymbols,
            Integer minWarmupTopOfBookSymbols,
            Long warmupMaxMarketDataAgeMillis,
            Boolean useSignalPlannerInstrumentUniverse,
            Integer maxCandidateMarketDataSymbols,
            BigDecimal minImbalanceRatio,
            BigDecimal maxSpreadBps,
            BigDecimal minTopOfBookQuoteNotional,
            Long maxMarketDataAgeMillis,
            String targetQuantity,
            String targetNotional,
            BigDecimal targetNotionalMarginBalanceFraction,
            BigDecimal minAllocatedTargetNotional,
            BigDecimal maxAllocatedTargetNotional,
            Boolean rejectMissingAllocationBalance,
            Integer maxSignalsPerRun,
            Integer maxAccountOpenOrders,
            Integer maxSymbolOpenOrders,
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            BigDecimal maxAccountPositionNotional,
            BigDecimal maxSymbolPositionNotional,
            BigDecimal maxAccountUnrealizedLoss,
            BigDecimal maxSymbolUnrealizedLoss,
            BigDecimal minAccountMarginBalance,
            BigDecimal maxAccountMarginDrawdownFraction,
            BigDecimal maxAccountMarginUtilization,
            BigDecimal maxAccountDailyRealizedLoss,
            BigDecimal maxSymbolDailyRealizedLoss,
            Boolean rejectMissingAccountRiskMetadata,
            Boolean requireSignalPlannerEnabled
    ) {

        @ConstructorBinding
        public SignalRunner {
            enabled = Boolean.TRUE.equals(enabled);
            initialDelayMillis = positive(initialDelayMillis, 30_000L, "initialDelayMillis");
            intervalMillis = positive(intervalMillis, 30_000L, "intervalMillis");
            strategyId = text(strategyId, "lfa");
            lifecycleState = normalizedText(lifecycleState, "STOPPED");
            LfaLifecycleState.parse(lifecycleState, "lifecycleState");
            allowedLifecycleStates = normalizedList(allowedLifecycleStates, List.of("ACTIVE"), "allowedLifecycleStates");
            allowedLifecycleTransitions = normalizedTransitions(allowedLifecycleTransitions);
            allowEmergencyStopReactivation = Boolean.TRUE.equals(allowEmergencyStopReactivation);
            requireWarmupMarketData = requireWarmupMarketData == null || Boolean.TRUE.equals(requireWarmupMarketData);
            minWarmupMarketDataSymbols = positive(minWarmupMarketDataSymbols, 1, "minWarmupMarketDataSymbols");
            minWarmupTopOfBookSymbols = positive(minWarmupTopOfBookSymbols, 1, "minWarmupTopOfBookSymbols");
            warmupMaxMarketDataAgeMillis = positive(
                    warmupMaxMarketDataAgeMillis,
                    30_000L,
                    "warmupMaxMarketDataAgeMillis"
            );
            useSignalPlannerInstrumentUniverse =
                    useSignalPlannerInstrumentUniverse == null || Boolean.TRUE.equals(useSignalPlannerInstrumentUniverse);
            maxCandidateMarketDataSymbols = positiveOrNull(maxCandidateMarketDataSymbols, "maxCandidateMarketDataSymbols");
            minImbalanceRatio = positive(minImbalanceRatio, new BigDecimal("1.50"), "minImbalanceRatio");
            if (minImbalanceRatio.compareTo(BigDecimal.ONE) <= 0) {
                throw new IllegalArgumentException("minImbalanceRatio must be greater than 1");
            }
            maxSpreadBps = positive(maxSpreadBps, new BigDecimal("5"), "maxSpreadBps");
            minTopOfBookQuoteNotional = positive(
                    minTopOfBookQuoteNotional,
                    new BigDecimal("250"),
                    "minTopOfBookQuoteNotional"
            );
            maxMarketDataAgeMillis = positive(maxMarketDataAgeMillis, 30_000L, "maxMarketDataAgeMillis");
            targetQuantity = blankToNull(targetQuantity);
            targetNotional = blankToNull(targetNotional);
            targetNotionalMarginBalanceFraction =
                    positiveOrNull(targetNotionalMarginBalanceFraction, "targetNotionalMarginBalanceFraction");
            if (targetNotionalMarginBalanceFraction != null
                    && targetNotionalMarginBalanceFraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("targetNotionalMarginBalanceFraction must be <= 1");
            }
            minAllocatedTargetNotional = positiveOrNull(minAllocatedTargetNotional, "minAllocatedTargetNotional");
            maxAllocatedTargetNotional = positiveOrNull(maxAllocatedTargetNotional, "maxAllocatedTargetNotional");
            rejectMissingAllocationBalance =
                    rejectMissingAllocationBalance == null || Boolean.TRUE.equals(rejectMissingAllocationBalance);
            if (minAllocatedTargetNotional != null && maxAllocatedTargetNotional != null
                    && minAllocatedTargetNotional.compareTo(maxAllocatedTargetNotional) > 0) {
                throw new IllegalArgumentException("minAllocatedTargetNotional must be <= maxAllocatedTargetNotional");
            }
            maxSignalsPerRun = positive(maxSignalsPerRun, 1, "maxSignalsPerRun");
            maxAccountOpenOrders = positiveOrNull(maxAccountOpenOrders, "maxAccountOpenOrders");
            maxSymbolOpenOrders = positiveOrNull(maxSymbolOpenOrders, "maxSymbolOpenOrders");
            maxAccountOpenPositions = positiveOrNull(maxAccountOpenPositions, "maxAccountOpenPositions");
            maxSymbolOpenPositions = positiveOrNull(maxSymbolOpenPositions, "maxSymbolOpenPositions");
            maxAccountPositionNotional = positiveOrNull(maxAccountPositionNotional, "maxAccountPositionNotional");
            maxSymbolPositionNotional = positiveOrNull(maxSymbolPositionNotional, "maxSymbolPositionNotional");
            maxAccountUnrealizedLoss = positiveOrNull(maxAccountUnrealizedLoss, "maxAccountUnrealizedLoss");
            maxSymbolUnrealizedLoss = positiveOrNull(maxSymbolUnrealizedLoss, "maxSymbolUnrealizedLoss");
            minAccountMarginBalance = positiveOrNull(minAccountMarginBalance, "minAccountMarginBalance");
            maxAccountMarginDrawdownFraction =
                    positiveOrNull(maxAccountMarginDrawdownFraction, "maxAccountMarginDrawdownFraction");
            if (maxAccountMarginDrawdownFraction != null
                    && maxAccountMarginDrawdownFraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("maxAccountMarginDrawdownFraction must be <= 1");
            }
            maxAccountMarginUtilization = positiveOrNull(maxAccountMarginUtilization, "maxAccountMarginUtilization");
            maxAccountDailyRealizedLoss = positiveOrNull(maxAccountDailyRealizedLoss, "maxAccountDailyRealizedLoss");
            maxSymbolDailyRealizedLoss = positiveOrNull(maxSymbolDailyRealizedLoss, "maxSymbolDailyRealizedLoss");
            rejectMissingAccountRiskMetadata =
                    rejectMissingAccountRiskMetadata == null || Boolean.TRUE.equals(rejectMissingAccountRiskMetadata);
            requireSignalPlannerEnabled =
                    requireSignalPlannerEnabled == null || Boolean.TRUE.equals(requireSignalPlannerEnabled);
            if (enabled && targetQuantity == null && targetNotional == null && targetNotionalMarginBalanceFraction == null) {
                throw new IllegalArgumentException(
                        "targetQuantity, targetNotional, or targetNotionalMarginBalanceFraction is required when LFA signal runner is enabled"
                );
            }
        }

        static SignalRunner disabled() {
            return new SignalRunner(
                    false,
                    30_000L,
                    30_000L,
                    "lfa",
                    null,
                    null,
                    null,
                    null,
                    "STOPPED",
                    List.of("ACTIVE"),
                    defaultAllowedLifecycleTransitions(),
                    false,
                    true,
                    1,
                    1,
                    30_000L,
                    true,
                    null,
                    new BigDecimal("1.50"),
                    new BigDecimal("5"),
                    new BigDecimal("250"),
                    30_000L,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    true
            );
        }

        @Override
        public List<String> allowedLifecycleStates() {
            return List.copyOf(allowedLifecycleStates);
        }

        @Override
        public Map<String, List<String>> allowedLifecycleTransitions() {
            return copyTransitions(allowedLifecycleTransitions);
        }

        LfaSignalRequest request(String provider, String environment, String account, String market) {
            return new LfaSignalRequest(
                    strategyId,
                    provider,
                    environment,
                    account,
                    market,
                    minImbalanceRatio,
                    maxSpreadBps,
                    minTopOfBookQuoteNotional,
                    maxMarketDataAgeMillis,
                    targetQuantity,
                    targetNotional
            );
        }

        private static String text(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static String normalizedText(String value, String defaultValue) {
            return text(value, defaultValue).toUpperCase(Locale.ROOT);
        }

        private static List<String> normalizedList(List<String> values, List<String> defaultValue, String field) {
            List<String> resolved = values == null || values.isEmpty() ? defaultValue : values;
            List<String> normalized = resolved.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " must contain at least one non-blank state");
            }
            normalized.forEach(value -> LfaLifecycleState.parse(value, field));
            return normalized;
        }

        private static Map<String, List<String>> normalizedTransitions(Map<String, List<String>> transitions) {
            Map<String, List<String>> resolved = transitions == null || transitions.isEmpty()
                    ? defaultAllowedLifecycleTransitions()
                    : transitions;
            LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
            resolved.forEach((from, toStates) -> {
                LfaLifecycleState fromState = LfaLifecycleState.parse(from, "allowedLifecycleTransitions");
                List<String> to = normalizedList(toStates, List.of(), "allowedLifecycleTransitions." + fromState.name());
                normalized.put(fromState.name(), to);
            });
            return Map.copyOf(normalized);
        }

        private static Map<String, List<String>> defaultAllowedLifecycleTransitions() {
            LinkedHashMap<String, List<String>> defaults = new LinkedHashMap<>();
            defaults.put("STARTING", List.of("PAUSED", "STOPPED", "EMERGENCY_STOP"));
            defaults.put("PAUSED", List.of("ACTIVE", "DRAINING", "STOPPED", "EMERGENCY_STOP"));
            defaults.put("ACTIVE", List.of("PAUSED", "DRAINING", "EMERGENCY_STOP"));
            defaults.put("DRAINING", List.of("PAUSED", "STOPPED", "EMERGENCY_STOP"));
            defaults.put("STOPPED", List.of("STARTING", "PAUSED", "EMERGENCY_STOP"));
            defaults.put("EMERGENCY_STOP", List.of("STOPPED"));
            return copyTransitions(defaults);
        }

        private static Map<String, List<String>> copyTransitions(Map<String, List<String>> transitions) {
            LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
            transitions.forEach((state, nextStates) -> copy.put(state, List.copyOf(nextStates)));
            return Map.copyOf(copy);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static long positive(Long value, long defaultValue, String field) {
            long resolved = value == null ? defaultValue : value.longValue();
            if (resolved <= 0L) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return resolved;
        }

        private static int positive(Integer value, int defaultValue, String field) {
            int resolved = value == null ? defaultValue : value.intValue();
            if (resolved <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return resolved;
        }

        private static BigDecimal positive(BigDecimal value, BigDecimal defaultValue, String field) {
            BigDecimal resolved = value == null ? defaultValue : value;
            if (resolved.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return resolved;
        }

        private static Integer positiveOrNull(Integer value, String field) {
            if (value != null && value.intValue() <= 0) {
                throw new IllegalArgumentException(field + " must be positive when configured");
            }
            return value;
        }

        private static BigDecimal positiveOrNull(BigDecimal value, String field) {
            if (value != null && value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(field + " must be positive when configured");
            }
            return value;
        }
    }
}
