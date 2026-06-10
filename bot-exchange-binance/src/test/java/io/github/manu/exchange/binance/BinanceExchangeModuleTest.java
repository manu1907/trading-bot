package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandTimeInForce;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.execution.OrderExecutionPreflightRejection;
import io.github.manu.journal.JournaledTradingEvent;
import io.github.manu.journal.TradingEventJournal;
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
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void seeds_reconciliation_event_ids_from_journal_when_available() throws Exception {
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
                provider(new InMemoryTradingEventJournal(List.of(new JournaledTradingEvent(
                        10,
                        serializedFuturesBalanceReconciliation()
                )))),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        runtimeModule.connect().join();
        awaitHttpCalls(httpTransport, 1);
        runtimeModule.disconnect().join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void submits_order_commands_through_order_execution_gateway() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 123456,
                  "clientOrderId": "tb-lfa-001",
                  "status": "NEW",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "BOTH",
                  "price": "50000.00",
                  "origQty": "0.001",
                  "executedQty": "0",
                  "avgPrice": "0",
                  "cumQuote": "0",
                  "updateTime": 1772000000002
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(orderCommand()).join();

        assertThat(runtimeModule.supports("binance", "demo", "main", "usdm_futures")).isTrue();
        assertThat(httpTransport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/fapi/v1/order");
            assertThat(call.uri()).contains("symbol=BTCUSDT");
            assertThat(call.uri()).contains("newClientOrderId=tb-lfa-001");
            assertThat(call.uri()).contains("newOrderRespType=RESULT");
        });
        assertThat(result.eventType()).isEqualTo(TradingEventType.ORDER_RESULT);
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getCommandId()).isEqualTo("cmd-001");
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.ACCEPTED);
            assertThat(value.getExchangeOrderId()).isEqualTo("123456");
            assertThat(value.getExchangeStatus()).isEqualTo("NEW");
        });
    }

    @Test
    void accepts_binance_native_feature_attribute_names_on_order_commands() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 123456,
                  "clientOrderId": "tb-lfa-001",
                  "status": "NEW",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "BOTH",
                  "price": "0",
                  "origQty": "0.001",
                  "executedQty": "0",
                  "avgPrice": "0",
                  "cumQuote": "0",
                  "updateTime": 1772000000002
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder(orderCommand())
                .setPrice(null)
                .setAttributes(Map.of(
                        "priceMatch", "QUEUE",
                        "newOrderRespType", "ACK",
                        "selfTradePreventionMode", "EXPIRE_TAKER"
                ))
                .build();

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(command).join();

        assertThat(httpTransport.calls()).singleElement().satisfies(call -> {
            assertThat(call.uri()).contains("priceMatch=QUEUE");
            assertThat(call.uri()).contains("newOrderRespType=ACK");
            assertThat(call.uri()).contains("selfTradePreventionMode=EXPIRE_TAKER");
            assertThat(call.uri()).doesNotContain("price=");
        });
        assertThat((OrderResultEvent) result.value()).satisfies(value ->
                assertThat(value.getStatus()).isEqualTo(OrderResultStatus.ACCEPTED));
    }

    @Test
    void routes_cancel_order_commands_through_order_execution_gateway() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 123456,
                  "clientOrderId": "tb-lfa-001",
                  "status": "CANCELED",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "BOTH",
                  "price": "50000.00",
                  "origQty": "0.001",
                  "executedQty": "0",
                  "avgPrice": "0",
                  "cumQuote": "0",
                  "updateTime": 1772000000002
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId("cmd-cancel-001")
                .setClientOrderId("tb-cancel-001")
                .setTargetClientOrderId("tb-lfa-001")
                .build();

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(command).join();

        assertThat(result.key().getEntityId()).isEqualTo("tb-lfa-001");
        assertThat(httpTransport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.uri()).contains("/fapi/v1/order");
            assertThat(call.uri()).contains("symbol=BTCUSDT");
            assertThat(call.uri()).contains("origClientOrderId=tb-lfa-001");
        });
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getCommandId()).isEqualTo("cmd-cancel-001");
            assertThat(value.getClientOrderId()).isEqualTo("tb-lfa-001");
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.CANCELED);
        });
    }

    @Test
    void routes_cancel_order_commands_by_exchange_order_id() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 123456,
                  "clientOrderId": "tb-lfa-001",
                  "status": "CANCELED",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "BOTH",
                  "price": "50000.00",
                  "origQty": "0.001",
                  "executedQty": "0",
                  "avgPrice": "0",
                  "cumQuote": "0",
                  "updateTime": 1772000000002
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId("cmd-cancel-001")
                .setClientOrderId("tb-cancel-001")
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId("123456")
                .build();

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(command).join();

        assertThat(result.key().getEntityId()).isEqualTo("tb-lfa-001");
        assertThat(httpTransport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.uri()).contains("/fapi/v1/order");
            assertThat(call.uri()).contains("symbol=BTCUSDT");
            assertThat(call.uri()).contains("orderId=123456");
            assertThat(call.uri()).doesNotContain("origClientOrderId=");
        });
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getCommandId()).isEqualTo("cmd-cancel-001");
            assertThat(value.getExchangeOrderId()).isEqualTo("123456");
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.CANCELED);
        });
    }

    @Test
    void routes_modify_order_commands_through_order_execution_gateway() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 123456,
                  "clientOrderId": "tb-lfa-001",
                  "status": "NEW",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "BOTH",
                  "price": "50100.00",
                  "origQty": "0.002",
                  "executedQty": "0",
                  "avgPrice": "0",
                  "cumQuote": "0",
                  "updateTime": 1772000000002
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.MODIFY)
                .setCommandId("cmd-modify-001")
                .setClientOrderId("tb-modify-001")
                .setTargetClientOrderId("tb-lfa-001")
                .setQuantity("0.002")
                .setPrice("50100.00")
                .build();

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(command).join();

        assertThat(httpTransport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.uri()).contains("/fapi/v1/order");
            assertThat(call.uri()).contains("symbol=BTCUSDT");
            assertThat(call.uri()).contains("origClientOrderId=tb-lfa-001");
            assertThat(call.uri()).contains("side=BUY");
            assertThat(call.uri()).contains("quantity=0.002");
            assertThat(call.uri()).contains("price=50100");
        });
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getCommandId()).isEqualTo("cmd-modify-001");
            assertThat(value.getClientOrderId()).isEqualTo("tb-lfa-001");
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.ACCEPTED);
            assertThat(value.getPrice()).isEqualTo("50100");
            assertThat(value.getOriginalQuantity()).isEqualTo("0.002");
        });
    }

    @Test
    void maps_unknown_binance_order_execution_status_to_unknown_result() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(503, """
                {
                  "code": -1000,
                  "msg": "Unknown error, please check your request or try again later."
                }
                """));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(orderCommand()).join();

        assertThat(result.eventType()).isEqualTo(TradingEventType.ORDER_RESULT);
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.UNKNOWN);
            assertThat(value.getRejectCode()).isEqualTo("-1000");
            assertThat(value.getRejectMessage()).contains("Unknown error");
            assertThat(value.getAttributes())
                    .containsEntry("http_reject", "true")
                    .containsEntry("http_status_code", "503")
                    .containsEntry("exchange_code", "-1000")
                    .containsEntry("retryable", "true")
                    .containsEntry("reconcile_before_retry", "true")
                    .containsEntry("unknown_execution_status", "true")
                    .containsEntry("retry_backoff_millis", "200");
        });
    }

    @Test
    void rejects_order_command_before_http_when_exchange_filter_fails() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{}"));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        TradingEventEnvelope<?> result = runtimeModule.submit(OrderCommandEvent.newBuilder(orderCommand())
                .setQuantity("0.0005")
                .build()).join();

        assertThat(httpTransport.calls()).isEmpty();
        assertThat(result.eventType()).isEqualTo(TradingEventType.ORDER_RESULT);
        assertThat((OrderResultEvent) result.value()).satisfies(value -> {
            assertThat(value.getStatus()).isEqualTo(OrderResultStatus.REJECTED);
            assertThat(value.getRejectCode()).isEqualTo("VALIDATION");
            assertThat(value.getRejectMessage()).contains("quantity 0.0005 is below exchangeInfo minimum 0.001");
        });
    }

    @Test
    void rejects_order_command_in_preflight_when_exchange_filter_fails() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{}"));
        BinanceExchangeModule runtimeModule = new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );

        runtimeModule.configure(config);
        OrderExecutionPreflightRejection rejection = runtimeModule.preflight(OrderCommandEvent.newBuilder(orderCommand())
                .setQuantity("0.0005")
                .build()).orElseThrow();

        assertThat(httpTransport.calls()).isEmpty();
        assertThat(rejection.reason()).isEqualTo("execution:provider_preflight_rejected");
        assertThat(rejection.attributes())
                .containsEntry("provider_preflight_provider", "binance")
                .containsEntry("provider_preflight_reject_code", "VALIDATION");
        assertThat(rejection.attributes().get("provider_preflight_message").toString())
                .contains("quantity 0.0005 is below exchangeInfo minimum 0.001");
    }

    @Test
    void accepts_cancel_order_command_in_preflight_when_target_identity_is_present() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{}"));
        BinanceExchangeModule runtimeModule = runtimeModule(httpTransport);

        runtimeModule.configure(config);
        Optional<OrderExecutionPreflightRejection> rejection = runtimeModule.preflight(OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.CANCEL)
                .setTargetClientOrderId("tb-lfa-001")
                .build());

        assertThat(rejection).isEmpty();
        assertThat(httpTransport.calls()).isEmpty();
    }

    @Test
    void rejects_cancel_order_command_in_preflight_when_target_identity_is_missing() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{}"));
        BinanceExchangeModule runtimeModule = runtimeModule(httpTransport);

        runtimeModule.configure(config);
        OrderExecutionPreflightRejection rejection = runtimeModule.preflight(OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.CANCEL)
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(null)
                .build()).orElseThrow();

        assertThat(httpTransport.calls()).isEmpty();
        assertThat(rejection.reason()).isEqualTo("execution:provider_preflight_rejected");
        assertThat(rejection.attributes())
                .containsEntry("provider_preflight_provider", "binance")
                .containsEntry("provider_preflight_reject_code", "VALIDATION");
        assertThat(rejection.attributes().get("provider_preflight_message").toString())
                .contains("orderId or origClientOrderId is required");
    }

    @Test
    void rejects_modify_order_command_in_preflight_when_modify_parameters_are_invalid() throws Exception {
        ResolvedExchangeConfig config = checkedInResolvedConfig();
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{}"));
        BinanceExchangeModule runtimeModule = runtimeModule(httpTransport);

        runtimeModule.configure(config);
        OrderExecutionPreflightRejection rejection = runtimeModule.preflight(OrderCommandEvent.newBuilder(orderCommand())
                .setAction(OrderCommandAction.MODIFY)
                .setTargetClientOrderId("tb-lfa-001")
                .setPrice(null)
                .setAttributes(Map.of())
                .build()).orElseThrow();

        assertThat(httpTransport.calls()).isEmpty();
        assertThat(rejection.reason()).isEqualTo("execution:provider_preflight_rejected");
        assertThat(rejection.attributes())
                .containsEntry("provider_preflight_provider", "binance")
                .containsEntry("provider_preflight_reject_code", "VALIDATION");
        assertThat(rejection.attributes().get("provider_preflight_message").toString())
                .contains("price or priceMatch is required for futures modify order");
    }

    private ResolvedExchangeConfig checkedInResolvedConfig() throws IOException {
        return checkedInResolvedConfig(market -> {
        });
    }

    private BinanceExchangeModule runtimeModule(FakeHttpTransport httpTransport) {
        return new BinanceExchangeModule(
                provider(new CapturingTradingEventBus()),
                null,
                null,
                null,
                provider(exchangeMetadataService()),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                httpTransport,
                null
        );
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

    private SerializedTradingEvent serializedFuturesBalanceReconciliation() {
        TradingEventEnvelope<BalanceUpdateEvent> envelope = new BinanceRestSnapshotEventMapper().futuresBalances(
                List.of(new BinanceFuturesBalance(
                        "SgsR",
                        "USDT",
                        decimal("1000"),
                        decimal("900"),
                        decimal("0"),
                        decimal("700"),
                        decimal("650"),
                        decimal("640"),
                        true,
                        1_772_000_000_002L
                )),
                new BinanceRestSnapshotEventMapper.Context(
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures",
                        Instant.parse("2026-05-22T20:00:00Z")
                )
        ).getFirst();
        return new TradingEventMessageCodec().serialize(envelope);
    }

    private OrderCommandEvent orderCommand() {
        return OrderCommandEvent.newBuilder()
                .setEventId("evt-command-001")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setSymbol("BTCUSDT")
                .setAction(OrderCommandAction.NEW)
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(null)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setPositionSide(null)
                .setTimeInForce(OrderCommandTimeInForce.GTC)
                .setQuantity("0.001")
                .setQuoteOrderQuantity(null)
                .setPrice("50000.00")
                .setStopPrice(null)
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(Instant.parse("2026-05-22T20:00:00Z"))
                .setAttributes(Map.of())
                .build();
    }

    private BinanceExchangeMetadataService exchangeMetadataService() {
        return new BinanceExchangeMetadataService(rest -> new BinanceExchangeInfoClient(
                rest,
                new ExchangeInfoHttpTransport(),
                JsonMapperFactory.create(),
                Clock.fixed(Instant.parse("2026-05-22T20:00:00Z"), ZoneOffset.UTC),
                new BinanceExchangeInfoParser()
        ));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private void awaitHttpCalls(FakeHttpTransport httpTransport, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (httpTransport.calls().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(httpTransport.calls()).hasSize(count);
    }

    private interface ConfigMutation {
        void apply(ObjectNode market);
    }

    private interface RootConfigMutation {
        void apply(ObjectNode root, ExchangeProperties active) throws IOException;
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
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

    private static final class ExchangeInfoHttpTransport implements BinanceHttpTransport {

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            return new BinanceHttpResponse(200, """
                    {
                      "timezone": "UTC",
                      "rateLimits": [],
                      "assets": [],
                      "symbols": [
                        {
                          "symbol": "BTCUSDT",
                          "status": "TRADING",
                          "baseAsset": "BTC",
                          "quoteAsset": "USDT",
                          "orderTypes": ["LIMIT", "MARKET"],
                          "timeInForce": ["GTC", "IOC", "FOK"],
                          "filters": [
                            {
                              "filterType": "PRICE_FILTER",
                              "minPrice": "0.10",
                              "maxPrice": "1000000",
                              "tickSize": "0.10"
                            },
                            {
                              "filterType": "LOT_SIZE",
                              "minQty": "0.001",
                              "maxQty": "100",
                              "stepSize": "0.001"
                            },
                            {
                              "filterType": "MIN_NOTIONAL",
                              "notional": "5"
                            }
                          ]
                        }
                      ]
                    }
                    """);
        }

        @Override
        public BinanceHttpResponse send(
                BinanceSignedRequest request,
                String method,
                String apiKey,
                String apiKeyHeader
        ) {
            throw new UnsupportedOperationException("signed requests are not used by exchangeInfo metadata tests");
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

    private record InMemoryTradingEventJournal(List<JournaledTradingEvent> events) implements TradingEventJournal {

        private InMemoryTradingEventJournal {
            events = List.copyOf(events);
        }

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new UnsupportedOperationException("append is not used by this test");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            return events;
        }

        @Override
        public void close() {
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
