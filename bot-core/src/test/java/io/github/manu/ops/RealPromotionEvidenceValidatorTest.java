package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RealPromotionEvidenceValidatorTest {

    private static final String SCRIPT = "ops/evidence/validate-real-promotion-evidence.sh";

    @TempDir
    Path tempDir;

    @Test
    void validator_passes_only_completed_same_codebase_demo_and_real_promotion_evidence() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path demoBurnIn = tempDir.resolve("demo-burn-in.yml");
        Path demoRelease = tempDir.resolve("demo-release.yml");
        Path realRelease = tempDir.resolve("real-release.yml");
        Path report = tempDir.resolve("reports/real-promotion-validation.md");
        Files.writeString(demoBurnIn, completedDemoBurnInEvidence(), StandardCharsets.UTF_8);
        Files.writeString(demoRelease, completedReleaseEvidence("demo"), StandardCharsets.UTF_8);
        Files.writeString(realRelease, completedReleaseEvidence("real"), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "--demo-burn-in-file",
                demoBurnIn.toString(),
                "--demo-release-file",
                demoRelease.toString(),
                "--real-release-file",
                realRelease.toString(),
                "--output-file",
                report.toString()
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(exited).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("Status: PASS");
        assertThat(Files.readString(report))
                .contains("Real Promotion Evidence Validation")
                .contains("Status: PASS")
                .contains("real execution config change must still be made through the guarded deployment path");
    }

    @Test
    void validator_blocks_placeholders_reduced_demo_behavior_and_missing_required_evidence() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path demoBurnIn = tempDir.resolve("blocked-demo-burn-in.yml");
        Path demoRelease = tempDir.resolve("blocked-demo-release.yml");
        Path realRelease = tempDir.resolve("blocked-real-release.yml");
        Path report = tempDir.resolve("reports/blocked-validation.md");
        Files.writeString(
                demoBurnIn,
                """
                evidence_type: demo_burn_in_collected
                environment: demo
                runtime_target: binance/demo/main/usdm_futures
                same_codebase_as_real: true
                same_strategy_behavior_intended_for_real: true_or_false
                same_remediation_behavior_intended_for_real: true
                same_symbol_universe_policy_intended_for_real: true
                reduced_demo_behavior: true
                btc_only_run: true
                unknown_order_results_count: 1
                promote_to_real_execution: false_default
                """,
                StandardCharsets.UTF_8);
        Files.writeString(demoRelease, completedReleaseEvidence("demo").replace("readiness_status: UP", "readiness_status: DOWN"), StandardCharsets.UTF_8);
        Files.writeString(realRelease, completedReleaseEvidence("real").replace("real_execution_policy_change_requested: true", "real_execution_policy_change_requested: false"), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "--demo-burn-in-file",
                demoBurnIn.toString(),
                "--demo-release-file",
                demoRelease.toString(),
                "--real-release-file",
                realRelease.toString(),
                "--output-file",
                report.toString()
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(exited).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isNotZero();
        assertThat(output)
                .contains("Status: FAIL")
                .contains("demo burn-in evidence still contains unresolved template or promotion-blocking markers")
                .contains("demo burn-in cannot be reduced versus intended real behavior")
                .contains("demo burn-in cannot be BTC-only unless real is intentionally BTC-only")
                .contains("demo release readiness smoke must be UP")
                .contains("real release must explicitly request real execution policy change");
    }

    @Test
    void validator_contract_is_offline_fail_closed_and_has_no_personal_receivers() throws IOException {
        String script = Files.readString(resolve(SCRIPT));
        String readme = Files.readString(resolve("ops/evidence/README.md"));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("Validates that demo burn-in, demo release, and real release evidence")
                .contains("fail-closed")
                .contains("--demo-burn-in-file")
                .contains("--demo-release-file")
                .contains("--real-release-file")
                .contains("reduced_demo_behavior")
                .contains("real_execution_policy_change_requested")
                .contains("This script does not call cloud providers, GitHub, Binance, or Secret Manager");

        assertThat(readme)
                .contains("validate-real-promotion-evidence.sh")
                .contains("real promotion evidence validator");

        for (String path : List.of(SCRIPT, "ops/evidence/README.md")) {
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

    private String completedDemoBurnInEvidence() {
        return """
                evidence_type: demo_burn_in_collected
                burn_in:
                  environment: demo
                  runtime_target: binance/demo/main/usdm_futures
                behavior_equivalence:
                  same_codebase_as_real: true
                  same_strategy_behavior_intended_for_real: true
                  same_remediation_behavior_intended_for_real: true
                  same_symbol_universe_policy_intended_for_real: true
                  reduced_demo_behavior: false
                runtime_stages:
                  strategy_disabled_observation_completed: true
                  strategy_shadow_mode_completed: true
                  bounded_demo_live_trading_completed: true
                  extended_demo_burn_in_completed: true
                market_universe:
                  dynamic_exchange_metadata_refresh_verified: true
                  high_liquidity_candidate_universe_covered: true
                  btc_only_run: false
                continuous_operation_metrics:
                  unknown_order_results_count: 0
                required_drills:
                  restart_during_open_order_completed: true
                  network_outage_or_exchange_timeout_completed: true
                  stale_user_data_stream_completed: true
                  stale_market_data_stream_completed: true
                  external_manual_order_or_position_completed: true
                  bad_config_or_failed_deployment_completed: true
                  emergency_stop_completed: true
                  rollback_completed: true
                observability_and_alerting:
                  alertmanager_routing_verified: true
                  email_slack_pagerduty_receivers_verified: true
                  google_cloud_monitoring_policies_verified: true
                  budget_alert_verified: true
                  dashboards_available: true
                incidents_and_exceptions:
                  unresolved_critical_incidents: 0
                  unresolved_high_risk_exceptions: 0
                promotion_readiness:
                  real_config_diff_reviewed: true
                  real_secret_isolation_verified: true
                  real_exchange_execution_remains_disabled_until_approval: true
                  promote_to_real_execution: true
                  no_secret_values_in_evidence: true
                """;
    }

    private String completedReleaseEvidence(String environment) {
        return """
                evidence_type: live_release_collected
                release:
                  environment: ENVIRONMENT
                code_identity:
                  security_workflow_conclusion: success
                configuration:
                  runtime_target: binance/ENVIRONMENT/main/usdm_futures
                  same_codebase_as_demo_and_real: true
                  intended_real_behavior_pretested_in_demo: true
                  demo_behavior_reduced_vs_real: false
                secret_bindings:
                  secret_values_collected: false
                  no_secret_values_in_evidence: true
                smoke_and_live_validation:
                  readiness_status: UP
                  binance_server_time_smoke: pass
                  binance_order_endpoint_smoke: pass
                  binance_user_data_stream_smoke: pass
                  binance_market_data_websocket_smoke: pass
                  no_unintended_exchange_action_on_startup: true
                trading_state:
                  reconciliation_confidence: healthy
                  unknown_order_results_count: 0
                  pending_modify_outcomes_count: 0
                risk_and_policy:
                  real_environment_allowed: false
                  account_risk_caps_reviewed: true
                  symbol_risk_caps_reviewed: true
                  loss_drawdown_caps_reviewed: true
                observability:
                  google_cloud_monitoring_policies_verified: true
                  budget_alert_verified: true
                  incident_response_runbook_available: true
                rollback_and_emergency:
                  no_second_bot_instance_verified: true
                promotion_decision:
                  promote_to_next_stage: true
                  next_stage: real_execution_enabled
                  real_execution_policy_change_requested: true
                """.replace("ENVIRONMENT", environment);
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
