package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceRestSnapshotReconciliationRuntimeTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void runs_configured_open_order_snapshots_and_publishes_events() {
        FakeOrderSnapshots orders = new FakeOrderSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        true,
                        List.of("BTCUSDT", "ETHUSDT"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of()
                ),
                orders,
                null,
                null
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(orders.symbols).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(published).hasSize(2);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.ORDER_RESULT, TradingEventType.ORDER_RESULT);
    }

    @Test
    void runs_configured_futures_and_margin_snapshots() {
        FakeFuturesSnapshots futures = new FakeFuturesSnapshots();
        FakeMarginSnapshots margin = new FakeMarginSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        false,
                        List.of(),
                        true,
                        true,
                        true,
                        true,
                        true,
                        List.of("BTCUSDT")
                ),
                null,
                futures,
                margin
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(futures.balancesCalls).isEqualTo(1);
        assertThat(futures.accountInfoCalls).isEqualTo(1);
        assertThat(futures.positionRiskCalls).isEqualTo(1);
        assertThat(margin.crossAccountCalls).isEqualTo(1);
        assertThat(margin.isolatedSymbols).containsExactly(List.of("BTCUSDT"));
        assertThat(published).hasSize(5);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(
                        TradingEventType.BALANCE_UPDATE,
                        TradingEventType.POSITION_UPDATE,
                        TradingEventType.BALANCE_UPDATE,
                        TradingEventType.BALANCE_UPDATE,
                        TradingEventType.BALANCE_UPDATE
                );
    }

    @Test
    void suppresses_recently_published_event_ids_across_runs() {
        FakeOrderSnapshots orders = new FakeOrderSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10,
                        true,
                        List.of("BTCUSDT"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of()
                ),
                orders,
                null,
                null
        );

        List<PublishedTradingEvent> firstRun = runtime.runOnce();
        List<PublishedTradingEvent> secondRun = runtime.runOnce();

        assertThat(firstRun).hasSize(1);
        assertThat(secondRun).isEmpty();
        assertThat(orders.symbols).containsExactly("BTCUSDT", "BTCUSDT");
        assertThat(eventBus.envelopes).hasSize(1);
    }

    @Test
    void suppresses_event_ids_seeded_from_journal() {
        FakeOrderSnapshots orders = new FakeOrderSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10,
                        true,
                        List.of("BTCUSDT"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of()
                ),
                orders,
                null,
                null,
                List.of("binance:demo:main:usd_m_futures:ORDER_RECONCILIATION:BTCUSDT:tb-BTCUSDT:12345:1772000000001")
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(published).isEmpty();
        assertThat(orders.symbols).containsExactly("BTCUSDT");
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void rejects_missing_snapshot_source_for_enabled_family() {
        BinanceProperties.Reconciliation reconciliation = new BinanceProperties.Reconciliation(
                false,
                60,
                false,
                List.of(),
                true,
                false,
                false,
                false,
                false,
                List.of()
        );

        assertThatThrownBy(() -> runtime(reconciliation, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futures snapshots");
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots
    ) {
        return runtime(reconciliation, orderSnapshots, futuresSnapshots, marginSnapshots, List.of());
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            List<String> initialRecentEventIds
    ) {
        return new BinanceRestSnapshotReconciliationRuntime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                new BinanceRestSnapshotEventPublisher(
                        new BinanceRestSnapshotEventMapper(),
                        new BinanceRestSnapshotEventPublisher.Context("binance", "demo", "main", "usd_m_futures"),
                        eventBus,
                        Clock.fixed(Instant.parse("2026-05-25T14:00:00Z"), ZoneOffset.UTC)
                ),
                executor,
                initialRecentEventIds
        );
    }

    private BinanceOrderResult order(String symbol) {
        return new BinanceOrderResult(
                symbol,
                12345L,
                "tb-" + symbol,
                "NEW",
                "BUY",
                "LIMIT",
                "BOTH",
                decimal("50000.00"),
                decimal("0.010"),
                decimal("0"),
                null,
                decimal("0"),
                1_772_000_000_001L
        );
    }

    private BinanceFuturesBalance futuresBalance() {
        return new BinanceFuturesBalance(
                "SgsR",
                "USDT",
                decimal("1000"),
                decimal("900"),
                decimal("12.5"),
                decimal("700"),
                decimal("650"),
                decimal("640"),
                true,
                1_772_000_000_002L
        );
    }

    private BinanceFuturesAccountSnapshot futuresAccount() {
        return new BinanceFuturesAccountSnapshot(
                decimal("10"),
                decimal("2"),
                decimal("1000"),
                decimal("12.5"),
                decimal("1012.5"),
                decimal("8"),
                decimal("1"),
                decimal("900"),
                decimal("12.5"),
                decimal("700"),
                decimal("650"),
                true,
                true,
                true,
                0,
                1_772_000_000_000L,
                List.of(new BinanceFuturesAssetSnapshot(
                        "USDT",
                        decimal("1000"),
                        decimal("12.5"),
                        decimal("1012.5"),
                        decimal("2"),
                        decimal("10"),
                        decimal("8"),
                        decimal("1"),
                        decimal("900"),
                        decimal("12.5"),
                        decimal("700"),
                        decimal("650"),
                        1_772_000_000_002L
                )),
                List.of(position())
        );
    }

    private BinanceFuturesPositionSnapshot position() {
        return new BinanceFuturesPositionSnapshot(
                "BTCUSDT",
                "LONG",
                decimal("0.250"),
                decimal("50000.00"),
                decimal("50001.00"),
                decimal("50100.00"),
                decimal("25.00"),
                decimal("45000.00"),
                20,
                decimal("10"),
                "isolated",
                true,
                false,
                decimal("1000"),
                decimal("900"),
                decimal("12525"),
                "USDT",
                decimal("10"),
                decimal("2"),
                decimal("8"),
                decimal("1"),
                2,
                decimal("0"),
                decimal("0"),
                1_772_000_000_003L
        );
    }

    private BinanceCrossMarginAccountSnapshot crossMarginAccount() {
        return new BinanceCrossMarginAccountSnapshot(
                true,
                true,
                decimal("1.5"),
                decimal("1.2"),
                decimal("0.2"),
                decimal("0.1"),
                decimal("0.1"),
                decimal("12000"),
                decimal("0"),
                true,
                true,
                true,
                "MARGIN_1",
                List.of(new BinanceMarginAssetBalance(
                        "BTC",
                        decimal("0.01"),
                        decimal("0.02"),
                        decimal("0.001"),
                        decimal("0.003"),
                        decimal("0.006")
                ))
        );
    }

    private BinanceIsolatedMarginAccountSnapshot isolatedMarginAccount() {
        return new BinanceIsolatedMarginAccountSnapshot(List.of(
                new BinanceIsolatedMarginPairSnapshot(
                        new BinanceIsolatedMarginAssetBalance(
                                "ETH",
                                true,
                                decimal("0.1"),
                                decimal("1.0"),
                                decimal("0.01"),
                                decimal("0.2"),
                                decimal("0.69"),
                                decimal("0.03"),
                                true,
                                decimal("1.3")
                        ),
                        new BinanceIsolatedMarginAssetBalance(
                                "USDT",
                                true,
                                decimal("10"),
                                decimal("100"),
                                decimal("1"),
                                decimal("5"),
                                decimal("84"),
                                decimal("0.002"),
                                true,
                                decimal("105")
                        ),
                        "ETHUSDT",
                        true,
                        true,
                        decimal("1.8"),
                        "NORMAL",
                        decimal("1.1"),
                        decimal("3000"),
                        decimal("1200"),
                        decimal("1.05"),
                        true
                )
        ), decimal("0.03"), decimal("0.01"), decimal("0.02"));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private final class FakeOrderSnapshots implements BinanceRestSnapshotReconciliationRuntime.OrderSnapshots {

        private final List<String> symbols = new ArrayList<>();

        @Override
        public List<BinanceOrderResult> openOrders(String symbol) {
            symbols.add(symbol);
            return List.of(order(symbol));
        }
    }

    private final class FakeFuturesSnapshots implements BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots {

        private int balancesCalls;
        private int accountInfoCalls;
        private int positionRiskCalls;

        @Override
        public List<BinanceFuturesBalance> balances() {
            balancesCalls++;
            return List.of(futuresBalance());
        }

        @Override
        public BinanceFuturesAccountSnapshot accountInfo() {
            accountInfoCalls++;
            return futuresAccount();
        }

        @Override
        public List<BinanceFuturesPositionSnapshot> positionRisk(BinanceFuturesPositionRiskQuery query) {
            positionRiskCalls++;
            return List.of(position());
        }
    }

    private final class FakeMarginSnapshots implements BinanceRestSnapshotReconciliationRuntime.MarginSnapshots {

        private final List<List<String>> isolatedSymbols = new ArrayList<>();
        private int crossAccountCalls;

        @Override
        public BinanceCrossMarginAccountSnapshot crossAccount() {
            crossAccountCalls++;
            return crossMarginAccount();
        }

        @Override
        public BinanceIsolatedMarginAccountSnapshot isolatedAccount(BinanceIsolatedMarginAccountQuery query) {
            isolatedSymbols.add(query.symbols());
            return isolatedMarginAccount();
        }
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this runtime");
        }
    }
}
