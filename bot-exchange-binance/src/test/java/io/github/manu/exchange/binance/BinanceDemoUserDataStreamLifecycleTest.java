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
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceDemoUserDataStreamLifecycleTest {

    private static final String ENABLE_PROPERTY = "binance.demo.userdata.smoke";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void starts_renews_and_closes_usdm_demo_user_data_stream_when_explicitly_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance demo user data stream smoke test");

        String apiKey = requiredEnv("BINANCE_DEMO_API_KEY");
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

        BinanceUserDataStreamClient client = new BinanceUserDataStreamClient(binance, apiKey, Clock.systemUTC());
        BinanceUserDataStreamSession started = null;
        try {
            started = client.start();
            assertThat(started.mode()).isEqualTo("listen_key");
            assertThat(started.streamId()).isNotBlank();
            assertThat(started.expiresAt()).isAfter(started.startedAt());
            assertThat(started.renewAfter()).isAfter(started.startedAt());
            assertThat(started.renewAfter()).isBefore(started.expiresAt());

            BinanceUserDataStreamSession renewed = client.keepAlive(started.streamId());
            assertThat(renewed.streamId()).isEqualTo(started.streamId());
        } finally {
            if (started != null) {
                client.close(started.streamId());
            }
        }
    }

    private TradingBotProperties loadCheckedInLiveConfig() throws IOException {
        Path configDir = resolveRepoConfigDir();
        ObjectNode root = objectNode(jsonMapper.readTree(configDir.resolve("catalog.json").toFile()));
        merge(root, objectNode(jsonMapper.readTree(configDir.resolve("application-demo.json").toFile())));
        ExchangeProperties active = readActive(configDir.resolve("active.json"));
        root.withObject("exchange").set("active", jsonMapper.valueToTree(active));
        return jsonMapper.treeToValue(root, TradingBotProperties.class);
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name + " must be available in the test process").isNotBlank();
        return value;
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
