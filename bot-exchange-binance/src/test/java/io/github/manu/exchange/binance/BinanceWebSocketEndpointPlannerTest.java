package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceWebSocketEndpointPlannerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void builds_spot_raw_market_stream_uri_and_rollover_schedule() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(spotWebsocket(), FIXED_CLOCK);

        BinanceWebSocketConnectionPlan plan = planner.raw(
                BinanceWebSocketRoute.DEFAULT,
                List.of(planner.streamName("BTCUSDT", "trade"))
        );

        assertThat(plan.uri().toString()).isEqualTo("wss://stream.binance.com:9443/ws/btcusdt@trade");
        assertThat(plan.createdAt()).isEqualTo(Instant.parse("2026-05-22T20:00:00Z"));
        assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-05-23T20:00:00Z"));
        assertThat(plan.reconnectAt()).isEqualTo(Instant.parse("2026-05-23T19:50:00Z"));
        assertThat(plan.serverPingInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(plan.pongTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(plan.maxIncomingMessagesPerSecond()).isEqualTo(5);
    }

    @Test
    void builds_routed_usdm_combined_market_stream_uri() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(usdmWebsocket(), FIXED_CLOCK);

        BinanceWebSocketConnectionPlan plan = planner.combined(
                BinanceWebSocketRoute.MARKET,
                List.of(planner.streamName("BTCUSDT", "aggTrade"), planner.streamName("ETHUSDT", "markPrice"))
        );

        assertThat(plan.uri().toString())
                .isEqualTo("wss://fstream.binance.com/market/stream?streams=btcusdt@aggTrade/ethusdt@markPrice");
        assertThat(plan.serverPingInterval()).isEqualTo(Duration.ofMinutes(3));
        assertThat(plan.pongTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(plan.maxIncomingMessagesPerSecond()).isEqualTo(10);
    }

    @Test
    void builds_routed_usdm_private_user_data_stream_uri() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(usdmWebsocket(), FIXED_CLOCK);

        BinanceWebSocketConnectionPlan plan = planner.raw(BinanceWebSocketRoute.PRIVATE, List.of("listen-key-1"));

        assertThat(plan.uri().toString()).isEqualTo("wss://fstream.binance.com/private/ws/listen-key-1");
        assertThat(plan.route()).isEqualTo(BinanceWebSocketRoute.PRIVATE);
        assertThat(plan.mode()).isEqualTo(BinanceWebSocketMode.RAW);
    }

    @Test
    void adds_microsecond_time_unit_query_parameter() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(microsecondWebsocket(), FIXED_CLOCK);

        BinanceWebSocketConnectionPlan plan = planner.combined(
                BinanceWebSocketRoute.DEFAULT,
                List.of(planner.streamName("BTCUSDT", "trade"))
        );

        assertThat(plan.uri().toString())
                .isEqualTo("wss://stream.binance.com:9443/stream?streams=btcusdt@trade&timeUnit=MICROSECOND");
    }

    @Test
    void rejects_empty_or_excessive_stream_sets() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(spotWebsocket(), FIXED_CLOCK);

        assertThatThrownBy(() -> planner.combined(BinanceWebSocketRoute.DEFAULT, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one");
        assertThatThrownBy(() -> planner.combined(
                BinanceWebSocketRoute.DEFAULT,
                Collections.nCopies(1025, "btcusdt@trade")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds configured maximum");
    }

    @Test
    void rejects_routed_connection_when_market_has_no_route_prefix() {
        BinanceWebSocketEndpointPlanner planner = new BinanceWebSocketEndpointPlanner(spotWebsocket(), FIXED_CLOCK);

        assertThatThrownBy(() -> planner.raw(BinanceWebSocketRoute.MARKET, List.of("btcusdt@trade")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marketPathPrefix is required");
    }

    private BinanceProperties.Websocket spotWebsocket() {
        return new BinanceProperties.Websocket(
                "wss://stream.binance.com:9443",
                null,
                null,
                null,
                "/ws",
                "/stream",
                24,
                10,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MILLISECONDS"
        );
    }

    private BinanceProperties.Websocket usdmWebsocket() {
        return new BinanceProperties.Websocket(
                "wss://fstream.binance.com",
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
                "MILLISECONDS"
        );
    }

    private BinanceProperties.Websocket microsecondWebsocket() {
        return new BinanceProperties.Websocket(
                "wss://stream.binance.com:9443",
                null,
                null,
                null,
                "/ws",
                "/stream",
                24,
                10,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MICROSECONDS"
        );
    }
}
