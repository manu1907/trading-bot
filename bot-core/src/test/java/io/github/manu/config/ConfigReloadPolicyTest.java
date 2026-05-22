package io.github.manu.config;

import io.github.manu.config.properties.TradingBotProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigReloadPolicyTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final ConfigReloadPolicy reloadPolicy = new ConfigReloadPolicy();

    @Test
    void accepts_first_config_as_mutable_bootstrap() {
        ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(null, config());

        assertThat(decision.restartRequired()).isFalse();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void accepts_unchanged_runtime_config() {
        TradingBotProperties current = config();
        TradingBotProperties candidate = config();

        ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(current, candidate);

        assertThat(decision.restartRequired()).isFalse();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void rejects_active_target_change() {
        TradingBotProperties current = config();
        TradingBotProperties candidate = config(builder -> builder.active().put("account", "subaccount_a"));

        ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(current, candidate);

        assertThat(decision.restartRequired()).isTrue();
        assertThat(decision.reason()).contains("active target changed");
    }

    @Test
    void rejects_bot_identity_change() {
        TradingBotProperties current = config();
        TradingBotProperties candidate = config(builder -> builder.bot().put("target_id", "changed_target"));

        ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(current, candidate);

        assertThat(decision.restartRequired()).isTrue();
        assertThat(decision.reason()).contains("instance_id/target_id/timezone");
    }

    @Test
    void rejects_exchange_session_change() {
        TradingBotProperties current = config();
        TradingBotProperties candidate = config(builder -> builder.activeMarketRest().put("base_url", "https://changed.example.test"));

        ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(current, candidate);

        assertThat(decision.restartRequired()).isTrue();
        assertThat(decision.reason()).contains("exchange session settings changed");
    }

    private TradingBotProperties config() {
        return config(builder -> {
        });
    }

    private TradingBotProperties config(ConfigCustomizer customizer) {
        try {
            ConfigBuilder builder = new ConfigBuilder();
            customizer.customize(builder);
            return jsonMapper.treeToValue(builder.root(), TradingBotProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build reload policy test config", e);
        }
    }

    private interface ConfigCustomizer {
        void customize(ConfigBuilder builder);
    }

    private final class ConfigBuilder {
        private final ObjectNode root = jsonMapper.createObjectNode();
        private final ObjectNode bot;
        private final ObjectNode active;
        private final ObjectNode activeMarketRest;

        private ConfigBuilder() {
            root.put("version", 1);
            bot = root.putObject("bot");
            bot.put("instance_id", "test-bot");
            bot.put("target_id", "binance_demo_main_usdm_futures");
            bot.put("timezone", "UTC");

            ObjectNode exchange = root.putObject("exchange");
            active = exchange.putObject("active");
            active.put("provider", "binance");
            active.put("environment", "demo");
            active.put("account", "main");
            active.put("market", "usdm_futures");

            ObjectNode provider = exchange.putObject("providers").putObject("binance");
            provider.put("enabled", true);
            ObjectNode environment = provider.putObject("environments").putObject("demo");
            environment.put("enabled", true);
            ObjectNode account = environment.putObject("accounts").putObject("main");
            account.put("enabled", true);
            ObjectNode market = account.putObject("markets").putObject("usdm_futures");
            market.put("enabled", true);
            activeMarketRest = market.putObject("rest");
            activeMarketRest.put("base_url", "https://demo-fapi.binance.com");
        }

        private ObjectNode root() {
            return root;
        }

        private ObjectNode bot() {
            return bot;
        }

        private ObjectNode active() {
            return active;
        }

        private ObjectNode activeMarketRest() {
            return activeMarketRest;
        }
    }
}
