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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceDemoOrderLifecycleTest {

    private static final String ENABLE_PROPERTY = "binance.demo.order.smoke";
    private static final String SYMBOL = "BTCUSDT";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void creates_and_cancels_passive_usdm_demo_order_when_explicitly_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance demo order lifecycle smoke test");

        String apiKey = requiredEnv("BINANCE_DEMO_API_KEY");
        String apiSecret = requiredEnv("BINANCE_DEMO_API_SECRET");
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

        JsonNode exchangeInfo = getJson(binance.rest().baseUrl() + binance.rest().exchangeInfoPath());
        JsonNode symbolInfo = symbolInfo(exchangeInfo, SYMBOL);
        BigDecimal passivePrice = passiveBuyPrice(binance);
        BigDecimal quantity = orderQuantity(symbolInfo, passivePrice);
        String clientOrderId = "tb_smoke_" + Instant.now().toEpochMilli();

        BinanceOrderClient orderClient = new BinanceOrderClient(
                binance,
                apiKey,
                apiSecret,
                Clock.systemUTC(),
                serverTimeOffsetMillis(binance)
        );
        boolean hedgeMode = hedgeMode(binance, apiKey, apiSecret);
        BinanceOrderCommand order = new BinanceOrderCommand(
                SYMBOL,
                "BUY",
                "LIMIT",
                "GTX",
                hedgeMode ? "LONG" : null,
                "RESULT",
                null,
                null,
                null,
                null,
                null,
                clientOrderId,
                null,
                quantity,
                null,
                passivePrice,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        boolean createdOrder = false;
        try {
            BinanceOrderResult created = orderClient.placeOrder(order);
            assertThat(created.clientOrderId()).isEqualTo(clientOrderId);
            assertThat(created.status()).isIn("NEW", "PARTIALLY_FILLED");
            createdOrder = true;

            BinanceOrderResult queried = queryEventually(orderClient, clientOrderId);
            assertThat(queried.clientOrderId()).isEqualTo(clientOrderId);
        } finally {
            if (createdOrder) {
                BinanceOrderResult cancelled = orderClient.cancelOrder(SYMBOL, clientOrderId);
                assertThat(cancelled.clientOrderId()).isEqualTo(clientOrderId);
                assertThat(cancelled.status()).isIn("CANCELED", "EXPIRED", "FILLED");
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

    private BinanceOrderResult queryEventually(BinanceOrderClient orderClient,
                                               String clientOrderId) throws InterruptedException {
        int attempts = 5;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return orderClient.queryOrder(SYMBOL, clientOrderId);
            } catch (BinanceApiException e) {
                if (!Integer.valueOf(-2013).equals(e.exchangeCode()) || attempt == attempts) {
                    throw e;
                }
                Thread.sleep(250L);
            }
        }
        throw new IllegalStateException("Unreachable query retry state");
    }

    private long serverTimeOffsetMillis(BinanceProperties binance) throws IOException, InterruptedException {
        JsonNode serverTime = getJson(binance.rest().baseUrl() + binance.rest().serverTimePath());
        return serverTime.required("serverTime").asLong() - Instant.now().toEpochMilli();
    }

    private boolean hedgeMode(BinanceProperties binance,
                              String apiKey,
                              String apiSecret) throws IOException, InterruptedException {
        BinanceRestRequestFactory factory = new BinanceRestRequestFactory(binance.rest(), Clock.systemUTC(), serverTimeOffsetMillis(binance));
        BinanceSignedRequest request = factory.signedUri("/fapi/v1/positionSide/dual", List.of(), apiSecret);
        HttpResponse<String> response = sendSigned(request, "GET", apiKey, binance.rest().apiKeyHeader());
        assertThat(response.statusCode())
                .as("position mode response: " + response.body())
                .isBetween(200, 299);
        return jsonMapper.readTree(response.body()).required("dualSidePosition").asBoolean();
    }

    private BigDecimal passiveBuyPrice(BinanceProperties binance) throws IOException, InterruptedException {
        JsonNode ticker = getJson(binance.rest().baseUrl() + "/fapi/v1/ticker/price?symbol=" + SYMBOL);
        BigDecimal currentPrice = new BigDecimal(ticker.required("price").asString());
        JsonNode symbol = symbolInfo(getJson(binance.rest().baseUrl() + binance.rest().exchangeInfoPath()), SYMBOL);
        BigDecimal tickSize = filterDecimal(symbol, "PRICE_FILTER", "tickSize", BigDecimal.ONE);
        return floorToStep(currentPrice.multiply(new BigDecimal("0.90")), tickSize);
    }

    private BigDecimal orderQuantity(JsonNode symbolInfo, BigDecimal price) {
        BigDecimal stepSize = filterDecimal(symbolInfo, "LOT_SIZE", "stepSize", new BigDecimal("0.001"));
        BigDecimal minQty = filterDecimal(symbolInfo, "LOT_SIZE", "minQty", new BigDecimal("0.001"));
        BigDecimal minNotional = filterDecimal(symbolInfo, "MIN_NOTIONAL", "notional", BigDecimal.TEN);
        BigDecimal notionalQuantity = minNotional.divide(price, 18, RoundingMode.UP);
        return ceilToStep(minQty.max(notionalQuantity), stepSize);
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return jsonMapper.readTree(response.body());
    }

    private HttpResponse<String> sendSigned(BinanceSignedRequest request,
                                            String method,
                                            String apiKey,
                                            String apiKeyHeader) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofSeconds(15))
                .header(apiKeyHeader, apiKey)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode symbolInfo(JsonNode exchangeInfo, String symbol) {
        for (JsonNode item : exchangeInfo.required("symbols")) {
            if (symbol.equals(item.required("symbol").asString())) {
                return item;
            }
        }
        throw new IllegalStateException("Missing Binance symbol metadata: " + symbol);
    }

    private BigDecimal filterDecimal(JsonNode symbolInfo, String filterType, String field, BigDecimal fallback) {
        for (JsonNode filter : symbolInfo.required("filters")) {
            if (filterType.equals(filter.required("filterType").asString()) && filter.hasNonNull(field)) {
                return new BigDecimal(filter.required(field).asString());
            }
        }
        return fallback;
    }

    private BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }

    private BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.UP).multiply(step).stripTrailingZeros();
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
