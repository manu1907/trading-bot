package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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
    void rejects_missing_market_data_config() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.remove("market_data"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market_data is required");
    }

    @Test
    void rejects_missing_reconciliation_config() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.remove("reconciliation"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation is required");
    }

    @Test
    void rejects_enabled_market_data_runtime_without_streams() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("market_data").put("runtime_enabled", true));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market_data.streams must not be empty");
    }

    @Test
    void rejects_enabled_reconciliation_runtime_without_snapshot_sources() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("reconciliation").put("runtime_enabled", true));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must enable at least one snapshot source");
    }

    @Test
    void rejects_invalid_reconciliation_dedupe_window() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("reconciliation").put("dedupe_window_event_ids", 0));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedupe_window_event_ids must be positive");
    }

    @Test
    void rejects_invalid_recv_window() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market -> market.withObject("rest").put("recv_window_millis", 0));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rest.recv_window_millis must be positive");
    }

    @Test
    void rejects_trading_contract_that_does_not_match_market_type() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("trading").put("new_order_path", "/api/v3/order"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trading.new_order_path must be /fapi/v1/order");
    }

    @Test
    void rejects_key_types_without_binance_support() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfigRoot((root, active) ->
                activeAccount(root, active).withObject("credentials").put("key_type", "ECDSA_SHA256"));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials.key_type must be one of");
    }

    @Test
    void rejects_portfolio_margin_futures_under_standard_futures_connector() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("futures_account").put("portfolio_margin_expected", true));

        assertThatThrownBy(() -> module.validateConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futures_account.portfolio_margin_expected must be false");
    }

    @Test
    void connects_without_user_data_runtime_when_runtime_flag_is_disabled() throws IOException {
        module.configure(checkedInResolvedConfig());

        module.connect().join();
        module.disconnect().join();
    }

    @Test
    void rejects_user_data_runtime_without_event_bus() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("user_data").put("runtime_enabled", true));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule();

        runtimeModule.configure(config);

        assertThatThrownBy(runtimeModule::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TradingEventBus");
    }

    @Test
    void rejects_market_data_runtime_without_event_bus() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(this::enableMarketDataRuntime);
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule();

        runtimeModule.configure(config);

        assertThatThrownBy(runtimeModule::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TradingEventBus");
    }

    @Test
    void rejects_reconciliation_runtime_without_event_bus() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(this::enableReconciliationRuntime);
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule();

        runtimeModule.configure(config);

        assertThatThrownBy(runtimeModule::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TradingEventBus");
    }

    @Test
    void starts_and_stops_user_data_runtime_when_enabled_and_event_bus_is_available() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(market ->
                market.withObject("user_data").put("runtime_enabled", true));
        FakeHttpTransport httpTransport = new FakeHttpTransport(
                new BinanceHttpResponse(200, "{\"listenKey\":\"listen-key-1\"}"),
                new BinanceHttpResponse(200, "{}")
        );
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                webSocketTransport
        );

        runtimeModule.configure(config);
        runtimeModule.connect().join();
        runtimeModule.connect().join();
        runtimeModule.disconnect().join();

        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("POST", "DELETE");
        assertThat(webSocketTransport.plans).singleElement().satisfies(plan -> {
            assertThat(plan.route()).isEqualTo(BinanceWebSocketRoute.PRIVATE);
            assertThat(plan.streams()).containsExactly("listen-key-1");
        });
        assertThat(webSocketTransport.closeCount).isEqualTo(1);
    }

    @Test
    void starts_and_stops_market_data_runtime_when_enabled_and_event_bus_is_available() throws IOException {
        ResolvedExchangeConfig config = checkedInResolvedConfig(this::enableMarketDataRuntime);
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                null,
                webSocketTransport
        );

        runtimeModule.configure(config);
        runtimeModule.connect().join();
        runtimeModule.connect().join();
        runtimeModule.disconnect().join();

        assertThat(webSocketTransport.plans).singleElement().satisfies(plan -> {
            assertThat(plan.route()).isEqualTo(BinanceWebSocketRoute.DEFAULT);
            assertThat(plan.mode()).isEqualTo(BinanceWebSocketMode.COMBINED);
            assertThat(plan.streams()).containsExactly("btcusdt@aggTrade");
        });
        assertThat(webSocketTransport.closeCount).isEqualTo(1);
    }

    @Test
    void starts_and_stops_reconciliation_runtime_when_enabled_and_event_bus_is_available() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig(this::enableReconciliationRuntime);
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                [
                  {
                    "accountAlias": "SgsR",
                    "asset": "USDT",
                    "balance": "1000",
                    "crossWalletBalance": "900",
                    "crossUnPnl": "0",
                    "availableBalance": "700",
                    "maxWithdrawAmount": "650",
                    "withdrawAvailable": "640",
                    "marginAvailable": true,
                    "updateTime": 1772000000002
                  }
                ]
                """));
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(eventBus),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        runtimeModule.connect().join();
        assertThat(eventBus.awaitEvents(1)).isTrue();
        runtimeModule.disconnect().join();

        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("GET");
        assertThat(eventBus.envelopes).singleElement()
                .satisfies(envelope -> assertThat(envelope.eventType()).isEqualTo(TradingEventType.BALANCE_UPDATE));
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

    private void enableMarketDataRuntime(ObjectNode market) {
        ObjectNode marketData = market.withObject("market_data");
        marketData.put("runtime_enabled", true);
        marketData.set("streams", jsonMapper.valueToTree(List.of("btcusdt@aggTrade")));
    }

    private void enableReconciliationRuntime(ObjectNode market) {
        ObjectNode reconciliation = market.withObject("reconciliation");
        reconciliation.put("runtime_enabled", true);
        reconciliation.put("interval_seconds", 3600);
        reconciliation.put("futures_balances_enabled", true);
    }

    private interface ConfigMutation {
        void apply(ObjectNode market);
    }

    private interface RootConfigMutation {
        void apply(ObjectNode root, ExchangeProperties active) throws IOException;
    }

    private ObjectProvider<TradingEventBus> provider(TradingEventBus eventBus) {
        return new ObjectProvider<>() {
            @Override
            public TradingEventBus getObject(Object... args) {
                return eventBus;
            }

            @Override
            public TradingEventBus getIfAvailable() {
                return eventBus;
            }

            @Override
            public TradingEventBus getObject() {
                return eventBus;
            }
        };
    }

    private record FakeCall(String method, String uri, String payload, String signature, String apiKey, String apiKeyHeader) {
    }

    private static final class FakeHttpTransport implements BinanceHttpTransport {
        private final List<BinanceHttpResponse> responses;
        private final List<FakeCall> calls = new ArrayList<>();

        FakeHttpTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            throw new UnsupportedOperationException("public requests are not used by this test");
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            calls.add(new FakeCall(
                    method,
                    request.uri().toString(),
                    request.payload(),
                    request.signature(),
                    apiKey,
                    apiKeyHeader
            ));
            return responses.removeFirst();
        }

        List<FakeCall> calls() {
            return List.copyOf(calls);
        }
    }

    private static final class FakeWebSocketTransport implements BinanceWebSocketTransport {
        private final List<BinanceWebSocketConnectionPlan> plans = new ArrayList<>();
        private int closeCount;

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            plans.add(plan);
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    Instant.parse("2026-05-22T20:00:00Z"),
                    () -> {
                        closeCount++;
                        listener.onClose();
                    }
            );
        }
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final CountDownLatch eventLatch = new CountDownLatch(1);
        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            eventLatch.countDown();
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    1
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }

        private boolean awaitEvents(int count) throws InterruptedException {
            if (count != 1) {
                throw new IllegalArgumentException("this test bus only supports waiting for one event");
            }
            return eventLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
