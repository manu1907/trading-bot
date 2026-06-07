package io.github.manu.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalTradingRuntimeEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void exposes_checked_in_demo_runtime_trading_overrides_as_spring_properties() throws IOException {
        Path configDir = prepareConfigDir("spring-runtime-overrides");
        StandardEnvironment environment = new StandardEnvironment();

        new ExternalTradingRuntimeEnvironmentPostProcessor(configDir)
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()
                .contains(ExternalTradingRuntimeEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isTrue();
        assertThat(environment.getProperty("trading.journal.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.projection.snapshot-store.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.messaging.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.messaging.consumers.auto-start", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.execution.pipeline.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.execution.signal-planner.defaults.symbol")).isEqualTo("BTCUSDT");
        assertThat(environment.getProperty("trading.intervention.automated-policy.external-order-action"))
                .isEqualTo("CLOSE");
        assertThat(environment.getProperty("trading.intervention.automated-policy.open-position-action"))
                .isEqualTo("CLOSE");
        assertThat(environment.getProperty("trading.intervention.automated-remediation-runner.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("trading.intervention.automated-remediation-runner.target.market"))
                .isEqualTo("usdm_futures");
        assertThat(environment.getProperty("trading.intervention.remediation-executor-policy.report-only", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("trading.intervention.remediation-executor-policy.allowed-operations[0]"))
                .isEqualTo("CANCEL_ORDER");
        assertThat(environment.getProperty("trading.intervention.remediation-executor-policy.allowed-operations[1]"))
                .isEqualTo("CLOSE_POSITION");
        assertThat(environment.getProperty("trading.intervention.remediation-executor-policy.allowed-operations[2]"))
                .isEqualTo("REDUCE_POSITION");
    }

    @Test
    void keeps_higher_priority_environment_values_authoritative() throws IOException {
        Path configDir = prepareConfigDir("spring-runtime-precedence");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "commandLineArgs",
                Map.of("trading.messaging.enabled", "false")
        ));

        new ExternalTradingRuntimeEnvironmentPostProcessor(configDir)
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("trading.messaging.enabled", Boolean.class)).isFalse();
    }

    @Test
    void does_not_add_live_runtime_properties_for_backtest_profile() throws IOException {
        Path configDir = prepareConfigDir("spring-runtime-backtest");
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("backtest");

        new ExternalTradingRuntimeEnvironmentPostProcessor(configDir)
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()
                .contains(ExternalTradingRuntimeEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    private Path prepareConfigDir(String directoryName) throws IOException {
        Path source = resolveRepoConfigDir();
        Path target = Files.createDirectories(tempDir.resolve(directoryName));
        copy(source, target, "catalog.json");
        copy(source, target, "active.json");
        copy(source, target, "application-demo.json");
        copy(source, target, "application-real.json");
        copy(source, target, "runtime/live/binance/demo/main/usdm_futures.json");
        return target;
    }

    private void copy(Path source, Path target, String relativePath) throws IOException {
        Path destination = target.resolve(relativePath);
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source.resolve(relativePath), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveRepoConfigDir() {
        Path cwdConfig = Path.of("config");
        if (Files.exists(cwdConfig.resolve("catalog.json"))) {
            return cwdConfig;
        }

        Path parentConfig = Path.of("..", "config").normalize();
        if (Files.exists(parentConfig.resolve("catalog.json"))) {
            return parentConfig;
        }

        throw new IllegalStateException("Unable to locate repo config directory");
    }
}
