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

class LiveReleaseEvidenceCollectorTest {

    private static final String SCRIPT = "ops/evidence/collect-live-release-evidence.sh";

    @TempDir
    Path tempDir;

    @Test
    void collector_generates_sanitized_demo_release_bundle_from_committed_contracts() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path output = tempDir.resolve("demo-evidence");

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "demo",
                "--output-dir",
                output.toString(),
                "--release-id",
                "test-release",
                "--operator",
                "ci",
                "--decision",
                "keep_disabled",
                "--security-workflow-run-id",
                "123",
                "--smoke-workflow-run-id",
                "456",
                "--cloud-run-region",
                "europe-west1",
                "--cloud-run-revision",
                "trading-bot-demo-main-usdm-futures-00001",
                "--revision-commit-label",
                "0123456789012345678901234567890123456789"
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(exited).as(outputText).isTrue();
        assertThat(process.exitValue()).as(outputText).isZero();
        assertThat(outputText).contains("Evidence bundle written to");

        Map<String, Object> evidence = yaml(output.resolve("live-release-evidence.yml"));
        assertThat(evidence)
                .containsEntry("schema_version", 1)
                .containsEntry("evidence_type", "live_release_collected");
        assertThat(map(evidence, "release"))
                .containsEntry("environment", "demo")
                .containsEntry("provider", "binance")
                .containsEntry("account", "main")
                .containsEntry("market", "usdm_futures")
                .containsEntry("decision", "keep_disabled");
        assertThat(map(evidence, "image_and_deployment"))
                .containsEntry("cloud_run_service", "trading-bot-demo-main-usdm-futures")
                .containsEntry("cloud_run_revision", "trading-bot-demo-main-usdm-futures-00001");
        assertThat(map(evidence, "configuration"))
                .containsEntry("runtime_target", "binance/demo/main/usdm_futures")
                .containsEntry("same_codebase_as_demo_and_real", true)
                .containsEntry("demo_behavior_reduced_vs_real", "false_required_for_real_promotion");
        assertThat(map(evidence, "secret_bindings"))
                .containsEntry("secret_values_collected", false)
                .containsEntry("no_secret_values_in_evidence", "true_required");

        String checksums = Files.readString(output.resolve("checksums.sha256"));
        assertThat(checksums)
                .contains("config/catalog.json")
                .contains("config/runtime/live/binance/demo/main/usdm_futures.json")
                .contains("ops/google-cloud/demo-usdm-futures-deployment.yml")
                .contains("ops/deployment/deployment-contract.yml")
                .contains("ops/evidence/live-release-evidence-template.yml");

        String secretBindings = Files.readString(output.resolve("secret-bindings.tsv"));
        assertThat(secretBindings)
                .contains("BINANCE_DEMO_API_KEY\ttrading-bot-demo-binance-api-key")
                .contains("TRADING_INTERVENTION_OPERATOR_API_OPERATOR_TOKEN\ttrading-bot-demo-operator-token")
                .contains("ALERTMANAGER_TRADING_BOT_OPERATOR_EMAIL_TO\ttrading-bot-demo-alert-operator-email-to")
                .doesNotContain("euggio1907")
                .doesNotContain("@gmail.com")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("jdbc:postgresql://");
    }

    @Test
    void collector_contract_documents_offline_operation_and_secret_refusal() throws IOException {
        String script = Files.readString(resolve(SCRIPT));
        String readme = Files.readString(resolve("ops/evidence/README.md"));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("demo|real")
                .contains("The script is intentionally offline-first")
                .contains("secret_env")
                .contains("alertmanager_secret_substitutions")
                .contains("secret_values_collected: false")
                .contains("no_secret_values_in_evidence: true_required")
                .contains("same_codebase_as_demo_and_real: true")
                .contains("demo_behavior_reduced_vs_real: false_required_for_real_promotion")
                .contains("refusing to copy possible secret-bearing file");

        assertThat(readme)
                .contains("collect-live-release-evidence.sh")
                .contains("The collector is offline-first")
                .contains("It does not call Google Cloud, GitHub, Binance")
                .contains("or Secret Manager")
                .contains("Generated bundles must still be filled with live outcomes");
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
