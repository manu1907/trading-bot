package io.github.manu.config;

import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void checked_in_catalog_and_active_must_be_coherent_as_is() throws IOException {
        TestContext context = prepareContext("config-checked-in");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));

        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(loadedConfig.getExchange()).isEqualTo(active);
        assertThat(loadedConfig.getBot().instanceId()).isNotBlank();
        assertThat(loadedConfig.getBot().targetId()).isNotBlank();
        assertThat(loadedConfig.getVersion()).isEqualTo(1);
        assertThat(loadedConfig.getProviders()).isNotNull();
        assertThat(activeRestBaseUrl(loadedConfig, active)).isEqualTo("https://demo-fapi.binance.com");
    }

    @Test
    void environment_config_overrides_catalog_defaults_for_active_target() throws IOException {
        TestContext context = prepareContext("config-environment-precedence");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));

        setActiveMarketRestBaseUrl(context.configDir.resolve("catalog.json"), active, "https://example.invalid");

        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(activeRestBaseUrl(loadedConfig, active)).isEqualTo("https://demo-fapi.binance.com");
    }

    @Test
    void runtime_file_overrides_environment_config_for_active_target() throws IOException {
        TestContext context = prepareContext("config-runtime-file-precedence");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        ObjectNode runtime = activeMarketRestBaseUrlOverride(active, "https://runtime.example.test");
        writeRuntimeFile(context.configDir, active, runtime);

        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(activeRestBaseUrl(loadedConfig, active)).isEqualTo("https://runtime.example.test");
    }

    @Test
    void bot_runtime_overrides_apply_for_the_current_active_target() throws IOException {
        TestContext context = prepareContext("config-bot-runtime");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));

        writeActiveFile(
                context.configDir,
                active,
                botRuntime("demo_runner_a", active.provider() + "_" + active.environment() + "_" + active.account() + "_" + active.market())
        );

        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(loadedConfig.getExchange()).isEqualTo(active);
        assertThat(loadedConfig.getBot().instanceId()).isEqualTo("demo_runner_a");
        assertThat(loadedConfig.getBot().targetId())
                .isEqualTo(active.provider() + "_" + active.environment() + "_" + active.account() + "_" + active.market());
        assertThat(loadedConfig.getBot().timezone()).isEqualTo("UTC");
        assertThat(loadedConfig.getProviders()).isNotNull();
    }

    @Test
    void runtime_can_enable_the_current_active_provider_when_catalog_disables_it() throws IOException {
        TestContext context = prepareContext("config-provider-runtime-enable");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        setCurrentActiveProviderEnabled(context.configDir.resolve("catalog.json"), active, false);

        ObjectNode runtime = botRuntime("demo_runner_a", active.provider() + "_" + active.environment() + "_" + active.account() + "_" + active.market());
        merge(runtime, providerEnabledOverride(active, true));
        writeActiveFile(context.configDir, active, runtime);

        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(loadedConfig.getExchange()).isEqualTo(active);
        assertThat(loadedConfig.getProviders()).isNotNull();
    }

    @Test
    void unknown_runtime_override_fields_are_rejected() throws IOException {
        TestContext context = prepareContext("config-invalid");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));

        ObjectNode runtime = botRuntime("demo_runner_a", active.provider() + "_" + active.environment() + "_" + active.account() + "_" + active.market());
        requiredObject(runtime, "bot").put("unexpected_field", "should_fail");
        writeActiveFile(context.configDir, active, runtime);

        assertThatThrownBy(() -> context.configLoader.loadBaseline(RuntimeProfile.LIVE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to load baseline configuration");
    }

    @Test
    void noop_runtime_overrides_are_rejected() throws IOException {
        TestContext context = prepareContext("config-noop-override");
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        boolean baselineEnabled = readCurrentActiveProviderEnabled(context.configDir.resolve("catalog.json"), active);

        writeActiveFile(context.configDir, active, providerEnabledOverride(active, baselineEnabled));

        assertThatThrownBy(() -> context.configLoader.loadBaseline(RuntimeProfile.LIVE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to load baseline configuration")
                .hasRootCauseMessage("Runtime override is a no-op at path: exchange.providers." + active.provider() + ".enabled");
    }

    private TestContext prepareContext(String directoryName) throws IOException {
        Path repoConfigDir = resolveRepoConfigDir();
        Path configDir = Files.createDirectories(tempDir.resolve(directoryName));
        Files.copy(repoConfigDir.resolve("catalog.json"), configDir.resolve("catalog.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("active.json"), configDir.resolve("active.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("application-demo.json"), configDir.resolve("application-demo.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("application-real.json"), configDir.resolve("application-real.json"), StandardCopyOption.REPLACE_EXISTING);

        ActiveTargetResolver resolver = new ActiveTargetResolver(new StandardEnvironment(), configDir);
        ConfigLoader configLoader = new ConfigLoader(resolver, configDir);
        return new TestContext(configDir, configLoader);
    }

    private ExchangeProperties readActiveSelection(Path activePath) throws IOException {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(activePath.toFile());
        JsonNode active = root.get("active");
        if (active == null || !active.isObject()) {
            throw new IllegalStateException("Expected active object in " + activePath);
        }
        return jsonMapper.treeToValue(active, ExchangeProperties.class);
    }

    private boolean readCurrentActiveProviderEnabled(Path catalogPath, ExchangeProperties active) throws IOException {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(catalogPath.toFile());
        return currentActiveProviderNode(root, active).path("enabled").asBoolean();
    }

    private void setCurrentActiveProviderEnabled(Path catalogPath, ExchangeProperties active, boolean enabled) throws IOException {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(catalogPath.toFile());
        currentActiveProviderNode(root, active).put("enabled", enabled);
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(catalogPath.toFile(), root);
    }

    private void setActiveMarketRestBaseUrl(Path catalogPath, ExchangeProperties active, String baseUrl) throws IOException {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(catalogPath.toFile());
        requiredObject(currentActiveMarketNode(root, active), "rest").put("base_url", baseUrl);
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(catalogPath.toFile(), root);
    }

    private ObjectNode providerEnabledOverride(ExchangeProperties active, boolean enabled) {
        ObjectNode runtime = jsonMapper.createObjectNode();
        ObjectNode exchange = runtime.putObject("exchange");
        ObjectNode providers = exchange.putObject("providers");
        providers.putObject(active.provider()).put("enabled", enabled);
        return runtime;
    }

    private ObjectNode activeMarketRestBaseUrlOverride(ExchangeProperties active, String baseUrl) {
        ObjectNode runtime = jsonMapper.createObjectNode();
        ObjectNode exchange = runtime.putObject("exchange");
        ObjectNode providers = exchange.putObject("providers");
        ObjectNode provider = providers.putObject(active.provider());
        ObjectNode environments = provider.putObject("environments");
        ObjectNode environment = environments.putObject(active.environment());
        ObjectNode accounts = environment.putObject("accounts");
        ObjectNode account = accounts.putObject(active.account());
        ObjectNode markets = account.putObject("markets");
        ObjectNode market = markets.putObject(active.market());
        market.putObject("rest").put("base_url", baseUrl);
        return runtime;
    }

    private ObjectNode botRuntime(String instanceId, String targetId) {
        ObjectNode runtime = jsonMapper.createObjectNode();
        ObjectNode bot = runtime.putObject("bot");
        bot.put("instance_id", instanceId);
        bot.put("target_id", targetId);
        return runtime;
    }

    private void writeActiveFile(Path configDir, ExchangeProperties active, ObjectNode runtime) throws IOException {
        ObjectNode root = jsonMapper.createObjectNode();
        root.set("active", jsonMapper.valueToTree(active));
        root.set("runtime", runtime);
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(configDir.resolve("active.json").toFile(), root);
    }

    private void writeRuntimeFile(Path configDir, ExchangeProperties active, ObjectNode runtime) throws IOException {
        Path runtimeFile = configDir.resolve("runtime")
                .resolve(RuntimeProfile.LIVE.id())
                .resolve(active.provider())
                .resolve(active.environment())
                .resolve(active.account())
                .resolve(active.market() + ".json");
        Path parent = runtimeFile.getParent();
        if (parent == null) {
            throw new IOException("Runtime file has no parent directory: " + runtimeFile);
        }
        Files.createDirectories(parent);
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(runtimeFile.toFile(), runtime);
    }

    private void merge(ObjectNode target, ObjectNode patch) {
        patch.properties().forEach(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode patchValue = entry.getValue();
            if (existing instanceof ObjectNode existingObject && patchValue instanceof ObjectNode patchObject) {
                merge(existingObject, patchObject);
            } else {
                target.set(entry.getKey(), patchValue.deepCopy());
            }
        });
    }

    private ObjectNode currentActiveProviderNode(ObjectNode root, ExchangeProperties active) {
        ObjectNode exchange = requiredObject(root, "exchange");
        ObjectNode providers = requiredObject(exchange, "providers");
        return requiredObject(providers, active.provider());
    }

    private ObjectNode currentActiveMarketNode(ObjectNode root, ExchangeProperties active) {
        ObjectNode provider = currentActiveProviderNode(root, active);
        ObjectNode environments = requiredObject(provider, "environments");
        ObjectNode environment = requiredObject(environments, active.environment());
        ObjectNode accounts = requiredObject(environment, "accounts");
        ObjectNode account = requiredObject(accounts, active.account());
        ObjectNode markets = requiredObject(account, "markets");
        return requiredObject(markets, active.market());
    }

    private String activeRestBaseUrl(TradingBotProperties properties, ExchangeProperties active) {
        JsonNode provider = properties.getProviders().requiredActive(active.provider());
        return provider.path("environments")
                .path(active.environment())
                .path("accounts")
                .path(active.account())
                .path("markets")
                .path(active.market())
                .path("rest")
                .path("base_url")
                .asString();
    }

    private ObjectNode requiredObject(ObjectNode parent, String key) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected object at path segment: " + key);
        }
        return (ObjectNode) node;
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

    private record TestContext(Path configDir, ConfigLoader configLoader) {
    }
}
