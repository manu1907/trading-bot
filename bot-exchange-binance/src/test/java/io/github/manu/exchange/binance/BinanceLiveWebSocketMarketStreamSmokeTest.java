package io.github.manu.exchange.binance;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceLiveWebSocketMarketStreamSmokeTest {

    private static final String ENABLE_PROPERTY = "binance.live.websocket.smoke";

    private final BinanceLiveSmokeTestSupport support = new BinanceLiveSmokeTestSupport();

    @Test
    void receives_public_market_stream_message_for_configured_live_target_when_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance live WebSocket market stream smoke test");

        TradingBotProperties properties = support.loadCheckedInLiveConfig();
        ExchangeProperties active = properties.getExchange();
        support.requireBinanceLiveTarget(active);
        assertThat(active.market()).isEqualTo("usdm_futures");

        BinanceProperties binance = support.resolveBinance(properties);
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

    private static final class WebSocketMessageProbe implements BinanceWebSocketListener {

        private final CountDownLatch messageReceived = new CountDownLatch(1);
        private final AtomicReference<String> message = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void onText(String payload) {
            message.compareAndSet(null, payload);
            messageReceived.countDown();
        }

        @Override
        public void onError(Throwable error) {
            failure.compareAndSet(null, error);
            messageReceived.countDown();
        }

        String awaitMessage() throws InterruptedException {
            assertThat(messageReceived.await(20, TimeUnit.SECONDS))
                    .as("Binance live WebSocket message received")
                    .isTrue();
            if (failure.get() != null) {
                throw new AssertionError("Binance live WebSocket failed", failure.get());
            }
            return message.get();
        }
    }
}
