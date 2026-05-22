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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceDemoWebSocketMarketStreamTest {

    private static final String ENABLE_PROPERTY = "binance.demo.websocket.smoke";

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void receives_public_usdm_demo_market_stream_message_when_explicitly_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance demo WebSocket market stream smoke test");

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
        assertThat(binance.websocket().baseUrl()).contains("binancefuture");

        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(
                binance.websocket(),
                Clock.systemUTC()
        );
        BinanceWebSocketConnectionPlan plan = planner.combined(
                BinanceWebSocketRoute.MARKET,
                List.of(planner.streamName("BTCUSDT", "aggTrade"))
        );
        WebSocketMessageProbe listener = new WebSocketMessageProbe();
        BinanceWebSocketConnection connection = new BinanceWebSocketClient(
                new BinanceReactorNettyWebSocketTransport()
        ).connect(plan, listener);
        try {
            assertThat(listener.awaitMessage())
                    .as("first Binance USD-M aggregate trade WebSocket payload")
                    .contains("\"e\":\"aggTrade\"");
        } finally {
            connection.close();
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

    private static final class WebSocketMessageProbe implements BinanceWebSocketListener {

        private final CountDownLatch messageReceived = new CountDownLatch(1);
        private final AtomicReference<String> firstMessage = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onText(String text) {
            firstMessage.compareAndSet(null, text);
            messageReceived.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error.compareAndSet(null, error);
            messageReceived.countDown();
        }

        private String awaitMessage() throws InterruptedException {
            assertThat(messageReceived.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isNull();
            return firstMessage.get();
        }
    }
}
