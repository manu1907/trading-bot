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
