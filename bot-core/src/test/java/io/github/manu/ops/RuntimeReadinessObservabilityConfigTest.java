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

class RuntimeReadinessObservabilityConfigTest {

    @Test
    void runtime_readiness_alerts_cover_blocked_reconciliation_order_intervention_pause_and_market_data_states()
            throws IOException {
        Map<String, Object> alerts = yaml("ops/prometheus/runtime-readiness-alerts.yml");
        Set<String> alertNames = alertNames(alerts);

        assertThat(alertNames).containsExactlyInAnyOrder(
                "RuntimeReadinessBlocked",
                "RuntimeReconciliationNotConfident",
                "RuntimeUnsafeOrderState",
                "RuntimeExternalInterventionDetected",
                "RuntimeActivePause",
                "RuntimeMarketDataFreshnessLow"
        );

        String content = Files.readString(resolve("ops/prometheus/runtime-readiness-alerts.yml"));
        assertThat(content)
                .contains("trading_runtime_readiness_states")
                .contains("trading_runtime_reconciliation_states")
                .contains("trading_runtime_projection_states")
                .contains("trading_runtime_blocker_states")
                .contains("readiness=\"BLOCKED\"")
                .contains("status=~\"NO_OBSERVATIONS|DEGRADED\"")
                .contains("unknown_order_statuses|unresolved_order_commands")
                .contains("external_order_interventions|external_position_interventions")
                .contains("market_data:no_symbols|market_data:fresh_symbols_below_minimum")
                .contains("routing_hint: operator")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("@gmail.com")
                .doesNotContain("-----BEGIN");
    }

    @Test
    void runtime_readiness_metrics_and_docs_use_bounded_labels() throws IOException {
        String metrics = Files.readString(resolve(
                "bot-core/src/main/java/io/github/manu/observability/RuntimeStatusGaugeBinder.java"
        ));
        String readme = Files.readString(resolve("ops/prometheus/README.md"));

        assertThat(metrics)
                .contains("trading.runtime.readiness.states")
                .contains("trading.runtime.blocker.states")
                .contains("trading.runtime.projection.states")
                .contains("trading.runtime.market_data.states")
                .contains("trading.runtime.reconciliation.states")
                .contains(".tag(\"readiness\"")
                .contains(".tag(\"blocker\"")
                .contains(".tag(\"kind\"")
                .contains(".tag(\"status\"")
                .contains("orders:unknown_status")
                .contains("market_data:fresh_symbols_below_minimum")
                .doesNotContain("clientOrderId")
                .doesNotContain("exchangeOrderId");
        assertThat(readme)
                .contains("## Runtime Readiness Alerts")
                .contains("runtime-readiness-alerts.yml")
                .contains("trading_runtime_readiness_states")
                .contains("low-cardinality");
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
