package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LiveAutonomousTradingReadinessValidatorTest {

    private static final String SCRIPT = "ops/autonomous/validate-live-autonomous-trading-readiness.sh";

    @Test
    void validator_is_strict_and_separate_from_deployment_readiness() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("stricter than deployment readiness")
                .contains("does not call Google Cloud, GitHub")
                .contains("configured and implemented enough to claim autonomous live trading readiness")
                .contains("trading.strategy.lfa.signal_runner.enabled=true")
                .contains("max_account_open_order_notional")
                .contains("max_account_position_notional")
                .contains("max_account_unrealized_loss")
                .contains("max_account_daily_realized_loss")
                .contains("PositionManager.java")
                .contains("StrategySignalEvent")
                .contains("require_demo_promotion_evidence: true")
                .contains("real_trading_initial_state: exchange_execution_disabled");
    }

    @Test
    void current_demo_autonomous_readiness_fails_closed_until_full_trading_is_implemented() throws Exception {
        Path report = Files.createTempDirectory("autonomous-readiness-").resolve("demo.md");

        CommandResult result = runValidator("demo", report);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("Live autonomous trading readiness: FAIL");
        String content = Files.readString(report);
        assertThat(content)
                .contains("- Overall: FAIL")
                .contains("LFA signal runner explicitly enabled for demo")
                .contains("demo runtime must explicitly set trading.strategy.lfa.signal_runner.enabled=true")
                .contains("LFA lifecycle active for autonomous demo")
                .contains("account open-order notional cap configured")
                .contains("account position notional cap configured")
                .contains("account unrealized-loss cap configured")
                .contains("account daily realized-loss cap configured")
                .contains("autonomous position lifecycle runner exists")
                .contains("position lifecycle emits strategy exit/reduce signals");
    }

    @Test
    void current_real_autonomous_readiness_also_fails_but_keeps_real_startup_guards() throws Exception {
        Path report = Files.createTempDirectory("autonomous-readiness-").resolve("real.md");

        CommandResult result = runValidator("real", report);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        String content = Files.readString(report);
        assertThat(content)
                .contains("- Overall: FAIL")
                .contains("real requires demo promotion evidence")
                .contains("real starts execution-disabled")
                .contains("autonomous position lifecycle runner exists");
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
