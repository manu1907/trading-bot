package io.github.manu.config.service;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.ConfigLoader;
import io.github.manu.config.ConfigReloadPolicy;
import io.github.manu.config.ConfigValidator;
import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exception.ConfigValidationException;
import io.github.manu.exchange.runtime.ExchangeManager;
import io.github.manu.runtime.RuntimeDescriptor;
import io.github.manu.runtime.RuntimeIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigReloadServiceTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void applies_valid_mutable_reload_and_updates_active_config() {
        TestContext context = testContext();
        TradingBotProperties current = config(builder -> {
        });
        TradingBotProperties candidate = config(builder -> {
        });
        context.configManager.setConfig(current);
        when(context.configLoader.loadBaseline(RuntimeProfile.LIVE)).thenReturn(candidate);
        when(context.runtimeIdentityService.apply(current)).thenReturn(descriptor(current));
        when(context.runtimeIdentityService.apply(candidate)).thenReturn(descriptor(candidate));

        context.reloadService.reloadFromProfileFile();

        assertThat(context.configManager.getConfig()).isSameAs(candidate);
        verify(context.exchangeManager).reconfigureActiveRuntime(candidate);
        verify(context.auditLogger).configurationReloaded(descriptor(candidate));
    }

    @Test
    void applies_first_reload_when_no_current_config_exists() {
        TestContext context = testContext();
        TradingBotProperties candidate = config(builder -> {
        });
        when(context.configLoader.loadBaseline(RuntimeProfile.LIVE)).thenReturn(candidate);
        when(context.runtimeIdentityService.apply(candidate)).thenReturn(descriptor(candidate));

        context.reloadService.reloadFromProfileFile();

        assertThat(context.configManager.getConfig()).isSameAs(candidate);
        verify(context.exchangeManager).reconfigureActiveRuntime(candidate);
        verify(context.auditLogger).configurationReloaded(descriptor(candidate));
    }

    @Test
    void rejects_restart_required_candidate_and_preserves_current_config() {
        TestContext context = testContext();
        TradingBotProperties current = config(builder -> {
        });
        TradingBotProperties candidate = config(builder -> builder.activeMarketRest().put("base_url", "https://changed.example.test"));
        RuntimeDescriptor descriptor = descriptor(current);
        context.configManager.setConfig(current);
        when(context.configLoader.loadBaseline(RuntimeProfile.LIVE)).thenReturn(candidate);
        when(context.runtimeIdentityService.apply(current)).thenReturn(descriptor);

        context.reloadService.reloadFromProfileFile();

        assertThat(context.configManager.getConfig()).isSameAs(current);
        verify(context.exchangeManager, never()).reconfigureActiveRuntime(candidate);
        verify(context.auditLogger).configurationReloadRejected(
                descriptor,
                "exchange session settings changed: credentials/endpoints/transport settings require restart"
        );
    }

    @Test
    void validation_failure_preserves_current_config() {
        TestContext context = testContext();
        TradingBotProperties current = config(builder -> {
        });
        TradingBotProperties candidate = config(builder -> {
        });
        context.configManager.setConfig(current);
        when(context.configLoader.loadBaseline(RuntimeProfile.LIVE)).thenReturn(candidate);
        doThrow(new ConfigValidationException("invalid config")).when(context.configValidator).validate(candidate);

        context.reloadService.reloadFromProfileFile();

        assertThat(context.configManager.getConfig()).isSameAs(current);
        verify(context.exchangeManager, never()).reconfigureActiveRuntime(candidate);
        verify(context.auditLogger, never()).configurationReloaded(descriptor(candidate));
    }

    private TestContext testContext() {
        ConfigManager configManager = new ConfigManager();
        ExchangeManager exchangeManager = mock(ExchangeManager.class);
        ConfigValidator configValidator = mock(ConfigValidator.class);
        ConfigLoader configLoader = mock(ConfigLoader.class);
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("live");
        ActiveProfileProvider profileProvider = new ActiveProfileProvider(environment);
        RuntimeIdentityService runtimeIdentityService = mock(RuntimeIdentityService.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        ConfigReloadPolicy reloadPolicy = new ConfigReloadPolicy();
        ConfigReloadService reloadService = new ConfigReloadService(
                configManager,
                exchangeManager,
                configValidator,
                configLoader,
                profileProvider,
                runtimeIdentityService,
                auditLogger,
                reloadPolicy
        );
        return new TestContext(
                configManager,
                exchangeManager,
                configValidator,
                configLoader,
                runtimeIdentityService,
                auditLogger,
                reloadService
        );
    }

    private TradingBotProperties config(ConfigCustomizer customizer) {
        try {
            ConfigBuilder builder = new ConfigBuilder();
            customizer.customize(builder);
            return jsonMapper.treeToValue(builder.root(), TradingBotProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build reload service test config", e);
        }
    }

    private RuntimeDescriptor descriptor(TradingBotProperties config) {
        return new RuntimeDescriptor(
                config.getBot().targetId(),
                config.getBot().instanceId(),
                config.getExchange().provider(),
                config.getExchange().environment(),
                config.getExchange().account(),
                config.getExchange().market(),
                RuntimeProfile.LIVE.id(),
                config.getVersion(),
                "file",
                "test"
        );
    }

    private interface ConfigCustomizer {
        void customize(ConfigBuilder builder);
    }

    private final class ConfigBuilder {
        private final ObjectNode root = jsonMapper.createObjectNode();
        private final ObjectNode activeMarketRest;

        private ConfigBuilder() {
            root.put("version", 1);
            ObjectNode schema = root.putObject("schema");
            schema.put("id", "io.github.manu.trading-bot.config");
            schema.put("version", 1);
            schema.put("migration_policy", "fail_fast");

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

        private ObjectNode activeMarketRest() {
            return activeMarketRest;
        }
    }

    private record TestContext(
            ConfigManager configManager,
            ExchangeManager exchangeManager,
            ConfigValidator configValidator,
            ConfigLoader configLoader,
            RuntimeIdentityService runtimeIdentityService,
            AuditLogger auditLogger,
            ConfigReloadService reloadService
    ) {
    }
}
