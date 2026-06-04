package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;
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
        assertThat(eventBus.envelopes)
                .extracting(envelope -> ((OrderResultEvent) envelope.value()).getAttributes().get("snapshotType"))
                .containsExactly("open_orders", "open_orders");
    }

    @Test
    void runs_configured_order_history_snapshots_and_publishes_events() {
        FakeOrderSnapshots orders = new FakeOrderSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10_000,
                        true,
                        false,
                        false,
                        List.of(),
                        true,
                        List.of("BTC-251123-126000-C", "ETH-251123-5000-P"),
                        500,
                        false,
                        List.of(),
                        1000,
                        1000,
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        false,
                        false,
                        List.of()
                ),
                orders,
                null,
                null,
                null,
                "options"
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(orders.symbols).isEmpty();
        assertThat(orders.historyQueries)
                .extracting(BinanceOrderHistoryQuery::symbol)
                .containsExactly("BTC-251123-126000-C", "ETH-251123-5000-P");
        assertThat(orders.historyQueries)
                .extracting(BinanceOrderHistoryQuery::limit)
                .containsExactly(500, 500);
        assertThat(published).hasSize(2);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.ORDER_RESULT, TradingEventType.ORDER_RESULT);
        assertThat(eventBus.envelopes)
                .extracting(envelope -> ((OrderResultEvent) envelope.value()).getAttributes().get("snapshotType"))
                .containsExactly("order_history", "order_history");
    }

    @Test
    void runs_configured_account_trade_snapshots_with_order_history_correlation() {
        FakeOrderSnapshots orders = new FakeOrderSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10_000,
                        true,
                        false,
                        false,
                        List.of(),
                        false,
                        List.of(),
                        1000,
                        true,
                        List.of("BTC-251123-126000-C"),
                        500,
                        750,
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        false,
                        false,
                        List.of()
                ),
                orders,
                null,
                null,
                null,
                "options"
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(orders.historyQueries).singleElement().satisfies(query -> {
            assertThat(query.symbol()).isEqualTo("BTC-251123-126000-C");
            assertThat(query.limit()).isEqualTo(750);
        });
        assertThat(orders.tradeQueries).singleElement().satisfies(query -> {
            assertThat(query.symbol()).isEqualTo("BTC-251123-126000-C");
            assertThat(query.limit()).isEqualTo(500);
        });
        assertThat(published).hasSize(1);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.EXECUTION_REPORT);
        ExecutionReportEvent event = (ExecutionReportEvent) eventBus.envelopes.getFirst().value();
        assertThat(event.getClientOrderId()).isEqualTo("tb-BTC-251123-126000-C");
        assertThat(event.getExchangeOrderId()).isEqualTo("12345");
        assertThat(event.getTradeId()).isEqualTo("239");
        assertThat(event.getExecutionType()).isEqualTo("TRADE");
        assertThat(event.getLastExecutedQuantity()).isEqualTo("0.002");
        assertThat(event.getLastExecutedPrice()).isEqualTo("7819.01");
        assertThat(event.getAttributes())
                .containsEntry("snapshotType", "account_trades")
                .containsEntry("optionSide", "CALL")
                .containsEntry("quoteAsset", "USDT");
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
    void runs_configured_options_account_and_position_snapshots() {
        FakeOptionsSnapshots options = new FakeOptionsSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10_000,
                        true,
                        false,
                        false,
                        List.of(),
                        false,
                        List.of(),
                        1000,
                        false,
                        List.of(),
                        1000,
                        1000,
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        true,
                        true,
                        List.of("BTC-251123-126000-C")
                ),
                null,
                null,
                null,
                options,
                "options"
        );

        List<PublishedTradingEvent> published = runtime.runOnce();

        assertThat(options.marginAccountCalls).isEqualTo(1);
        assertThat(options.positionSymbols).containsExactly("BTC-251123-126000-C");
        assertThat(published).hasSize(3);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(
                        TradingEventType.BALANCE_UPDATE,
                        TradingEventType.RISK_UPDATE,
                        TradingEventType.POSITION_UPDATE
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
    void compares_rest_snapshots_against_projection_state() {
        BinanceRestSnapshotEventMapper mapper = new BinanceRestSnapshotEventMapper();
        List<TradingEventEnvelope<BalanceUpdateEvent>> projected = mapper.futuresBalances(
                List.of(futuresBalance()),
                mapperContext()
        );
        TradingStateProjection projection = new TradingStateProjection();
        projected.forEach(projection::apply);
        BinanceRestSnapshotProjectionComparator comparator = new BinanceRestSnapshotProjectionComparator(projection);

        List<BinanceRestSnapshotProjectionComparison> matched = comparator.compare(projected);
        List<BinanceRestSnapshotProjectionComparison> mismatched = comparator.compare(mapper.futuresBalances(
                List.of(futuresBalance("USDT", "1000", "900", "600", 1_772_000_000_002L)),
                mapperContext()
        ));

        assertThat(matched).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo(BinanceRestSnapshotProjectionComparison.Status.MATCHED);
            assertThat(comparison.differences()).isEmpty();
        });
        assertThat(mismatched).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo(BinanceRestSnapshotProjectionComparison.Status.MISMATCH);
            assertThat(comparison.entityKey()).isEqualTo("binance|demo|main|usd_m_futures|USDT");
            assertThat(comparison.differences()).singleElement().satisfies(difference -> {
                assertThat(difference.field()).isEqualTo("availableBalance");
                assertThat(difference.snapshotValue()).isEqualTo("600");
                assertThat(difference.projectionValue()).isEqualTo("700");
            });
        });
    }

    @Test
    void can_fail_runtime_when_projection_comparison_is_strict() {
        FakeFuturesSnapshots futures = new FakeFuturesSnapshots();
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10_000,
                        true,
                        true,
                        false,
                        List.of(),
                        false,
                        List.of(),
                        1000,
                        false,
                        List.of(),
                        1000,
                        1000,
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        false,
                        false,
                        List.of()
                ),
                null,
                futures,
                null,
                null,
                "usd_m_futures",
                List.of(),
                new BinanceRestSnapshotProjectionComparator(new TradingStateProjection())
        );

        assertThatThrownBy(runtime::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot/projection mismatch");
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void records_projection_comparison_confidence_for_runtime_target() {
        FakeFuturesSnapshots futures = new FakeFuturesSnapshots();
        ReconciliationConfidenceTracker confidenceTracker = new ReconciliationConfidenceTracker(
                Clock.fixed(Instant.parse("2026-05-26T08:00:00Z"), ZoneOffset.UTC)
        );
        BinanceRestSnapshotReconciliationRuntime runtime = runtime(
                new BinanceProperties.Reconciliation(
                        false,
                        60,
                        10_000,
                        true,
                        false,
                        false,
                        List.of(),
                        false,
                        List.of(),
                        1000,
                        false,
                        List.of(),
                        1000,
                        1000,
                        true,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        false,
                        false,
                        List.of()
                ),
                null,
                futures,
                null,
                null,
                "usd_m_futures",
                List.of(),
                new BinanceRestSnapshotProjectionComparator(new TradingStateProjection()),
                confidenceTracker
        );

        runtime.runOnce();

        ReconciliationTargetConfidence confidence = confidenceTracker.targetConfidence(
                "binance",
                "demo",
                "main",
                "usd_m_futures"
        );
        assertThat(confidence.status()).isEqualTo(ReconciliationTargetConfidence.Status.DEGRADED);
        assertThat(confidence.degradedStates()).isEqualTo(1);
        assertThat(confidence.observedAt()).isEqualTo(Instant.parse("2026-05-26T08:00:00Z"));
        assertThat(confidenceTracker.degradedStates("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(ReconciliationConfidenceStatus.MISSING_PROJECTION);
                    assertThat(state.eventType()).isEqualTo(TradingEventType.BALANCE_UPDATE);
                    assertThat(state.entityKey()).isEqualTo("binance|demo|main|usd_m_futures|USDT");
                });
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

    @Test
    void rejects_order_history_reconciliation_without_symbols() {
        BinanceProperties.Reconciliation reconciliation = new BinanceProperties.Reconciliation(
                false,
                60,
                10_000,
                true,
                false,
                false,
                List.of(),
                true,
                List.of(),
                500,
                false,
                List.of(),
                1000,
                1000,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of()
        );

        assertThatThrownBy(() -> runtime(reconciliation, new FakeOrderSnapshots(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured symbols");
    }

    @Test
    void rejects_account_trade_reconciliation_without_symbols() {
        BinanceProperties.Reconciliation reconciliation = new BinanceProperties.Reconciliation(
                false,
                60,
                10_000,
                true,
                false,
                false,
                List.of(),
                false,
                List.of(),
                1000,
                true,
                List.of(),
                500,
                750,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of()
        );

        assertThatThrownBy(() -> runtime(reconciliation, new FakeOrderSnapshots(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured symbols");
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots
    ) {
        return runtime(reconciliation, orderSnapshots, futuresSnapshots, marginSnapshots, null, List.of());
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            List<String> initialRecentEventIds
    ) {
        return runtime(reconciliation, orderSnapshots, futuresSnapshots, marginSnapshots, null, initialRecentEventIds);
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots,
            String market
    ) {
        return runtime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                market,
                List.of()
        );
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots,
            List<String> initialRecentEventIds
    ) {
        return runtime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                "usd_m_futures",
                initialRecentEventIds
        );
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots,
            String market,
            List<String> initialRecentEventIds
    ) {
        return runtime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                market,
                initialRecentEventIds,
                null
        );
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots,
            String market,
            List<String> initialRecentEventIds,
            BinanceRestSnapshotProjectionComparator projectionComparator
    ) {
        return runtime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                market,
                initialRecentEventIds,
                projectionComparator,
                null
        );
    }

    private BinanceRestSnapshotReconciliationRuntime runtime(
            BinanceProperties.Reconciliation reconciliation,
            BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots,
            BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots,
            BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots,
            BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots,
            String market,
            List<String> initialRecentEventIds,
            BinanceRestSnapshotProjectionComparator projectionComparator,
            ReconciliationConfidenceTracker confidenceTracker
    ) {
        return new BinanceRestSnapshotReconciliationRuntime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                new BinanceRestSnapshotEventPublisher(
                        new BinanceRestSnapshotEventMapper(),
                        new BinanceRestSnapshotEventPublisher.Context("binance", "demo", "main", market),
                        eventBus,
                        Clock.fixed(Instant.parse("2026-05-25T14:00:00Z"), ZoneOffset.UTC)
                ),
                projectionComparator,
                confidenceTracker,
                executor,
                initialRecentEventIds
        );
    }

    private BinanceRestSnapshotEventMapper.Context mapperContext() {
        return new BinanceRestSnapshotEventMapper.Context(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                Instant.parse("2026-05-25T14:00:00Z")
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

    private BinanceAccountTrade trade(String symbol, long orderId, long tradeId) {
        return new BinanceAccountTrade(
                symbol,
                tradeId,
                tradeId,
                orderId,
                null,
                null,
                "BUY",
                "BOTH",
                "TAKER",
                decimal("7819.01"),
                decimal("0.002"),
                decimal("15.63802"),
                null,
                decimal("-0.91539999"),
                decimal("0.07819010"),
                "USDT",
                null,
                2,
                4,
                "CALL",
                "USDT",
                true,
                false,
                true,
                1_569_514_978_020L
        );
    }

    private BinanceFuturesBalance futuresBalance() {
        return futuresBalance("USDT", "1000", "900", "700", 1_772_000_000_002L);
    }

    private BinanceFuturesBalance futuresBalance(
            String asset,
            String balance,
            String crossWalletBalance,
            String availableBalance,
            long updateTime
    ) {
        return new BinanceFuturesBalance(
                "SgsR",
                asset,
                decimal(balance),
                decimal(crossWalletBalance),
                decimal("12.5"),
                decimal(availableBalance),
                decimal("650"),
                decimal("640"),
                true,
                updateTime
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

    private BinanceOptionsMarginAccountSnapshot optionsMarginAccount() {
        return new BinanceOptionsMarginAccountSnapshot(
                List.of(new BinanceOptionsAccountAsset(
                        "USDT",
                        decimal("10000475.51032086"),
                        decimal("10000371.61462086"),
                        decimal("9998120.00000000"),
                        decimal("32354.38562539"),
                        decimal("6089.28766956"),
                        decimal("16.10430000"),
                        decimal("10000475.51032086")
                )),
                List.of(new BinanceOptionsGreek(
                        "BTCUSDT",
                        decimal("-0.01304097"),
                        decimal("16.11648100"),
                        decimal("-0.00000124"),
                        decimal("-3.83444011")
                )),
                1_772_000_000_004L,
                true,
                true,
                true,
                false,
                0L
        );
    }

    private BinanceOptionsPositionSnapshot optionsPosition(String symbol) {
        return new BinanceOptionsPositionSnapshot(
                symbol,
                "SHORT",
                decimal("-0.1000"),
                decimal("1200.00000000"),
                decimal("-120.00000000"),
                decimal("16.10430000"),
                decimal("1210.00000000"),
                decimal("126000"),
                1_764_000_000_000L,
                2,
                4,
                "CALL",
                "USDT",
                1_772_000_000_005L,
                decimal("0"),
                decimal("9.91")
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private final class FakeOrderSnapshots implements BinanceRestSnapshotReconciliationRuntime.OrderSnapshots {

        private final List<String> symbols = new ArrayList<>();
        private final List<BinanceOrderHistoryQuery> historyQueries = new ArrayList<>();
        private final List<BinanceTradeHistoryQuery> tradeQueries = new ArrayList<>();

        @Override
        public List<BinanceOrderResult> openOrders(String symbol) {
            symbols.add(symbol);
            return List.of(order(symbol));
        }

        @Override
        public List<BinanceOrderResult> allOrders(BinanceOrderHistoryQuery query) {
            historyQueries.add(query);
            return List.of(order(query.symbol()));
        }

        @Override
        public List<BinanceAccountTrade> accountTrades(BinanceTradeHistoryQuery query) {
            tradeQueries.add(query);
            return List.of(
                    trade(query.symbol(), 12345L, 239L),
                    trade(query.symbol(), 99999L, 240L)
            );
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

    private final class FakeOptionsSnapshots implements BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots {

        private final List<String> positionSymbols = new ArrayList<>();
        private int marginAccountCalls;

        @Override
        public BinanceOptionsMarginAccountSnapshot marginAccount() {
            marginAccountCalls++;
            return optionsMarginAccount();
        }

        @Override
        public List<BinanceOptionsPositionSnapshot> positions(String symbol) {
            positionSymbols.add(symbol);
            return List.of(optionsPosition(symbol));
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
