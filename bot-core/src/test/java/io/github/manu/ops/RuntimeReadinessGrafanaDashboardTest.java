package io.github.manu.ops;

import io.github.manu.config.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeReadinessGrafanaDashboardTest {

    private static final String DASHBOARD = "ops/grafana/runtime-readiness-dashboard.json";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void runtime_readiness_dashboard_is_importable_and_tracks_runtime_health() throws IOException {
        JsonNode dashboard = jsonMapper.readTree(resolve(DASHBOARD).toFile());

        assertThat(dashboard.get("uid").asString()).isEqualTo("trading-bot-runtime-readiness");
        assertThat(dashboard.get("title").asString()).isEqualTo("Trading Bot - Runtime Readiness");
        assertThat(dashboard.get("__inputs").get(0).get("pluginId").asString()).isEqualTo("prometheus");

        Set<String> titles = new HashSet<>();
        Set<String> expressions = new HashSet<>();
        for (JsonNode panel : dashboard.get("panels")) {
            titles.add(panel.get("title").asString());
            panel.get("targets").forEach(target -> expressions.add(target.get("expr").asString()));
        }

        assertThat(titles).contains(
                "Runtime Readiness",
                "Reconciliation State",
                "Active Runtime Blockers",
                "Projection Safety Counts",
                "Market Data Symbols",
                "Unsafe Order State",
                "External Interventions",
                "Active Pauses",
                "Market Data Freshness Blockers"
        );
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("trading_runtime_readiness_states"))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("trading_runtime_reconciliation_states"))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("trading_runtime_blocker_states"))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("trading_runtime_projection_states"))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("trading_runtime_market_data_states"))).isTrue();
    }

    @Test
    void runtime_readiness_dashboard_and_readme_preserve_low_cardinality_contract() throws IOException {
        String dashboard = Files.readString(resolve(DASHBOARD));
        String readme = Files.readString(resolve("ops/grafana/README.md"));

        assertThat(dashboard)
                .contains("ops/prometheus/runtime-readiness-alerts.yml")
                .contains("readiness")
                .contains("status")
                .contains("blocker")
                .contains("kind")
                .doesNotContain("signal_id")
                .doesNotContain("client_order_id")
                .doesNotContain("exchange_order_id")
                .doesNotContain("symbol=~")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("@gmail.com")
                .doesNotContain("-----BEGIN");
        assertThat(readme)
                .contains("## Runtime Readiness Dashboard")
                .contains("runtime-readiness-dashboard.json")
                .contains("Runtime readiness")
                .contains("low-cardinality");
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
