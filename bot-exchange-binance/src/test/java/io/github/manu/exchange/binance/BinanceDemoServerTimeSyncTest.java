package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceDemoServerTimeSyncTest {

    private static final String ENABLE_PROPERTY = "binance.demo.servertime.smoke";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void syncs_usdm_demo_server_time_when_explicitly_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance demo server-time smoke test");

        TradingBotProperties properties = loadCheckedInLiveConfig();
        ExchangeProperties active = properties.getExchange();
        assertThat(active.provider()).isEqualTo("binance");
        assertThat(active.environment()).isEqualTo("demo");
        assertThat(active.market()).isEqualTo("usdm_futures");

        BinanceProviderProperties provider = jsonMapper.treeToValue(
                properties.getProviders().requiredActive("binance"),
                BinanceProviderProperties.class
        );
        BinanceProperties binance = provider.resolve(active);
        assertThat(binance.rest().baseUrl()).contains("demo");

        BinanceServerTimeSnapshot snapshot = new BinanceServerTimeSynchronizer(binance.rest()).sync();

        assertThat(snapshot.serverTime()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(snapshot.roundTripMillis()).isBetween(0L, 15_000L);
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
}
