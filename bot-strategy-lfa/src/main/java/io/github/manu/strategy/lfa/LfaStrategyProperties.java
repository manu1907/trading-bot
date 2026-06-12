package io.github.manu.strategy.lfa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

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
            Integer maxSignalsPerRun,
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            BigDecimal maxAccountPositionNotional,
            BigDecimal maxSymbolPositionNotional,
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
            allowedLifecycleStates = normalizedList(allowedLifecycleStates, List.of("ACTIVE"), "allowedLifecycleStates");
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
            maxSignalsPerRun = positive(maxSignalsPerRun, 1, "maxSignalsPerRun");
            maxAccountOpenPositions = positiveOrNull(maxAccountOpenPositions, "maxAccountOpenPositions");
            maxSymbolOpenPositions = positiveOrNull(maxSymbolOpenPositions, "maxSymbolOpenPositions");
            maxAccountPositionNotional = positiveOrNull(maxAccountPositionNotional, "maxAccountPositionNotional");
            maxSymbolPositionNotional = positiveOrNull(maxSymbolPositionNotional, "maxSymbolPositionNotional");
            maxAccountDailyRealizedLoss = positiveOrNull(maxAccountDailyRealizedLoss, "maxAccountDailyRealizedLoss");
            maxSymbolDailyRealizedLoss = positiveOrNull(maxSymbolDailyRealizedLoss, "maxSymbolDailyRealizedLoss");
            rejectMissingAccountRiskMetadata =
                    rejectMissingAccountRiskMetadata == null || Boolean.TRUE.equals(rejectMissingAccountRiskMetadata);
            requireSignalPlannerEnabled =
                    requireSignalPlannerEnabled == null || Boolean.TRUE.equals(requireSignalPlannerEnabled);
            if (enabled && targetQuantity == null && targetNotional == null) {
                throw new IllegalArgumentException("targetQuantity or targetNotional is required when LFA signal runner is enabled");
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
                    1,
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
            return normalized;
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
