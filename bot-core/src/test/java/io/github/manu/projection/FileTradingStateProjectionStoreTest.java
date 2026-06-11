package io.github.manu.projection;

import io.github.manu.config.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileTradingStateProjectionStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void returns_empty_when_snapshot_file_does_not_exist() {
        FileTradingStateProjectionStore store = store();

        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isEmpty();
    }

    @Test
    void saves_and_loads_projection_snapshot() {
        TradingStateSnapshot snapshot = new TradingStateSnapshot(
                List.of(new TradingStateProjection.BalanceState(
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures",
                        "USDT",
                        "1000",
                        "900",
                        "700",
                        null,
                        "REST_SNAPSHOT",
                        Instant.parse("2026-05-26T20:00:00Z"),
                        "evt-balance"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of("evt-balance")
        );
        FileTradingStateProjectionStore store = store();

        store.save(snapshot);
        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().balances()).singleElement().satisfies(balance -> {
            assertThat(balance.provider()).isEqualTo("binance");
            assertThat(balance.environment()).isEqualTo("demo");
            assertThat(balance.walletBalance()).isEqualTo("1000");
            assertThat(balance.updatedAt()).isEqualTo(Instant.parse("2026-05-26T20:00:00Z"));
        });
        assertThat(loaded.get().appliedEventIds()).containsExactly("evt-balance");
    }

    @Test
    void preserves_partially_filled_order_state_after_file_snapshot_restore() {
        Instant updatedAt = Instant.parse("2026-05-26T20:05:00Z");
        TradingStateSnapshot snapshot = new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures",
                        "BTCUSDT",
                        "cmd-partial",
                        "tb-lfa-partial",
                        "123456789",
                        "PARTIALLY_FILLED",
                        "PARTIALLY_FILLED",
                        "BUY",
                        "LIMIT",
                        "50010.00",
                        "0.100",
                        "0.040",
                        "50009.50",
                        "2000.380",
                        "USER_DATA",
                        "TRADE",
                        true,
                        false,
                        null,
                        updatedAt,
                        "evt-partial-fill"
                )),
                List.of(),
                List.of("evt-partial-fill")
        );
        FileTradingStateProjectionStore store = store();

        store.save(snapshot);
        TradingStateSnapshot loaded = store.load().orElseThrow();
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(loaded);

        assertThat(restored.order("binance", "demo", "main", "usdm_futures", "BTCUSDT", "tb-lfa-partial"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-partial");
                    assertThat(order.exchangeOrderId()).isEqualTo("123456789");
                    assertThat(order.status()).isEqualTo("PARTIALLY_FILLED");
                    assertThat(order.exchangeStatus()).isEqualTo("PARTIALLY_FILLED");
                    assertThat(order.side()).isEqualTo("BUY");
                    assertThat(order.orderType()).isEqualTo("LIMIT");
                    assertThat(order.originalQuantity()).isEqualTo("0.100");
                    assertThat(order.executedQuantity()).isEqualTo("0.040");
                    assertThat(order.averagePrice()).isEqualTo("50009.50");
                    assertThat(order.cumulativeQuote()).isEqualTo("2000.380");
                    assertThat(order.updateSource()).isEqualTo("USER_DATA");
                    assertThat(order.executionType()).isEqualTo("TRADE");
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.updatedAt()).isEqualTo(updatedAt);
                    assertThat(order.eventId()).isEqualTo("evt-partial-fill");
                });
        assertThat(loaded.appliedEventIds()).containsExactly("evt-partial-fill");
    }

    @Test
    void loads_snapshot_written_before_daily_realized_pnl_state_existed() throws Exception {
        Path snapshotDirectory = temporaryDirectory.resolve("projection");
        Path snapshotPath = snapshotDirectory.resolve("trading-state.json");
        Files.createDirectories(snapshotDirectory);
        Files.writeString(snapshotPath, """
                {
                  "balances": [],
                  "positions": [],
                  "orders": [],
                  "risks": [],
                  "manual_review_decisions": [],
                  "remediation_decisions": [],
                  "pause_governance": [],
                  "applied_event_ids": ["evt-existing"]
                }
                """);
        FileTradingStateProjectionStore store = store();

        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().dailyRealizedPnl()).isEmpty();
        assertThat(loaded.get().appliedEventIds()).containsExactly("evt-existing");
    }

    private FileTradingStateProjectionStore store() {
        return new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
    }
}
