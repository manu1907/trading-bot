package io.github.manu.projection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTradingStateProjectionStoreTest {

    @Test
    void returns_empty_when_tables_have_no_projection_state() {
        JdbcTradingStateProjectionStore store = store();
        store.initializeSchema();

        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isEmpty();
    }

    @Test
    void saves_and_loads_full_projection_snapshot_transactionally() {
        JdbcTradingStateProjectionStore store = store();
        store.initializeSchema();
        TradingStateSnapshot snapshot = snapshot();

        store.save(snapshot);
        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().balances()).singleElement().satisfies(balance -> {
            assertThat(balance.provider()).isEqualTo("binance");
            assertThat(balance.environment()).isEqualTo("demo");
            assertThat(balance.walletBalance()).isEqualTo("1000");
        });
        assertThat(loaded.get().positions()).singleElement()
                .satisfies(position -> assertThat(position.positionAmount()).isEqualTo("-0.10"));
        assertThat(loaded.get().orders()).singleElement()
                .satisfies(order -> assertThat(order.exchangeStatus()).isEqualTo("NEW"));
        assertThat(loaded.get().risks()).singleElement()
                .satisfies(risk -> assertThat(risk.delta()).isEqualTo("-0.01304097"));
        assertThat(loaded.get().appliedEventIds()).containsExactly("evt-balance", "evt-position", "evt-order", "evt-risk");
    }

    @Test
    void rejects_unsafe_table_prefixes() {
        assertThatThrownBy(() -> new JdbcTradingStateProjectionStore(
                url(),
                "",
                "",
                "projection;drop table"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tablePrefix");
    }

    private JdbcTradingStateProjectionStore store() {
        return new JdbcTradingStateProjectionStore(url(), "", "", "trading_projection_");
    }

    private String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    }

    private TradingStateSnapshot snapshot() {
        Instant now = Instant.parse("2026-05-26T20:15:00Z");
        return new TradingStateSnapshot(
                List.of(new TradingStateProjection.BalanceState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "USDT",
                        "1000",
                        null,
                        "950",
                        null,
                        "REST_SNAPSHOT",
                        now,
                        "evt-balance"
                )),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "SHORT",
                        "-0.10",
                        "1200",
                        "1210",
                        "16.10",
                        now.plusSeconds(1),
                        "evt-position"
                )),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "100",
                        "0.10",
                        "0",
                        null,
                        null,
                        now.plusSeconds(2),
                        "evt-order"
                )),
                List.of(new TradingStateProjection.RiskState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "UNDERLYING",
                        "BTCUSDT",
                        "BTCUSDT",
                        null,
                        "-0.01304097",
                        "-0.00000124",
                        "16.116481",
                        "-3.83444011",
                        null,
                        null,
                        now.plusSeconds(3),
                        "evt-risk"
                )),
                List.of("evt-balance", "evt-position", "evt-order", "evt-risk")
        );
    }
}
