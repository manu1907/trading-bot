package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DemoBurnInEvidenceCollectorTest {

    private static final String SCRIPT = "ops/evidence/collect-demo-burn-in-evidence.sh";

    @TempDir
    Path tempDir;

    @Test
    void collector_generates_sanitized_demo_burn_in_bundle_from_committed_contracts() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path output = tempDir.resolve("burn-in-evidence");
        Path releaseEvidenceDir = tempDir.resolve("release-evidence");
        Files.createDirectories(releaseEvidenceDir);
        Files.writeString(releaseEvidenceDir.resolve("live-release-evidence.yml"), "schema_version: 1\nsecret_values_collected: false\n");
        Path marketUniverse = writeObservation("market-universe.txt", "symbols_analyzed=BTCUSDT,ETHUSDT,SOLUSDT\nbtc_only_run=false\n");
        Path continuousMetrics = writeObservation("continuous-metrics.txt", "uptime_percent=99.9\nunknown_order_results_count=0\n");
        Path drills = writeObservation("drills.txt", "emergency_stop=completed\nrollback=completed\n");

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "--output-dir",
                output.toString(),
                "--burn-in-id",
                "test-burn-in",
                "--operator",
                "ci",
                "--started-at",
                "2026-06-20T00:00:00Z",
                "--ended-at",
                "2026-06-20T06:00:00Z",
                "--duration-hours",
                "6",
                "--release-evidence-dir",
                releaseEvidenceDir.toString(),
                "--market-universe-file",
                marketUniverse.toString(),
                "--continuous-metrics-file",
                continuousMetrics.toString(),
                "--drills-file",
                drills.toString()
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(exited).as(outputText).isTrue();
        assertThat(process.exitValue()).as(outputText).isZero();
        assertThat(outputText).contains("Demo burn-in evidence bundle written to");

        Map<String, Object> evidence = yaml(output.resolve("demo-burn-in-evidence.yml"));
        assertThat(evidence)
                .containsEntry("schema_version", 1)
                .containsEntry("evidence_type", "demo_burn_in_collected");
        assertThat(map(evidence, "burn_in"))
                .containsEntry("provider", "binance")
                .containsEntry("environment", "demo")
                .containsEntry("account", "main")
                .containsEntry("market", "usdm_futures")
                .containsEntry("runtime_target", "binance/demo/main/usdm_futures")
                .containsEntry("cloud_run_service", "trading-bot-demo-main-usdm-futures")
                .containsEntry("burn_in_id", "test-burn-in")
                .containsEntry("duration_hours", 6);
        assertThat(map(evidence, "behavior_equivalence"))
                .containsEntry("same_codebase_as_real", "true_required")
                .containsEntry("same_strategy_behavior_intended_for_real", "true_required")
                .containsEntry("same_remediation_behavior_intended_for_real", "true_required")
                .containsEntry("same_symbol_universe_policy_intended_for_real", "true_required")
                .containsEntry("reduced_demo_behavior", "false_required_for_real_promotion");
        assertThat(map(evidence, "market_universe"))
                .containsEntry("copied_market_universe_file", "market-universe-evidence.txt")
                .containsEntry("same_universe_policy_as_intended_real", "true_required");
        assertThat(map(evidence, "continuous_operation_metrics"))
                .containsEntry("copied_continuous_metrics_file", "continuous-operation-metrics.txt")
                .containsEntry("unknown_order_results_count", "0_required_for_promotion");
        assertThat(map(evidence, "required_drills"))
                .containsEntry("copied_drills_file", "required-drills-evidence.txt");
        assertThat(map(evidence, "promotion_readiness"))
                .containsEntry("real_exchange_execution_remains_disabled_until_approval", "true_required")
                .containsEntry("promote_to_real_execution", "false_default")
                .containsEntry("secret_values_collected", false)
                .containsEntry("no_secret_values_in_evidence", "true_required");

        String checksums = Files.readString(output.resolve("checksums.sha256"));
        assertThat(checksums)
                .contains("config/catalog.json")
                .contains("config/runtime/live/binance/demo/main/usdm_futures.json")
                .contains("ops/google-cloud/demo-usdm-futures-deployment.yml")
                .contains("ops/google-cloud/real-usdm-futures-deployment.yml")
                .contains("ops/evidence/demo-burn-in-evidence-template.yml");
        assertThat(Files.readString(output.resolve("release-evidence-manifest.tsv")))
                .contains("live-release-evidence.yml")
                .doesNotContain("jdbc:postgresql://")
                .doesNotContain("hooks.slack.com");
    }

    @Test
    void collector_contract_documents_offline_operation_and_secret_refusal() throws IOException {
        String script = Files.readString(resolve(SCRIPT));
        String readme = Files.readString(resolve("ops/evidence/README.md"));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("The script is intentionally offline-first")
                .contains("binance/demo/main/usdm_futures")
                .contains("same_codebase_as_real: true_required")
                .contains("same_symbol_universe_policy_intended_for_real: true_required")
                .contains("real_exchange_execution_remains_disabled_until_approval: true_required")
                .contains("promote_to_real_execution: false_default")
                .contains("refusing to copy possible secret-bearing file");

        assertThat(readme)
                .contains("collect-demo-burn-in-evidence.sh")
                .contains("demo burn-in collector")
                .contains("promotion-blocking by default");
    }

    @Test
    void collector_and_docs_do_not_inline_personal_receivers() throws IOException {
        for (String path : List.of(SCRIPT, "ops/evidence/README.md")) {
            String content = Files.readString(resolve(path));

            assertThat(content)
                    .doesNotContain("euggio1907")
                    .doesNotContain("@gmail.com");
        }
    }

    private Path writeObservation(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
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
