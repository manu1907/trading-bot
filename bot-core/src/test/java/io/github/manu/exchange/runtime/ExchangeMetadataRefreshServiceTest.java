package io.github.manu.exchange.runtime;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exchange.ExchangeMetadata;
import io.github.manu.exchange.ExchangeMetadataFetcher;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeMetadataRefreshServiceTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void refreshes_active_provider_metadata_from_current_runtime_config() throws Exception {
        RecordingFetcher fetcher = new RecordingFetcher();
        List<ResolvedExchangeConfig> refreshedRuntimeConfigs = new ArrayList<>();
        ConfigManager configManager = new ConfigManager();
        configManager.setConfig(loadCheckedInLiveConfig());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ExchangeMetadataRefreshService service = new ExchangeMetadataRefreshService(
                configManager,
                new ExchangeMetadataService(List.of(fetcher)),
                refreshedRuntimeConfigs::add,
                Duration.ofSeconds(1),
                executor
        );
        try {
            service.refreshActiveMetadata();
        } finally {
            service.stop();
        }

        assertThat(fetcher.refreshedConfigs).singleElement().satisfies(config -> {
            assertThat(config.provider()).isEqualTo("binance");
            assertThat(config.target().environment()).isEqualTo("demo");
            assertThat(config.target().market()).isEqualTo("usdm_futures");
        });
        assertThat(refreshedRuntimeConfigs).singleElement().satisfies(config -> {
            assertThat(config.provider()).isEqualTo("binance");
            assertThat(config.target().environment()).isEqualTo("demo");
            assertThat(config.target().market()).isEqualTo("usdm_futures");
        });
    }

    @Test
    void ignores_refresh_when_config_is_not_bootstrapped_yet() {
        RecordingFetcher fetcher = new RecordingFetcher();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ExchangeMetadataRefreshService service = new ExchangeMetadataRefreshService(
                new ConfigManager(),
                new ExchangeMetadataService(List.of(fetcher)),
                Duration.ofSeconds(1),
                executor
        );
        try {
            service.refreshActiveMetadata();
        } finally {
            service.stop();
        }

        assertThat(fetcher.refreshedConfigs).isEmpty();
    }

    @Test
    void rejects_non_positive_refresh_interval() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        assertThatThrownBy(() -> new ExchangeMetadataRefreshService(
                new ConfigManager(),
                new ExchangeMetadataService(List.of(new RecordingFetcher())),
                Duration.ZERO,
                executor
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refreshInterval");
        executor.shutdownNow();
    }

    private TradingBotProperties loadCheckedInLiveConfig() throws IOException {
        Path configDir = resolveRepoConfigDir();
        ObjectNode root = objectNode(jsonMapper.readTree(configDir.resolve("catalog.json").toFile()));
        merge(root, objectNode(jsonMapper.readTree(configDir.resolve("application-demo.json").toFile())));
        ExchangeProperties active = readActive(configDir.resolve("active.json"));
        root.withObject("exchange").set("active", jsonMapper.valueToTree(active));
        return jsonMapper.treeToValue(root, TradingBotProperties.class);
    }

    private ExchangeProperties readActive(Path activePath) throws IOException {
        ObjectNode root = objectNode(jsonMapper.readTree(activePath.toFile()));
        return jsonMapper.treeToValue(root.required("active"), ExchangeProperties.class);
    }

    private void merge(ObjectNode target, ObjectNode patch) {
        for (Map.Entry<String, JsonNode> entry : patch.properties()) {
            JsonNode existing = target.get(entry.getKey());
            JsonNode patchValue = entry.getValue();
            if (existing instanceof ObjectNode existingObject && patchValue instanceof ObjectNode patchObject) {
                merge(existingObject, patchObject);
            } else {
                target.set(entry.getKey(), patchValue.deepCopy());
            }
        }
    }

    private ObjectNode objectNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException("Expected object JSON node");
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

    private static final class RecordingFetcher implements ExchangeMetadataFetcher {

        private final List<ResolvedExchangeConfig> refreshedConfigs = new ArrayList<>();

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Optional<? extends ExchangeMetadata> current() {
            return Optional.empty();
        }

        @Override
        public Optional<? extends ExchangeMetadata> refresh(ResolvedExchangeConfig config) {
            refreshedConfigs.add(config);
            return Optional.empty();
        }
    }
}
