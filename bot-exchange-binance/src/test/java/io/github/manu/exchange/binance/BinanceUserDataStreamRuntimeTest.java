package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceUserDataStreamRuntimeTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void starts_listen_key_stream_and_publishes_private_websocket_events() {
        BinanceProperties binance = binance(userData("listen_key", "/fapi/v1/listenKey", "/fapi/v1/listenKey", "/fapi/v1/listenKey"));
        FakeHttpTransport httpTransport = new FakeHttpTransport(new BinanceHttpResponse(200, "{\"listenKey\":\"listen-key-1\"}"));
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        BinanceUserDataStreamRuntime runtime = runtime(binance, httpTransport, webSocketTransport, eventBus, new FakeScheduler());

        BinanceUserDataStreamSession session = runtime.start();
        webSocketTransport.listeners.getFirst().onText("""
                {
                  "e": "balanceUpdate",
                  "E": 1573200697110,
                  "a": "BTC",
                  "d": "100.00000000",
                  "T": 1573200697068
                }
                """);

        assertThat(session.streamId()).isEqualTo("listen-key-1");
        assertThat(runtime.activeSession()).contains(session);
        assertThat(runtime.activeConnection()).isPresent();
        assertThat(webSocketTransport.plans).singleElement().satisfies(plan -> {
            assertThat(plan.route()).isEqualTo(BinanceWebSocketRoute.PRIVATE);
            assertThat(plan.streams()).containsExactly("listen-key-1");
            assertThat(plan.uri()).hasToString("wss://fstream.binancefuture.com/private/ws/listen-key-1");
        });
        assertThat(eventBus.envelopes).hasSize(1);
        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("POST");
    }

    @Test
    void renews_listen_key_without_reopening_websocket_when_stream_id_is_stable() {
        BinanceProperties binance = binance(userData("listen_key", "/fapi/v1/listenKey", "/fapi/v1/listenKey", "/fapi/v1/listenKey"));
        FakeHttpTransport httpTransport = new FakeHttpTransport(
                new BinanceHttpResponse(200, "{\"listenKey\":\"listen-key-1\"}"),
                new BinanceHttpResponse(200, "{\"listenKey\":\"listen-key-1\"}")
        );
        FakeScheduler scheduler = new FakeScheduler();
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceUserDataStreamRuntime runtime = runtime(binance, httpTransport, webSocketTransport, new CapturingTradingEventBus(), scheduler);

        runtime.start();
        scheduler.renewalTask().run();

        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("POST", "PUT");
        assertThat(webSocketTransport.plans).hasSize(1);
        assertThat(runtime.activeSession()).hasValueSatisfying(session ->
                assertThat(session.streamId()).isEqualTo("listen-key-1"));
    }

    @Test
    void restarts_stream_when_lifecycle_has_no_keepalive_path() {
        BinanceProperties binance = binance(userData("listen_token", "/sapi/v1/userListenToken", null, null));
        FakeHttpTransport httpTransport = new FakeHttpTransport(
                new BinanceHttpResponse(200, "{\"listenToken\":\"listen-token-1\"}"),
                new BinanceHttpResponse(200, "{\"listenToken\":\"listen-token-2\"}")
        );
        FakeScheduler scheduler = new FakeScheduler();
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceUserDataStreamRuntime runtime = runtime(binance, httpTransport, webSocketTransport, new CapturingTradingEventBus(), scheduler);

        runtime.start();
        scheduler.renewalTask().run();

        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("POST", "POST");
        assertThat(webSocketTransport.plans).hasSize(2);
        assertThat(webSocketTransport.plans.get(0).streams()).containsExactly("listen-token-1");
        assertThat(webSocketTransport.plans.get(1).streams()).containsExactly("listen-token-2");
        assertThat(webSocketTransport.closeCount).isEqualTo(1);
        assertThat(runtime.activeSession()).hasValueSatisfying(session ->
                assertThat(session.streamId()).isEqualTo("listen-token-2"));
    }

    @Test
    void closes_supervisor_and_rest_lifecycle_when_close_path_is_configured() {
        BinanceProperties binance = binance(userData("listen_key", "/fapi/v1/listenKey", "/fapi/v1/listenKey", "/fapi/v1/listenKey"));
        FakeHttpTransport httpTransport = new FakeHttpTransport(
                new BinanceHttpResponse(200, "{\"listenKey\":\"listen-key-1\"}"),
                new BinanceHttpResponse(200, "{}")
        );
        FakeScheduler scheduler = new FakeScheduler();
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceUserDataStreamRuntime runtime = runtime(binance, httpTransport, webSocketTransport, new CapturingTradingEventBus(), scheduler);

        runtime.start();
        runtime.close();

        assertThat(httpTransport.calls()).extracting(FakeCall::method).containsExactly("POST", "DELETE");
        assertThat(webSocketTransport.closeCount).isEqualTo(1);
        assertThat(scheduler.renewalTask().cancelled).isTrue();
        assertThat(runtime.activeSession()).isEmpty();
        assertThat(runtime.activeConnection()).isEmpty();
    }

    private BinanceUserDataStreamRuntime runtime(BinanceProperties binance,
                                                 FakeHttpTransport httpTransport,
                                                 FakeWebSocketTransport webSocketTransport,
                                                 CapturingTradingEventBus eventBus,
                                                 FakeScheduler scheduler) {
        return new BinanceUserDataStreamRuntime(
                binance.userDataStream(),
                new BinanceUserDataStreamClient(
                        binance,
                        "api-key",
                        FIXED_CLOCK,
                        httpTransport,
                        JsonMapperFactory.create()
                ),
                new BinanceWebSocketClient(webSocketTransport),
                new BinanceWebSocketEndpointPlanner(binance.websocket(), FIXED_CLOCK),
                scheduler,
                FIXED_CLOCK,
                Duration.ofSeconds(2),
                new BinanceUserDataEventMapper(),
                new BinanceUserDataEventMapper.Context("binance", "demo", "main", "usd_m_futures"),
                eventBus,
                new BinanceWebSocketListener() {
                }
        );
    }

    private BinanceProperties binance(BinanceProperties.UserDataStream userData) {
        return new BinanceProperties(
                "FUTURES_USD_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                trading(),
                userData,
                null,
                new BinanceProperties.FuturesAccount(
                        "ONE_WAY",
                        List.of("ONE_WAY", "HEDGE"),
                        "/fapi/v1/positionSide/dual",
                        "/fapi/v1/marginType",
                        "/fapi/v1/leverage",
                        "/fapi/v3/balance",
                        "/fapi/v3/account",
                        "/fapi/v3/positionRisk",
                        "/fapi/v1/adlQuantile",
                        "/fapi/v1/forceOrders",
                        "/fapi/v1/income",
                        "/fapi/v1/fundingRate",
                        "/fapi/v1/multiAssetsMargin",
                        1,
                        125,
                        List.of("CROSSED", "ISOLATED"),
                        false,
                        false
                )
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://demo-fapi.binance.com",
                "/fapi/v1/exchangeInfo",
                "/fapi/v1/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                5000,
                2000,
                5000,
                3,
                200,
                List.of(408, 429, 500, 502, 503, 504),
                List.of("X-MBX-USED-WEIGHT"),
                List.of("X-MBX-ORDER-COUNT"),
                "RESULT",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://fstream.binancefuture.com",
                "/public",
                "/market",
                "/private",
                "/ws",
                "/stream",
                24,
                10,
                null,
                3,
                null,
                10,
                10,
                1024,
                null,
                "MILLISECONDS",
                null,
                null
        );
    }

    private BinanceProperties.Trading trading() {
        return new BinanceProperties.Trading(
                "/fapi/v1/order",
                null,
                "/fapi/v1/order",
                "/fapi/v1/order",
                "/fapi/v1/openOrders",
                "/fapi/v1/allOrders",
                "/fapi/v1/userTrades",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "/fapi/v1/batchOrders",
                "/fapi/v1/order",
                "/fapi/v1/batchOrders",
                "/fapi/v1/orderAmendment",
                "/fapi/v1/batchOrders",
                "/fapi/v1/allOpenOrders",
                "/fapi/v1/countdownCancelAll",
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("GTC", "IOC", "FOK", "GTX", "GTD"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
                List.of("LIMIT", "STOP", "TAKE_PROFIT"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("MARK_PRICE", "CONTRACT_PRICE"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private BinanceProperties.UserDataStream userData(String mode, String startPath, String keepalivePath, String closePath) {
        return new BinanceProperties.UserDataStream(
                mode,
                false,
                startPath,
                keepalivePath,
                closePath,
                60,
                30,
                1
        );
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
        private final List<BinanceWebSocketListener> listeners = new ArrayList<>();
        private int closeCount;

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            plans.add(plan);
            listeners.add(listener);
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    FIXED_CLOCK.instant(),
                    () -> {
                        closeCount++;
                        listener.onClose();
                    }
            );
        }
    }

    private static final class FakeScheduler implements BinanceWebSocketReconnectScheduler {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public BinanceScheduledTask schedule(Duration delay, Runnable task) {
            ScheduledTask scheduled = new ScheduledTask(task);
            tasks.add(scheduled);
            return scheduled;
        }

        private ScheduledTask renewalTask() {
            return tasks.getLast();
        }
    }

    private static final class ScheduledTask implements BinanceScheduledTask {
        private final Runnable task;
        private boolean cancelled;

        private ScheduledTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void run() {
            if (!cancelled) {
                task.run();
            }
        }
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {
        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public java.util.concurrent.CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return java.util.concurrent.CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public java.util.concurrent.CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
