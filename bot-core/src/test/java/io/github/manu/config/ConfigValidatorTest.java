package io.github.manu.config;

import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exception.ConfigValidationException;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ExchangeModuleFactory;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void accepts_valid_active_target_and_delegates_provider_validation() {
        AtomicBoolean validated = new AtomicBoolean(false);
        ConfigValidator validator = validator(new StubExchangeModule("binance", validated));

        validator.validate(validConfig());

        assertThat(validated).isTrue();
    }

    @Test
    void rejects_unsupported_config_version() {
        ConfigValidator validator = validator(new StubExchangeModule("binance"));

        assertThatThrownBy(() -> validator.validate(config(2, true, true, true, true)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Unsupported config version 2; expected 1");
    }

    @Test
    void rejects_unknown_exchange_module() {
        ConfigValidator validator = validator();

        assertThatThrownBy(() -> validator.validate(validConfig()))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("No module registered for exchange 'binance'");
    }

    @Test
    void rejects_disabled_active_market() {
        ConfigValidator validator = validator(new StubExchangeModule("binance"));

        assertThatThrownBy(() -> validator.validate(config(1, true, true, true, false)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Active config path is disabled")
                .hasMessageContaining("markets.usdm_futures");
    }

    @Test
    void wraps_provider_specific_validation_errors() {
        ConfigValidator validator = validator(new RejectingExchangeModule("binance"));

        assertThatThrownBy(() -> validator.validate(validConfig()))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Invalid provider config for exchange 'binance': missing recvWindow");
    }

    private ConfigValidator validator(ExchangeModule... modules) {
        return new ConfigValidator(new ExchangeModuleFactory(List.of(modules)));
    }

    private TradingBotProperties validConfig() {
        return config(1, true, true, true, true);
    }

    private TradingBotProperties config(int version,
                                        boolean providerEnabled,
                                        boolean environmentEnabled,
                                        boolean accountEnabled,
                                        boolean marketEnabled) {
        try {
            ObjectNode root = jsonMapper.createObjectNode();
            root.put("version", version);
            ObjectNode bot = root.putObject("bot");
            bot.put("instance_id", "test-bot");
            bot.put("target_id", "binance_demo_main_usdm_futures");
            bot.put("timezone", "UTC");

            ObjectNode exchange = root.putObject("exchange");
            ObjectNode active = exchange.putObject("active");
            active.put("provider", "binance");
            active.put("environment", "demo");
            active.put("account", "main");
            active.put("market", "usdm_futures");

            ObjectNode provider = exchange.putObject("providers").putObject("binance");
            provider.put("enabled", providerEnabled);
            ObjectNode environment = provider.putObject("environments").putObject("demo");
            environment.put("enabled", environmentEnabled);
            ObjectNode account = environment.putObject("accounts").putObject("main");
            account.put("enabled", accountEnabled);
            ObjectNode market = account.putObject("markets").putObject("usdm_futures");
            market.put("enabled", marketEnabled);

            return jsonMapper.treeToValue(root, TradingBotProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test config", e);
        }
    }

    private record StubExchangeModule(String provider, AtomicBoolean validated) implements ExchangeModule {

        private StubExchangeModule(String provider) {
            this(provider, new AtomicBoolean(false));
        }

        @Override
        public void validateConfig(ResolvedExchangeConfig config) {
            validated.set(true);
        }

        @Override
        public void configure(ResolvedExchangeConfig config) {
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private record RejectingExchangeModule(String provider) implements ExchangeModule {

        @Override
        public void validateConfig(ResolvedExchangeConfig config) {
            throw new IllegalArgumentException("missing recvWindow");
        }

        @Override
        public void configure(ResolvedExchangeConfig config) {
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
