package io.github.manu.config;

import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exception.ConfigValidationException;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ExchangeModuleFactory;
import io.github.manu.exchange.ResolvedExchangeConfig;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOverrideWriterIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void writes_active_runtime_override_atomically_and_loader_applies_it() throws IOException {
        TestContext context = prepareContext("runtime-write", new StubExchangeModule("binance"));
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        String expectedInstanceId = "runtime_writer_test";

        Path writtenFile = context.writer.writeActiveRuntimeOverride(botInstanceOverride(expectedInstanceId));
        TradingBotProperties loadedConfig = context.configLoader.loadBaseline(RuntimeProfile.LIVE);

        assertThat(writtenFile).isEqualTo(runtimeFile(context.configDir, active));
        assertThat(loadedConfig.getBot().instanceId()).isEqualTo(expectedInstanceId);
        assertThat(readObjectNode(writtenFile).path("bot").path("instance_id").asString())
                .isEqualTo(expectedInstanceId);
    }

    @Test
    void rejects_unknown_paths_without_replacing_runtime_file() throws IOException {
        TestContext context = prepareContext("runtime-unknown-path", new StubExchangeModule("binance"));
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        Path runtimeFile = runtimeFile(context.configDir, active);
        String before = Files.readString(runtimeFile);

        ObjectNode patch = jsonMapper.createObjectNode();
        patch.putObject("bot").put("unexpected_field", "bad");

        assertThatThrownBy(() -> context.writer.writeActiveRuntimeOverride(patch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Runtime override path does not exist: bot.unexpected_field");
        assertThat(Files.readString(runtimeFile)).isEqualTo(before);
        assertThat(Files.exists(runtimeFile.resolveSibling(runtimeFile.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    void rejects_provider_validation_failure_without_replacing_runtime_file() throws IOException {
        TestContext context = prepareContext("runtime-provider-invalid", new RejectingExchangeModule("binance"));
        ExchangeProperties active = readActiveSelection(context.configDir.resolve("active.json"));
        Path runtimeFile = runtimeFile(context.configDir, active);
        String before = Files.readString(runtimeFile);

        assertThatThrownBy(() -> context.writer.writeActiveRuntimeOverride(botInstanceOverride("will_not_apply")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Invalid provider config for exchange 'binance': rejected by provider");
        assertThat(Files.readString(runtimeFile)).isEqualTo(before);
    }

    private TestContext prepareContext(String directoryName, ExchangeModule module) throws IOException {
        Path repoConfigDir = resolveRepoConfigDir();
        Path configDir = Files.createDirectories(tempDir.resolve(directoryName));
        Files.copy(repoConfigDir.resolve("catalog.json"), configDir.resolve("catalog.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("active.json"), configDir.resolve("active.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("application-demo.json"), configDir.resolve("application-demo.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(repoConfigDir.resolve("application-real.json"), configDir.resolve("application-real.json"), StandardCopyOption.REPLACE_EXISTING);
        copyRuntimeFile(repoConfigDir, configDir);

        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("live");
        ActiveProfileProvider profileProvider = new ActiveProfileProvider(environment);
        ActiveTargetResolver resolver = new ActiveTargetResolver(environment, configDir);
        ConfigLoader configLoader = new ConfigLoader(resolver, configDir);
        ConfigValidator validator = new ConfigValidator(new ExchangeModuleFactory(List.of(module)));
        RuntimeOverrideWriter writer = new RuntimeOverrideWriter(
                profileProvider,
                resolver,
                configLoader,
                validator,
                configDir
        );
        return new TestContext(configDir, configLoader, writer);
    }

    private void copyRuntimeFile(Path sourceConfigDir, Path targetConfigDir) throws IOException {
        ExchangeProperties active = readActiveSelection(sourceConfigDir.resolve("active.json"));
        Path sourceRuntimeFile = runtimeFile(sourceConfigDir, active);
        Path targetRuntimeFile = runtimeFile(targetConfigDir, active);
        Path parent = targetRuntimeFile.getParent();
        if (parent == null) {
            throw new IOException("Runtime file has no parent directory: " + targetRuntimeFile);
        }
        Files.createDirectories(parent);
        Files.copy(sourceRuntimeFile, targetRuntimeFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private ObjectNode botInstanceOverride(String instanceId) {
        ObjectNode patch = jsonMapper.createObjectNode();
        patch.putObject("bot").put("instance_id", instanceId);
        return patch;
    }

    private ObjectNode readObjectNode(Path path) throws IOException {
        JsonNode node = jsonMapper.readTree(path.toFile());
        if (node == null || !node.isObject()) {
            throw new IOException("Expected object JSON in " + path);
        }
        return (ObjectNode) node;
    }

    private ExchangeProperties readActiveSelection(Path activePath) throws IOException {
        ObjectNode root = readObjectNode(activePath);
        JsonNode active = root.get("active");
        if (active == null || !active.isObject()) {
            throw new IllegalStateException("Expected active object in " + activePath);
        }
        return jsonMapper.treeToValue(active, ExchangeProperties.class);
    }

    private Path runtimeFile(Path configDir, ExchangeProperties active) {
        return configDir.resolve("runtime")
                .resolve(RuntimeProfile.LIVE.id())
                .resolve(active.provider())
                .resolve(active.environment())
                .resolve(active.account())
                .resolve(active.market() + ".json");
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

    private record TestContext(Path configDir, ConfigLoader configLoader, RuntimeOverrideWriter writer) {
    }

    private record StubExchangeModule(String provider) implements ExchangeModule {

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
            throw new IllegalArgumentException("rejected by provider");
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
