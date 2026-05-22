package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceExchangeModuleTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final BinanceExchangeModule module = new BinanceExchangeModule();

    @Test
    void accepts_checked_in_demo_usdm_futures_config() throws IOException {
        module.validateConfig(checkedInResolvedConfig());
    }

    @Test
    void rejects_missing_rest_base_url() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.withObject("rest").put("base_url", " "));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rest.base_url is required");
    }

    @Test
    void rejects_invalid_rest_base_url_scheme() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.withObject("rest").put("base_url", "ftp://example.com"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rest.base_url must be an absolute URI with scheme");
    }

    @Test
    void rejects_missing_futures_user_data() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.remove("user_data"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user_data is required for Binance futures markets");
    }

    @Test
    void rejects_invalid_recv_window() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.withObject("rest").put("recv_window_millis", 0));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rest.recv_window_millis must be positive");
    }

    @Test
    void rejects_key_types_without_binance_support() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfigRoot((root, active) ->
                activeAccount(root, active).withObject("credentials").put("key_type", "ECDSA_SHA256"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials.key_type must be one of");
    }

    private ResolvedExchangeConfig checkedInResolvedConfig() throws IOException {
        return checkedInResolvedConfig(market -> {
        });
    }

    private ResolvedExchangeConfig checkedInResolvedConfig(ConfigMutation mutation) throws IOException {
        return checkedInResolvedConfigRoot((root, active) -> mutation.apply(activeMarket(root, active)));
    }

    private ResolvedExchangeConfig checkedInResolvedConfigRoot(RootConfigMutation mutation) throws IOException {
        Path configDir = resolveRepoConfigDir();
        ObjectNode root = objectNode(jsonMapper.readTree(configDir.resolve("catalog.json").toFile()));
        merge(root, objectNode(jsonMapper.readTree(configDir.resolve("application-demo.json").toFile())));

        ExchangeProperties active = readActive(configDir.resolve("active.json"));
        root.withObject("exchange").set("active", jsonMapper.valueToTree(active));
        mutation.apply(root, active);

        TradingBotProperties properties = jsonMapper.treeToValue(root, TradingBotProperties.class);
        return ResolvedExchangeConfig.from(properties);
    }

    private ExchangeProperties readActive(Path activePath) throws IOException {
        ObjectNode root = objectNode(jsonMapper.readTree(activePath.toFile()));
        return jsonMapper.treeToValue(root.required("active"), ExchangeProperties.class);
    }

    private ObjectNode activeAccount(ObjectNode root, ExchangeProperties active) {
        return root.withObject("exchange")
                .withObject("providers")
                .withObject(active.provider())
                .withObject("environments")
                .withObject(active.environment())
                .withObject("accounts")
                .withObject(active.account());
    }

    private ObjectNode activeMarket(ObjectNode root, ExchangeProperties active) {
        return activeAccount(root, active)
                .withObject("markets")
                .withObject(active.market());
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

    private interface ConfigMutation {
        void apply(ObjectNode market);
    }

    private interface RootConfigMutation {
        void apply(ObjectNode root, ExchangeProperties active) throws IOException;
    }
}
