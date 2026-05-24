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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceCatalogComplianceTest {

    private static final Set<String> EXPECTED_MARKETS = Set.of(
            "spot",
            "margin_cross",
            "margin_isolated",
            "usdm_futures",
            "coinm_futures",
            "options"
    );

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final BinanceExchangeModule module = new BinanceExchangeModule();

    @Test
    void catalog_declares_all_binance_trading_product_families() throws IOException {
        ObjectNode catalog = catalog();

        for (String environmentName : Set.of("demo", "real")) {
            ObjectNode markets = markets(catalog, environmentName);

            assertThat(markets.propertyNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_MARKETS);
            assertThat(markets.required("spot").required("name").asString()).isEqualTo("SPOT");
            assertThat(markets.required("margin_cross").required("name").asString()).isEqualTo("MARGIN_CROSS");
            assertThat(markets.required("margin_isolated").required("name").asString()).isEqualTo("MARGIN_ISOLATED");
            assertThat(markets.required("usdm_futures").required("name").asString()).isEqualTo("FUTURES_USD_M");
            assertThat(markets.required("coinm_futures").required("name").asString()).isEqualTo("FUTURES_COIN_M");
            assertThat(markets.required("options").required("name").asString()).isEqualTo("OPTIONS");
            assertThat(markets.required("spot").required("trading").required("new_order_path").asString())
                    .isEqualTo("/api/v3/order");
            assertThat(markets.required("margin_cross").required("trading").required("new_order_path").asString())
                    .isEqualTo("/sapi/v1/margin/order");
            assertThat(markets.required("usdm_futures").required("trading").required("new_order_path").asString())
                    .isEqualTo("/fapi/v1/order");
            assertThat(markets.required("coinm_futures").required("trading").required("new_order_path").asString())
                    .isEqualTo("/dapi/v1/order");
            assertThat(markets.required("usdm_futures").required("futures_account").required("portfolio_margin_expected").asBoolean())
                    .isFalse();
            assertThat(markets.required("coinm_futures").required("futures_account").required("portfolio_margin_expected").asBoolean())
                    .isFalse();
            assertThat(markets.required("options").required("trading").required("new_order_path").asString())
                    .isEqualTo("/eapi/v1/order");
        }
    }

    @Test
    void real_environment_endpoints_cover_configured_binance_markets() throws IOException {
        ObjectNode root = mergedCatalog("real");

        assertMarketBaseUrls(root, "spot", "https://api.binance.com", "wss://stream.binance.com:9443");
        assertMarketBaseUrls(root, "margin_cross", "https://api.binance.com", "wss://stream.binance.com:9443");
        assertMarketBaseUrls(root, "margin_isolated", "https://api.binance.com", "wss://stream.binance.com:9443");
        assertMarketBaseUrls(root, "usdm_futures", "https://fapi.binance.com", "wss://fstream.binance.com");
        assertMarketBaseUrls(root, "coinm_futures", "https://dapi.binance.com", "wss://dstream.binance.com");
        assertMarketBaseUrls(root, "options", "https://eapi.binance.com", "wss://fstream.binance.com");
    }

    @Test
    void every_real_market_can_be_enabled_and_validated_independently() throws IOException {
        for (String market : EXPECTED_MARKETS) {
            ObjectNode root = mergedCatalog("real");
            ExchangeProperties active = new ExchangeProperties("binance", "real", "main", market);
            markets(root, "real").withObject(market).put("enabled", true);
            root.withObject("exchange").set("active", jsonMapper.valueToTree(active));

            TradingBotProperties properties = jsonMapper.treeToValue(root, TradingBotProperties.class);

            module.validateConfig(ResolvedExchangeConfig.from(properties));
        }
    }

    private ObjectNode catalog() throws IOException {
        return objectNode(jsonMapper.readTree(resolveRepoConfigDir().resolve("catalog.json").toFile()));
    }

    private ObjectNode mergedCatalog(String environmentName) throws IOException {
        ObjectNode root = catalog();
        ObjectNode patch = objectNode(jsonMapper.readTree(resolveRepoConfigDir()
                .resolve("application-" + environmentName + ".json")
                .toFile()));
        merge(root, patch);
        return root;
    }

    private void assertMarketBaseUrls(ObjectNode root, String marketName, String restBaseUrl, String websocketBaseUrl) {
        ObjectNode market = markets(root, "real").withObject(marketName);
        assertThat(market.withObject("rest").required("base_url").asString()).isEqualTo(restBaseUrl);
        assertThat(market.withObject("websocket").required("base_url").asString()).isEqualTo(websocketBaseUrl);
    }

    private ObjectNode markets(ObjectNode root, String environmentName) {
        return root.withObject("exchange")
                .withObject("providers")
                .withObject("binance")
                .withObject("environments")
                .withObject(environmentName)
                .withObject("accounts")
                .withObject("main")
                .withObject("markets");
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
