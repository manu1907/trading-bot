package io.github.manu.position;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "trading.position")
public record PositionProperties(
        Lifecycle lifecycle
) {

    @ConstructorBinding
    public PositionProperties {
        lifecycle = lifecycle == null ? Lifecycle.disabled() : lifecycle;
    }

    public record Lifecycle(
            Boolean enabled,
            Long intervalMillis,
            Long initialDelayMillis,
            String strategyId,
            String governedStrategyId,
            Boolean requireStrategyLifecycleActive,
            List<String> allowedStrategyLifecycleStates,
            Target target,
            Boolean requireReconciliationConfidence,
            Boolean requireMarketData,
            Long maxMarketDataAgeMillis,
            Boolean skipWhenOpenOrdersExist,
            Boolean skipWhenUnknownOrdersExist,
            Boolean skipWhenUnresolvedCommandsExist,
            String reduceWhenUnrealizedLossAtLeast,
            String reduceFraction,
            String closeWhenUnrealizedLossAtLeast,
            String orderType,
            String timeInForce
    ) {

        @ConstructorBinding
        public Lifecycle {
            enabled = Boolean.TRUE.equals(enabled);
            intervalMillis = positiveOrDefault(intervalMillis, 30_000L, "position lifecycle intervalMillis");
            initialDelayMillis = positiveOrDefault(initialDelayMillis, 30_000L, "position lifecycle initialDelayMillis");
            strategyId = textOrDefault(strategyId, "position-lifecycle");
            governedStrategyId = textOrNull(governedStrategyId);
            requireStrategyLifecycleActive = requireStrategyLifecycleActive == null || Boolean.TRUE.equals(requireStrategyLifecycleActive);
            allowedStrategyLifecycleStates = allowedStrategyLifecycleStates == null || allowedStrategyLifecycleStates.isEmpty()
                    ? List.of("ACTIVE")
                    : List.copyOf(allowedStrategyLifecycleStates.stream()
                            .filter(value -> value != null && !value.isBlank())
                            .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                            .distinct()
                            .toList());
            if (allowedStrategyLifecycleStates.isEmpty()) {
                throw new IllegalArgumentException("position lifecycle allowedStrategyLifecycleStates must not be empty");
            }
            target = target == null ? Target.empty() : target;
            requireReconciliationConfidence = requireReconciliationConfidence == null || Boolean.TRUE.equals(requireReconciliationConfidence);
            requireMarketData = requireMarketData == null || Boolean.TRUE.equals(requireMarketData);
            maxMarketDataAgeMillis = positiveOrDefault(maxMarketDataAgeMillis, 30_000L, "position lifecycle maxMarketDataAgeMillis");
            skipWhenOpenOrdersExist = skipWhenOpenOrdersExist == null || Boolean.TRUE.equals(skipWhenOpenOrdersExist);
            skipWhenUnknownOrdersExist = skipWhenUnknownOrdersExist == null || Boolean.TRUE.equals(skipWhenUnknownOrdersExist);
            skipWhenUnresolvedCommandsExist = skipWhenUnresolvedCommandsExist == null || Boolean.TRUE.equals(skipWhenUnresolvedCommandsExist);
            validatePositiveDecimal("position lifecycle reduceWhenUnrealizedLossAtLeast", reduceWhenUnrealizedLossAtLeast);
            validatePositiveDecimal("position lifecycle closeWhenUnrealizedLossAtLeast", closeWhenUnrealizedLossAtLeast);
            reduceFraction = decimalOrDefault(reduceFraction, "0.50", "position lifecycle reduceFraction");
            BigDecimal fraction = new BigDecimal(reduceFraction);
            if (fraction.compareTo(BigDecimal.ZERO) <= 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("position lifecycle reduceFraction must be in (0, 1]");
            }
            orderType = textOrDefault(orderType, "MARKET").toUpperCase(java.util.Locale.ROOT);
            timeInForce = textOrNull(timeInForce);
        }

        static Lifecycle disabled() {
            return new Lifecycle(
                    false,
                    30_000L,
                    30_000L,
                    "position-lifecycle",
                    null,
                    true,
                    List.of("ACTIVE"),
                    Target.empty(),
                    true,
                    true,
                    30_000L,
                    true,
                    true,
                    true,
                    null,
                    "0.50",
                    null,
                    "MARKET",
                    null
            );
        }
    }

    public record Target(
            String provider,
            String environment,
            String account,
            String market,
            List<String> symbols
    ) {

        @ConstructorBinding
        public Target {
            provider = textOrNull(provider);
            environment = textOrNull(environment);
            account = textOrNull(account);
            market = textOrNull(market);
            symbols = symbols == null ? List.of() : List.copyOf(symbols.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                    .distinct()
                    .toList());
        }

        static Target empty() {
            return new Target(null, null, null, null, List.of());
        }
    }

    private static Long positiveOrDefault(Long value, Long defaultValue, String name) {
        Long effective = value == null ? defaultValue : value;
        if (effective <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return effective;
    }

    private static String decimalOrDefault(String value, String defaultValue, String name) {
        String effective = textOrDefault(value, defaultValue);
        validatePositiveDecimal(name, effective);
        return effective;
    }

    private static void validatePositiveDecimal(String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        BigDecimal decimal = new BigDecimal(value.trim());
        if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be positive when configured");
        }
    }

    private static String textOrDefault(String value, String defaultValue) {
        String text = textOrNull(value);
        return text == null ? defaultValue : text;
    }

    private static String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
