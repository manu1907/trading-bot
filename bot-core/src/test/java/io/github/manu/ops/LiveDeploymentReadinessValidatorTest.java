package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LiveDeploymentReadinessValidatorTest {

    private static final String SCRIPT = "ops/google-cloud/validate-live-deployment-readiness.sh";

    @Test
    void validator_is_strict_offline_and_covers_live_deployment_path() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("offline repository preflight")
                .contains("It does not call Google Cloud, GitHub")
                .contains("Binance, or Secret Manager")
                .contains("security.yml")
                .contains("publish-google-cloud-image.yml")
                .contains("migrate-google-cloud-postgresql-state.yml")
                .contains("deploy-google-cloud-cloud-run.yml")
                .contains("smoke-google-cloud-cloud-run.yml")
                .contains("smoke-binance-live.yml")
                .contains("rollback-google-cloud-cloud-run.yml")
                .contains("archive-google-cloud-evidence.yml")
                .contains("archive-google-cloud-journal.yml")
                .contains("validate-real-promotion-evidence.yml")
                .contains("collect-live-release-evidence.sh")
                .contains("collect-demo-burn-in-evidence.sh")
                .contains("validate-real-promotion-evidence.sh")
                .contains("render-google-cloud-alertmanager.sh")
                .contains("migrate-postgresql-state.sh")
                .contains("archive-journal-segments.sh");
    }

    @Test
    void validator_codifies_demo_real_same_codebase_and_real_startup_guards() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("SPRING_PROFILES_ACTIVE: live")
                .contains("BOT_PROVIDER: binance")
                .contains("BOT_ACCOUNT: main")
                .contains("BOT_MARKET: usdm_futures")
                .contains("require_demo_promotion_evidence: true")
                .contains("require_real_secret_isolation: true")
                .contains("real_trading_initial_state: exchange_execution_disabled")
                .contains("allowed_real_operations: []")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ENABLED: \\\"false\\\"")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_EXCHANGE_EXECUTION_ENABLED: \\\"false\\\"")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_REPORT_ONLY: \\\"true\\\"")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ALLOW_REAL_ENVIRONMENT: \\\"false\\\"");
    }

    @Test
    void validator_generates_passing_demo_and_real_reports() throws Exception {
        Path outputDir = Files.createTempDirectory("live-deployment-readiness-");

        Path demoReport = outputDir.resolve("demo.md");
        CommandResult demo = runValidator("demo", demoReport);
        assertThat(demo.exitCode()).as(demo.output()).isZero();
        assertThat(demo.output()).contains("Live deployment readiness: PASS").contains(demoReport.toString());
        assertReport(demoReport, "binance/demo/main/usdm_futures");

        Path realReport = outputDir.resolve("real.md");
        CommandResult real = runValidator("real", realReport);
        assertThat(real.exitCode()).as(real.output()).isZero();
        assertThat(real.output()).contains("Live deployment readiness: PASS").contains(realReport.toString());
        assertReport(realReport, "binance/real/main/usdm_futures");
    }

    @Test
    void validator_rejects_unknown_source_controlled_target() throws Exception {
        Path output = Files.createTempDirectory("live-deployment-readiness-").resolve("unsupported.md");
        Path root = repoRoot();
        Process process = new ProcessBuilder(
                        "bash",
                        root.resolve(SCRIPT).toString(),
                        "--environment",
                        "demo",
                        "--provider",
                        "coinbase",
                        "--output-file",
                        output.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();

        CommandResult result = waitFor(process);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Only binance/main/usdm_futures is currently source-controlled");
        assertThat(output).doesNotExist();
    }

    private void assertReport(Path report, String target) throws IOException {
        assertThat(report).exists();
        assertThat(Files.readString(report))
                .contains("# Live Deployment Readiness Report")
                .contains("- Target: " + target)
                .contains("- Platform: google_cloud/cloud_run")
                .contains("- Overall: PASS")
                .contains("| PASS | workflow: security.yml |")
                .contains("| PASS | real requires demo promotion evidence |")
                .contains("| PASS | runtime stream literals |")
                .contains("It does not prove that Google Cloud resources, Secret Manager versions, Binance connectivity, or GitHub environment values exist.");
    }

    private CommandResult runValidator(String environment, Path outputFile) throws Exception {
        Path root = repoRoot();
        Process process = new ProcessBuilder(
                        "bash",
                        root.resolve(SCRIPT).toString(),
                        "--environment",
                        environment,
                        "--output-file",
                        outputFile.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        return waitFor(process);
    }

    private CommandResult waitFor(Process process) throws Exception {
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(completed).as(output).isTrue();
        return new CommandResult(process.exitValue(), output);
    }

    private Path resolve(String path) {
        Path root = repoRoot();
        Path resolved = root.resolve(path).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }

    private Path repoRoot() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("settings.gradle.kts"))) {
            return cwd;
        }
        Path parent = cwd.resolve("..").normalize();
        if (Files.exists(parent.resolve("settings.gradle.kts"))) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate repository root from " + cwd);
    }

    private record CommandResult(int exitCode, String output) {
    }
}
