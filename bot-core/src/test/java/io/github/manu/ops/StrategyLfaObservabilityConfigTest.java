package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyLfaObservabilityConfigTest {

    @Test
    void strategy_lfa_alerts_cover_runner_disabled_blocked_and_published_outcomes() throws IOException {
        Map<String, Object> alerts = yaml("ops/prometheus/strategy-lfa-alerts.yml");
        Set<String> alertNames = alertNames(alerts);

        assertThat(alertNames).containsExactlyInAnyOrder(
                "LfaSignalRunnerDisabled",
                "LfaSignalRunnerLifecycleBlocked",
                "LfaSignalRunnerReconciliationBlocked",
                "LfaSignalRunnerBudgetBlocked",
                "LfaSignalRunnerAllocationBlocked",
                "LfaSignalRunnerPublishedSignals"
        );

        String content = Files.readString(resolve("ops/prometheus/strategy-lfa-alerts.yml"));
        assertThat(content)
                .contains("trading_strategy_lfa_signal_runner_run_events_total")
                .contains("status=\"DISABLED\"")
                .contains("status=\"BLOCKED\"")
                .contains("status=\"PUBLISHED\"")
                .contains("reason=\"lfa_signal_runner:lifecycle_blocked\"")
                .contains("reason=\"lfa_signal_runner:reconciliation_blocked\"")
                .contains("reason=\"lfa_signal_runner:budget_blocked\"")
                .contains("reason=\"lfa_signal_runner:allocation_blocked\"")
                .contains("primary_blocker")
                .contains("routing_hint: operator")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("@gmail.com")
                .doesNotContain("-----BEGIN");
    }

    @Test
    void strategy_lfa_metric_and_docs_describe_bounded_operational_labels() throws IOException {
        String metrics = Files.readString(resolve(
                "bot-strategy-lfa/src/main/java/io/github/manu/strategy/lfa/LfaSignalRunnerMetrics.java"
        ));
        String readme = Files.readString(resolve("ops/prometheus/README.md"));

        assertThat(metrics)
                .contains("trading.strategy.lfa.signal_runner.run.events")
                .contains(".tag(\"provider\"")
                .contains(".tag(\"environment\"")
                .contains(".tag(\"account\"")
                .contains(".tag(\"market\"")
                .contains(".tag(\"enabled\"")
                .contains(".tag(\"status\"")
                .contains(".tag(\"reason\"")
                .contains(".tag(\"primary_blocker\"")
                .contains("DISABLED")
                .contains("BLOCKED")
                .contains("PUBLISHED");
        assertThat(readme)
                .contains("## Strategy LFA Alerts")
                .contains("trading_strategy_lfa_signal_runner_run_events_total")
                .contains("They do not claim profitability");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String path) throws IOException {
        try (var reader = Files.newBufferedReader(resolve(path))) {
            return new Yaml().loadAs(reader, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> alertNames(Map<String, Object> alerts) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) alerts.get("groups");
        return groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream())
                .map(rule -> (String) rule.get("alert"))
                .collect(Collectors.toSet());
    }

    private Path resolve(String path) {
        Path cwd = Path.of(path);
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path parent = Path.of("..").resolve(path).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }
}
