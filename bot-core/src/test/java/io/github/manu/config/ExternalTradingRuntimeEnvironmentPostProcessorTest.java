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
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.initial-delay-millis", Long.class))
                .isEqualTo(10000L);
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.provider")).isEqualTo("binance");
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.environment")).isEqualTo("demo");
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.account")).isEqualTo("main");
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.market")).isEqualTo("usdm_futures");
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.target-quantity")).isEqualTo("0.001");
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.max-account-open-positions", Integer.class))
                .isEqualTo(3);
        assertThat(environment.getProperty("trading.strategy.lfa.signal-runner.max-symbol-open-positions", Integer.class))
                .isEqualTo(1);
        assertThat(environment.getProperty("trading.execution.pipeline.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("trading.execution.signal-planner.defaults.symbol")).isEqualTo("BTCUSDT");
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.enabled",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty("trading.execution.signal-planner.instrument-universe.included-symbols[0]"))
                .isEqualTo("BTCUSDT");
        assertThat(environment.getProperty("trading.execution.signal-planner.instrument-universe.included-symbols[1]"))
                .isEqualTo("ETHUSDT");
        assertThat(environment.getProperty("trading.execution.signal-planner.instrument-universe.included-symbols[2]"))
                .isEqualTo("BNBUSDT");
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.refresh-exchange-metadata-before-planning",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.require-exchange-metadata",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.require-included-symbol",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.allowed-quote-assets[0]"
        )).isEqualTo("USDT");
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.allowed-contract-types[0]"
        )).isEqualTo("PERPETUAL");
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.max-eligible-symbols",
                Integer.class
        )).isEqualTo(13);
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.min-top-of-book-quote-notional"
        )).isEqualTo("250");
        assertThat(environment.getProperty(
                "trading.execution.signal-planner.instrument-universe.symbol-policies[1].symbol"
        )).isEqualTo("ETHUSDT");
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
        assertThat(environment.getProperty("trading.intervention.remediation-executor-policy.allowed-operations[3]"))
                .isEqualTo("AMEND_ORDER");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.one-way-reduce-only-enabled",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.market"
        )).isEqualTo("usdm_futures");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.position-side"
        )).isEqualTo("BOTH");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.allowed-symbols[0]"
        )).isEqualTo("BTCUSDT");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.allowed-symbols[1]"
        )).isEqualTo("ETHUSDT");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.max-position-quantity"
        )).isEqualTo("0.001");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.chunk-close-when-max-quantity-exceeded",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.max-position-notional"
        )).isEqualTo("250");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.required-margin-type"
        )).isEqualTo("cross");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.min-leverage"
        )).isEqualTo("1");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.position-order-policy.max-leverage"
        )).isEqualTo("5");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.enabled",
                Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allowed-symbols[0]"
        )).isEqualTo("BTCUSDT");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allowed-symbols[1]"
        )).isEqualTo("ETHUSDT");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allowed-order-types[0]"
        )).isEqualTo("LIMIT");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allowed-fields[0]"
        )).isEqualTo("PRICE");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allowed-fields[1]"
        )).isEqualTo("QUANTITY");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.allow-quantity-increase",
                Boolean.class
        )).isFalse();
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.max-quantity-decrease-fraction"
        )).isEqualTo("0.50");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.max-price-drift-fraction"
        )).isEqualTo("0.02");
        assertThat(environment.getProperty(
                "trading.intervention.remediation-executor-policy.managed-order-amendment-policy.max-projection-age-millis",
                Long.class
        )).isEqualTo(30000L);
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
