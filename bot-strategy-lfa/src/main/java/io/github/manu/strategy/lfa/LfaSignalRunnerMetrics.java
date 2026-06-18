package io.github.manu.strategy.lfa;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

import java.util.List;
import java.util.Objects;

public final class LfaSignalRunnerMetrics {

    static final String RUN_EVENTS = "trading.strategy.lfa.signal_runner.run.events";

    private final MeterRegistry meterRegistry;

    public LfaSignalRunnerMetrics() {
        this(Metrics.globalRegistry);
    }

    public LfaSignalRunnerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void signalRun(LfaSignalRunner.LfaSignalRunResult result) {
        Counter.builder(RUN_EVENTS)
                .description("LFA signal runner run outcomes")
                .tag("provider", targetValue(result, TargetField.PROVIDER))
                .tag("environment", targetValue(result, TargetField.ENVIRONMENT))
                .tag("account", targetValue(result, TargetField.ACCOUNT))
                .tag("market", targetValue(result, TargetField.MARKET))
                .tag("enabled", Boolean.toString(result.enabled()))
                .tag("status", status(result))
                .tag("reason", tagValue(result.reason(), "unknown"))
                .tag("primary_blocker", primaryBlocker(result.blockers()))
                .register(meterRegistry)
                .increment();
    }

    private String targetValue(LfaSignalRunner.LfaSignalRunResult result, TargetField field) {
        LfaSignalRunner.LfaRunnerTarget target = result.target();
        if (target == null) {
            return "unknown";
        }
        return switch (field) {
            case PROVIDER -> tagValue(target.provider(), "unknown");
            case ENVIRONMENT -> tagValue(target.environment(), "unknown");
            case ACCOUNT -> tagValue(target.account(), "unknown");
            case MARKET -> tagValue(target.market(), "unknown");
        };
    }

    private String status(LfaSignalRunner.LfaSignalRunResult result) {
        if (!result.enabled()) {
            return "DISABLED";
        }
        String reason = tagValue(result.reason(), "");
        if ("lfa_signal_runner:already_running".equals(reason)) {
            return "SKIPPED";
        }
        if (reason.endsWith("_blocked") || !result.blockers().isEmpty()) {
            return "BLOCKED";
        }
        if ("lfa_signal_runner:published".equals(reason)
                || "lfa_signal_runner:published_with_budget_blocks".equals(reason)) {
            return "PUBLISHED";
        }
        if ("lfa_signal_runner:no_signal".equals(reason)) {
            return "NO_SIGNAL";
        }
        return "OTHER";
    }

    private String primaryBlocker(List<String> blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return "none";
        }
        return tagValue(blockers.get(0), "unknown");
    }

    private String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private enum TargetField {
        PROVIDER,
        ENVIRONMENT,
        ACCOUNT,
        MARKET
    }
}
