package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalEvidenceTemplateTest {

    @Test
    void live_release_template_requires_traceable_release_deployment_smoke_and_state_evidence() throws IOException {
        Map<String, Object> evidence = yaml("ops/evidence/live-release-evidence-template.yml");

        assertThat(evidence)
                .containsEntry("schema_version", 1)
                .containsEntry("evidence_type", "live_release");
        assertThat(evidence.keySet()).contains(
                "release",
                "code_identity",
                "image_and_deployment",
                "configuration",
                "secret_bindings",
                "pre_deploy_gates",
                "smoke_and_live_validation",
                "trading_state",
                "risk_and_policy",
                "observability",
                "rollback_and_emergency",
                "incidents_and_exceptions",
                "promotion_decision"
        );
        assertThat(map(evidence, "code_identity"))
                .containsEntry("git_branch", "main")
                .containsEntry("security_workflow_conclusion", "success_required");
        assertThat(map(evidence, "configuration"))
                .containsEntry("same_codebase_as_demo_and_real", true)
                .containsEntry("demo_behavior_reduced_vs_real", "false_required_for_real_promotion");
        assertThat(map(evidence, "pre_deploy_gates"))
                .containsEntry("security_workflow_passed", "true_required")
                .containsEntry("real_exchange_execution_disabled_unless_promotion_evidence_passed", "true_required_for_real");
        assertThat(map(evidence, "smoke_and_live_validation"))
                .containsEntry("readiness_status", "UP_required")
                .containsEntry("binance_server_time_smoke", "pass_required")
                .containsEntry("binance_order_endpoint_smoke", "pass_required")
                .containsEntry("binance_user_data_stream_smoke", "pass_required")
                .containsEntry("binance_market_data_websocket_smoke", "pass_required")
                .containsEntry("no_unintended_exchange_action_on_startup", "true_required");
        assertThat(map(evidence, "trading_state"))
                .containsEntry("reconciliation_confidence", "healthy_required_before_execution")
                .containsEntry("unknown_order_results_count", "0_required_for_promotion")
                .containsEntry("pending_modify_outcomes_count", "0_required_for_promotion");
        assertThat(map(evidence, "rollback_and_emergency"))
                .containsEntry("no_second_bot_instance_verified", "true_required");
    }

    @Test
    void demo_burn_in_template_requires_same_real_behavior_universe_metrics_and_drills() throws IOException {
        Map<String, Object> evidence = yaml("ops/evidence/demo-burn-in-evidence-template.yml");

        assertThat(evidence)
                .containsEntry("schema_version", 1)
                .containsEntry("evidence_type", "demo_burn_in");
        assertThat(evidence.keySet()).contains(
                "burn_in",
                "behavior_equivalence",
                "runtime_stages",
                "market_universe",
                "continuous_operation_metrics",
                "trading_metrics",
                "required_drills",
                "observability_and_alerting",
                "promotion_readiness"
        );
        assertThat(map(evidence, "behavior_equivalence"))
                .containsEntry("same_codebase_as_real", "true_required")
                .containsEntry("same_strategy_behavior_intended_for_real", "true_required")
                .containsEntry("same_remediation_behavior_intended_for_real", "true_required")
                .containsEntry("same_symbol_universe_policy_intended_for_real", "true_required")
                .containsEntry("reduced_demo_behavior", "false_required_for_real_promotion");
        assertThat(map(evidence, "market_universe"))
                .containsEntry("dynamic_exchange_metadata_refresh_verified", "true_or_false")
                .containsEntry("high_liquidity_candidate_universe_covered", "true_or_false")
                .containsEntry("btc_only_run", "false_required_for_real_promotion_unless_real_is_btc_only");
        assertThat(map(evidence, "continuous_operation_metrics"))
                .containsEntry("unknown_order_results_count", "0_required_for_promotion")
                .containsKey("reconciliation_degraded_incidents");
        assertThat(map(evidence, "required_drills").keySet()).contains(
                "restart_during_open_order",
                "network_outage_or_exchange_timeout",
                "stale_user_data_stream",
                "stale_market_data_stream",
                "external_manual_order_or_position",
                "bad_config_or_failed_deployment",
                "emergency_stop",
                "rollback"
        );
        assertThat(map(evidence, "promotion_readiness"))
                .containsEntry("real_exchange_execution_remains_disabled_until_approval", "true_required")
                .containsEntry("promote_to_real_execution", "false_default");
    }

    @Test
    void evidence_templates_and_readme_do_not_inline_secret_values_or_personal_receivers() throws IOException {
        for (String path : List.of(
                "ops/evidence/README.md",
                "ops/evidence/live-release-evidence-template.yml",
                "ops/evidence/demo-burn-in-evidence-template.yml"
        )) {
            String content = Files.readString(resolve(path));

            assertThat(content)
                    .doesNotContain("euggio1907")
                    .doesNotContain("@gmail.com")
                    .doesNotContain("hooks.slack.com")
                    .doesNotContain("xoxb-")
                    .doesNotContain("ghp_")
                    .doesNotContain("-----BEGIN")
                    .doesNotContain("jdbc:postgresql://");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String path) throws IOException {
        try (var reader = Files.newBufferedReader(resolve(path))) {
            return new Yaml().loadAs(reader, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
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
