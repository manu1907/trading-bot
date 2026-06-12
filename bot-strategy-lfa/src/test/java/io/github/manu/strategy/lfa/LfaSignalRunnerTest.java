package io.github.manu.strategy.lfa;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class LfaSignalRunnerTest {

    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();

    @Test
    void publishes_symbol_specific_strategy_signal_from_projected_market_data() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.candidateSignals()).isEqualTo(1);
        assertThat(result.publishedSignals()).isEqualTo(1);
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.STRATEGY_SIGNAL);
            assertThat(envelope.key().getSymbol()).isEqualTo("BTCUSDT");
            assertThat(envelope.key().getProvider()).isEqualTo("binance");
            assertThat(envelope.key().getEnvironment()).isEqualTo("demo");
            assertThat(envelope.key().getAccount()).isEqualTo("main");
            assertThat(envelope.key().getMarket()).isEqualTo("usdm_futures");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getSymbol()).isEqualTo("BTCUSDT");
        });
    }

    @Test
    void blocks_when_signal_planner_is_required_but_disabled() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, disabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:signal_planner_disabled");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void returns_no_signal_without_publishing_when_projected_market_is_not_admissible() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.010", "50000.50", "0.009")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:no_signal");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void respects_max_signals_per_run_after_analyzer_ranking() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.publishedSignals()).isEqualTo(1);
        assertThat(eventBus.envelopes()).singleElement()
                .satisfies(envelope -> assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT"));
    }

    @Test
    void stays_disabled_without_publishing() {
        LfaSignalRunner runner = runner(disabledProperties(), new TradingStateProjection(), enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason()).isEqualTo("lfa_signal_runner:disabled");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_account_open_position_budget_is_full() {
        TradingStateProjection projection = projectionWith(
                List.of(
                        position("ETHUSDT", "BOTH", "0.10", "3000"),
                        position("SOLUSDT", "BOTH", "1.00", "100"),
                        position("BNBUSDT", "BOTH", "1.00", "600")
                ),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_open_positions");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_symbol_open_position_budget_is_full() {
        TradingStateProjection projection = projectionWith(
                List.of(position("BTCUSDT", "BOTH", "0.001", "50000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(enabledProperties(), projection, enabledExecutionProperties());

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.candidateSignals()).isEqualTo(1);
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_open_positions");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_projected_account_position_notional_exceeds_budget() {
        TradingStateProjection projection = projectionWith(
                List.of(position("ETHUSDT", "BOTH", "0.20", "3000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 1, "640", null, null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_position_notional");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_current_symbol_daily_realized_loss_exceeds_budget() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(
                        dailyPnl(null, "-5"),
                        dailyPnl("BTCUSDT", "-11")
                ),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 1, null, null, null, "10"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_daily_realized_loss");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    private LfaSignalRunner runner(
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            ExecutionProperties executionProperties
    ) {
        return new LfaSignalRunner(
                new LfaMarketSignalAnalyzer(),
                properties,
                projection,
                eventBus,
                executionProperties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private LfaStrategyProperties.SignalRunner enabledProperties() {
        return enabledPropertiesWithBudgets(3, 1, null, null, null, null);
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithBudgets(
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            String maxAccountPositionNotional,
            String maxSymbolPositionNotional,
            String maxAccountDailyRealizedLoss,
            String maxSymbolDailyRealizedLoss
    ) {
        return new LfaStrategyProperties.SignalRunner(
                true,
                30_000L,
                30_000L,
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000L,
                "0.001",
                null,
                1,
                maxAccountOpenPositions,
                maxSymbolOpenPositions,
                decimal(maxAccountPositionNotional),
                decimal(maxSymbolPositionNotional),
                decimal(maxAccountDailyRealizedLoss),
                decimal(maxSymbolDailyRealizedLoss),
                true,
                true
        );
    }

    private LfaStrategyProperties.SignalRunner disabledProperties() {
        return LfaStrategyProperties.SignalRunner.disabled();
    }

    private ExecutionProperties enabledExecutionProperties() {
        return executionProperties(true);
    }

    private ExecutionProperties disabledExecutionProperties() {
        return executionProperties(false);
    }

    private ExecutionProperties executionProperties(boolean signalPlannerEnabled) {
        return new ExecutionProperties(
                new ExecutionProperties.SignalPlanner(
                        signalPlannerEnabled,
                        new ExecutionProperties.SignalPlanner.Defaults(
                                "binance",
                                "demo",
                                "main",
                                "usdm_futures",
                                "BTCUSDT",
                                "GTC",
                                "tb-demo"
                        )
                ),
                null
        );
    }

    private TradingStateProjection projectionWith(TradingStateProjection.MarketDataState... marketData) {
        return projectionWith(List.of(), List.of(), marketData);
    }

    private TradingStateProjection projectionWith(
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.DailyRealizedPnlState> dailyPnl,
            TradingStateProjection.MarketDataState... marketData
    ) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                positions,
                List.of(),
                List.of(),
                List.of(marketData),
                dailyPnl,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection.PositionState position(
            String symbol,
            String positionSide,
            String amount,
            String markPrice
    ) {
        return new TradingStateProjection.PositionState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                symbol,
                positionSide,
                amount,
                markPrice,
                markPrice,
                "0",
                "1",
                "cross",
                null,
                "USER_DATA",
                false,
                null,
                NOW,
                "position-" + symbol
        );
    }

    private TradingStateProjection.DailyRealizedPnlState dailyPnl(String symbol, String pnl) {
        return new TradingStateProjection.DailyRealizedPnlState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                symbol,
                "2026-06-12",
                pnl,
                NOW,
                "daily-pnl-" + (symbol == null ? "account" : symbol)
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private TradingStateProjection.MarketDataState marketData(
            String symbol,
            String bidPrice,
            String bidQuantity,
            String askPrice,
            String askQuantity
    ) {
        return new TradingStateProjection.MarketDataState(
                "binance",
                "demo",
                "usdm_futures",
                symbol,
                "BOOK_TICKER",
                bidPrice,
                bidQuantity,
                askPrice,
                askQuantity,
                NOW,
                null,
                null,
                null,
                null,
                Map.of("stream", symbol.toLowerCase() + "@bookTicker"),
                NOW,
                "evt-" + symbol
        );
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
                    envelopes.size() - 1L
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }

        private List<TradingEventEnvelope<? extends SpecificRecord>> envelopes() {
            return List.copyOf(envelopes);
        }
    }
}
