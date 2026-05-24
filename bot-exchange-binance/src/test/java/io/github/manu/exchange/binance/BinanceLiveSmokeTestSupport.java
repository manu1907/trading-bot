package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class BinanceLiveSmokeTestSupport {

    static final String ALLOW_REAL_PROPERTY = "binance.live.smoke.allowReal";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    TradingBotProperties loadCheckedInLiveConfig() throws IOException {
        Path configDir = resolveRepoConfigDir();
        ExchangeProperties active = readActive(configDir.resolve("active.json"));
        ObjectNode root = objectNode(jsonMapper.readTree(configDir.resolve("catalog.json").toFile()));
        merge(root, objectNode(jsonMapper.readTree(configDir.resolve("application-" + active.environment() + ".json").toFile())));
        root.withObject("exchange").set("active", jsonMapper.valueToTree(active));
        return jsonMapper.treeToValue(root, TradingBotProperties.class);
    }

    BinanceProperties resolveBinance(TradingBotProperties properties) {
        ExchangeProperties active = properties.getExchange();
        BinanceProviderProperties provider = jsonMapper.treeToValue(
                properties.getProviders().requiredActive("binance"),
                BinanceProviderProperties.class
        );
        return provider.resolve(active);
    }

    void requireBinanceLiveTarget(ExchangeProperties active) {
        assertThat(active.provider()).isEqualTo("binance");
        assertThat(active.environment()).isIn("demo", "real");
        if ("real".equals(active.environment())) {
            Assumptions.assumeTrue(Boolean.getBoolean(ALLOW_REAL_PROPERTY), () ->
                    "Set -D" + ALLOW_REAL_PROPERTY + "=true to run live smoke tests against the real Binance target");
        }
    }

    String apiKey(ExchangeProperties active) throws IOException {
        return requiredSecret(active.environment(), "API_KEY");
    }

    String apiSecret(ExchangeProperties active) throws IOException {
        return requiredSecret(active.environment(), "API_SECRET");
    }

    ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    private String requiredSecret(String environment, String suffix) throws IOException {
        String specificName = "BINANCE_" + environment.toUpperCase() + "_" + suffix;
        String value = firstPresent(System.getenv(specificName), apiEnv().get(specificName));
        assertThat(value).as(specificName + " must be available in the environment or api.env").isNotBlank();
        return value;
    }

    private Map<String, String> apiEnv() throws IOException {
        Path path = resolveRepoRoot().resolve("api.env");
        if (!Files.exists(path)) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            values.put(parts[0].trim(), unquote(parts[1].trim()));
        }
        return values;
    }

    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
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
        return resolveRepoRoot().resolve("config");
    }

    private Path resolveRepoRoot() {
        Path cwdConfig = Path.of("config");
        if (Files.exists(cwdConfig.resolve("catalog.json"))) {
            return Path.of(".");
        }

        Path parentConfig = Path.of("..", "config").normalize();
        if (Files.exists(parentConfig.resolve("catalog.json"))) {
            return Path.of("..").normalize();
        }

        throw new IllegalStateException("Unable to locate repo root");
    }
}
