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

class StrategyLfaGrafanaDashboardTest {

    private static final String DASHBOARD = "ops/grafana/strategy-lfa-dashboard.json";
    private static final String METRIC = "trading_strategy_lfa_signal_runner_run_events_total";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void strategy_lfa_dashboard_is_importable_and_tracks_runner_outcomes() throws IOException {
        JsonNode dashboard = jsonMapper.readTree(resolve(DASHBOARD).toFile());

        assertThat(dashboard.get("uid").asString()).isEqualTo("trading-bot-strategy-lfa");
        assertThat(dashboard.get("title").asString()).isEqualTo("Trading Bot - LFA Strategy Runner");
        assertThat(dashboard.get("__inputs").get(0).get("pluginId").asString()).isEqualTo("prometheus");

        Set<String> titles = new HashSet<>();
        Set<String> expressions = new HashSet<>();
        for (JsonNode panel : dashboard.get("panels")) {
            titles.add(panel.get("title").asString());
            panel.get("targets").forEach(target -> expressions.add(target.get("expr").asString()));
        }

        assertThat(titles).contains(
                "Runner Outcomes By Status",
                "Published Signal Runs",
                "Blocked Signal Runs",
                "Runner Outcome Rate By Status",
                "Blocked Outcomes By Reason",
                "Primary Blockers",
                "Lifecycle And Reconciliation Blocks",
                "Budget And Allocation Blocks",
                "Published Outcomes By Target",
                "Disabled Evaluations"
        );
        assertThat(expressions).allSatisfy(expression -> assertThat(expression).contains(METRIC));
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("primary_blocker"))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("status=\"PUBLISHED\""))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("status=\"BLOCKED\""))).isTrue();
        assertThat(expressions.stream().anyMatch(expression -> expression.contains("status=\"DISABLED\""))).isTrue();
    }

    @Test
    void strategy_lfa_dashboard_and_readme_preserve_low_cardinality_contract() throws IOException {
        String dashboard = Files.readString(resolve(DASHBOARD));
        String readme = Files.readString(resolve("ops/grafana/README.md"));

        assertThat(dashboard)
                .contains("ops/prometheus/strategy-lfa-alerts.yml")
                .contains("environment")
                .contains("account")
                .contains("market")
                .contains("status")
                .contains("reason")
                .contains("primary_blocker")
                .doesNotContain("signal_id")
                .doesNotContain("client_order_id")
                .doesNotContain("exchange_order_id")
                .doesNotContain("symbol=~")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("@gmail.com")
                .doesNotContain("-----BEGIN");
        assertThat(readme)
                .contains("## Strategy LFA Dashboard")
                .contains("strategy-lfa-dashboard.json")
                .contains("LFA signal-runner outcomes")
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
