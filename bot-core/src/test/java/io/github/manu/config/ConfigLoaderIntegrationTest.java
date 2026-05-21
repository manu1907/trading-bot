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

    private ObjectNode providerEnabledOverride(ExchangeProperties active, boolean enabled) {
        ObjectNode runtime = jsonMapper.createObjectNode();
        ObjectNode exchange = runtime.putObject("exchange");
        ObjectNode providers = exchange.putObject("providers");
        providers.putObject(active.provider()).put("enabled", enabled);
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
