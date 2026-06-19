package io.github.manu.strategy.lfa;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.exchange.ExchangeMetadata;
import io.github.manu.exchange.ExchangeMetadataFetcher;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.InstrumentExchangeMetadata;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.execution.StrategyInstrumentUniverseResolver;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.FileTradingStateProjectionStore;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LfaSignalRunnerTest {

    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();

    @TempDir
    private Path temporaryDirectory;

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
    void records_published_signal_runner_outcome_metrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledProperties(),
                projection,
                enabledExecutionProperties(),
                null,
                reconciliationTracker(ReconciliationConfidenceStatus.CONFIDENT),
                new LfaSignalRunnerMetrics(meterRegistry)
        );

        runner.runOnce();

        assertThat(meterRegistry.get(LfaSignalRunnerMetrics.RUN_EVENTS)
                        .tag("provider", "binance")
                        .tag("environment", "demo")
                        .tag("account", "main")
                        .tag("market", "usdm_futures")
                        .tag("enabled", "true")
                        .tag("status", "PUBLISHED")
                        .tag("reason", "lfa_signal_runner:published")
                        .tag("primary_blocker", "none")
                        .counter()
                        .count())
                .isEqualTo(1.0);
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
    void blocks_before_analysis_when_lifecycle_is_paused() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithLifecycle("PAUSED", 1, 1),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:lifecycle_blocked");
        assertThat(result.blockers()).containsExactly("lfa_lifecycle:paused");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_emergency_stop_even_when_allowed_lifecycle_states_include_it() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithLifecycle("EMERGENCY_STOP", List.of("ACTIVE", "EMERGENCY_STOP")),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:lifecycle_blocked");
        assertThat(result.blockers()).containsExactly("lfa_lifecycle:emergency_stop");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void rejects_unknown_lifecycle_state_configuration() {
        assertThatThrownBy(() -> enabledPropertiesWithLifecycle("BROKEN", 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lifecycleState must be a known LFA lifecycle state");
    }

    @Test
    void blocks_before_analysis_when_projected_market_data_warmup_is_incomplete() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithLifecycle("ACTIVE", 2, 2),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:warmup_incomplete");
        assertThat(result.blockers()).containsExactly(
                "lfa_warmup:market_data_symbols_below_min",
                "lfa_warmup:top_of_book_symbols_below_min"
        );
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
    void blocks_before_analysis_when_target_reconciliation_has_no_observations() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledProperties(),
                projection,
                enabledExecutionProperties(),
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC))
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:reconciliation_blocked");
        assertThat(result.blockers()).containsExactly("lfa_reconciliation:no_observations");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void restored_snapshot_blocks_signal_resume_until_target_reconciliation_is_observed_after_restart() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(projection.snapshot());
        TradingStateProjection restoredProjection = new TradingStateProjection();
        restoredProjection.restore(store.load().orElseThrow());
        LfaSignalRunner runner = runner(
                enabledProperties(),
                restoredProjection,
                enabledExecutionProperties(),
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC))
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:reconciliation_blocked");
        assertThat(result.blockers()).containsExactly("lfa_reconciliation:no_observations");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_target_reconciliation_is_degraded() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledProperties(),
                projection,
                enabledExecutionProperties(),
                reconciliationTracker(ReconciliationConfidenceStatus.MISSING_PROJECTION)
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:reconciliation_blocked");
        assertThat(result.blockers()).containsExactly("lfa_reconciliation:degraded");
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
    void ranks_analyzed_signals_by_expected_edge_before_publication() {
        TradingStateProjection projection = projectionWith(
                marketData(
                        "BTCUSDT",
                        "50000.00",
                        "0.100",
                        "50000.50",
                        "0.010",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                ),
                marketData(
                        "ETHUSDT",
                        "3000.00",
                        "0.600",
                        "3000.03",
                        "0.300",
                        Map.of("quoteVolume", "900000000", "numberOfTrades", "900000", "takerBuyQuoteVolume", "700000000")
                )
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation(null, null, null, 2),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        StrategySignalEvent first = (StrategySignalEvent) eventBus.envelopes().get(0).value();
        StrategySignalEvent second = (StrategySignalEvent) eventBus.envelopes().get(1).value();
        assertThat(first.getSymbol()).isEqualTo("ETHUSDT");
        assertThat(second.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(second.getConfidence()).isGreaterThan(first.getConfidence());
        assertThat(new BigDecimal(first.getAttributes().get("lfa_expected_edge_score").toString()))
                .isGreaterThan(new BigDecimal(second.getAttributes().get("lfa_expected_edge_score").toString()));
    }

    @Test
    void ranks_analyzed_signals_by_risk_money_management_fit_before_publication_cap() {
        TradingStateProjection projection = projectionWith(
                List.of(position("ETHUSDT", "BOTH", "0.300", "3000")),
                List.of(),
                marketData(
                        "BTCUSDT",
                        "50000.00",
                        "0.020",
                        "50000.50",
                        "0.010",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                ),
                marketData(
                        "ETHUSDT",
                        "3000.00",
                        "0.600",
                        "3000.03",
                        "0.300",
                        Map.of("quoteVolume", "900000000", "numberOfTrades", "900000", "takerBuyQuoteVolume", "700000000")
                )
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 2, null, "903.5", null, null, null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(1);
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.key().getSymbol()).isEqualTo("BTCUSDT");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getAttributes())
                    .containsEntry("lfa_risk_money_management_fit_score", "0.62977274")
                    .containsKey("lfa_expected_edge_score");
        });
    }

    @Test
    void analyzes_only_signal_planner_universe_symbols_when_universe_is_enabled() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.200", "50000.50", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                enabledProperties(),
                projection,
                executionProperties(true, instrumentUniverse(List.of("ETHUSDT"), true))
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement()
                .satisfies(envelope -> assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT"));
    }

    @Test
    void does_not_analyze_universe_symbols_when_required_exchange_metadata_resolver_is_unavailable() {
        TradingStateProjection projection = projectionWith(
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                enabledProperties(),
                projection,
                executionProperties(true, instrumentUniverse(List.of("ETHUSDT"), true, true))
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:no_signal");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void caps_candidate_market_data_by_projected_market_quality_before_analysis() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.100", "50010.00", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithCandidateCap(1),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement()
                .satisfies(envelope -> assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT"));
    }

    @Test
    void ranks_candidate_market_data_by_projected_daily_quote_volume_when_spread_matches() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010", "100000000"),
                marketData("ETHUSDT", "3000.00", "0.400", "3000.03", "0.200", "900000000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithCandidateCap(1),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getAttributes()).containsEntry("lfa_daily_quote_volume", "900000000");
        });
    }

    @Test
    void ranks_candidate_market_data_by_projected_trade_statistics_when_volume_matches() {
        TradingStateProjection projection = projectionWith(
                marketData(
                        "BTCUSDT",
                        "50000.00",
                        "0.020",
                        "50000.50",
                        "0.010",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                ),
                marketData(
                        "ETHUSDT",
                        "3000.00",
                        "0.400",
                        "3000.03",
                        "0.200",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "900000", "takerBuyQuoteVolume", "70000000")
                )
        );
        LfaSignalRunner runner = runner(
                propertiesWithCandidateCap(1),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getAttributes())
                    .containsEntry("lfa_daily_number_of_trades", "900000")
                    .containsEntry("lfa_daily_taker_buy_quote_volume", "70000000");
        });
    }

    @Test
    void ranks_candidate_market_data_by_provider_capability_when_market_statistics_match() {
        TradingStateProjection projection = projectionWith(
                marketData(
                        "BTCUSDT",
                        "50000.00",
                        "0.020",
                        "50000.50",
                        "0.010",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                ),
                marketData(
                        "ETHUSDT",
                        "3000.00",
                        "0.400",
                        "3000.03",
                        "0.200",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                )
        );
        LfaSignalRunner runner = runner(
                propertiesWithCandidateCap(1),
                projection,
                executionProperties(true, instrumentUniverse(List.of("BTCUSDT", "ETHUSDT"), true, true)),
                strategyInstrumentUniverseResolver(
                        instrument("BTCUSDT", "TRADING", "USDT", "PERPETUAL", List.of("LIMIT", "MARKET")),
                        instrument("ETHUSDT", "TRADING", "USDT", "PERPETUAL", List.of("LIMIT"))
                ),
                reconciliationTrackerForSymbols("BTCUSDT", "ETHUSDT")
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.key().getSymbol()).isEqualTo("BTCUSDT");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getAttributes()).containsEntry("lfa_provider_capability_score", "3.5");
        });
    }

    @Test
    void ranks_candidate_market_data_by_reconciliation_availability_when_market_quality_matches() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010", "100000000"),
                marketData("ETHUSDT", "3000.00", "0.400", "3000.03", "0.200", "100000000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithCandidateCap(1),
                projection,
                enabledExecutionProperties(),
                reconciliationTrackerForSymbols("ETHUSDT")
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            assertThat(envelope.key().getSymbol()).isEqualTo("ETHUSDT");
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getAttributes()).containsEntry("lfa_reconciliation_availability_score", "1");
        });
    }

    @Test
    void allocates_target_notional_from_projected_account_margin_balance() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, "15"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(eventBus.envelopes()).singleElement().satisfies(envelope -> {
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getTargetQuantity()).isNull();
            assertThat(signal.getTargetNotional()).isEqualTo("15");
            assertThat(signal.getAttributes())
                    .containsEntry("lfa_allocation_source", "account_margin_balance")
                    .containsEntry("lfa_allocation_base", "1000")
                    .containsEntry("lfa_allocation_fraction", "0.02")
                    .containsEntry("lfa_allocation_total_target_notional", "15")
                    .containsEntry("lfa_allocated_target_notional", "15");
        });
    }

    @Test
    void splits_allocated_target_notional_across_candidate_publish_slots() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, null, 2),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        assertThat(eventBus.envelopes()).hasSize(2).allSatisfy(envelope -> {
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getTargetQuantity()).isNull();
            assertThat(signal.getTargetNotional()).isEqualTo("10");
            assertThat(signal.getAttributes())
                    .containsEntry("lfa_allocation_total_target_notional", "20")
                    .containsEntry("lfa_allocated_target_notional", "10");
        });
    }

    @Test
    void caps_allocated_target_notional_by_strategy_run_notional_limit() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, null, 2, "EQUAL", "12"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        assertThat(eventBus.envelopes()).hasSize(2).allSatisfy(envelope -> {
            StrategySignalEvent signal = (StrategySignalEvent) envelope.value();
            assertThat(signal.getTargetQuantity()).isNull();
            assertThat(signal.getTargetNotional()).isEqualTo("6");
            assertThat(signal.getAttributes())
                    .containsEntry("lfa_allocation_total_target_notional", "12")
                    .containsEntry("lfa_allocated_target_notional", "6")
                    .containsEntry("lfa_allocation_strategy_run_notional_cap", "12");
        });
    }

    @Test
    void weights_allocated_target_notional_by_market_quality_when_configured() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010"),
                marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, null, 2, "MARKET_QUALITY"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        StrategySignalEvent first = (StrategySignalEvent) eventBus.envelopes().get(0).value();
        StrategySignalEvent second = (StrategySignalEvent) eventBus.envelopes().get(1).value();
        assertThat(first.getSymbol()).isEqualTo("ETHUSDT");
        assertThat(second.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(new BigDecimal(first.getTargetNotional().toString()))
                .isGreaterThan(new BigDecimal(second.getTargetNotional().toString()));
        assertThat(first.getAttributes())
                .containsEntry("lfa_allocation_weighting_mode", "MARKET_QUALITY")
                .containsKey("lfa_allocation_weight")
                .containsEntry("lfa_allocation_total_target_notional", "20");
        assertThat(second.getAttributes())
                .containsEntry("lfa_allocation_weighting_mode", "MARKET_QUALITY")
                .containsKey("lfa_allocation_weight");
    }

    @Test
    void weights_allocated_target_notional_by_projected_daily_quote_volume_when_configured() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010", "100000000"),
                marketData("ETHUSDT", "3000.00", "0.400", "3000.03", "0.200", "900000000")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, null, 2, "MARKET_QUALITY"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        StrategySignalEvent btc = signal("BTCUSDT");
        StrategySignalEvent eth = signal("ETHUSDT");
        assertThat(new BigDecimal(eth.getTargetNotional().toString()))
                .isGreaterThan(new BigDecimal(btc.getTargetNotional().toString()));
        assertThat(eth.getAttributes())
                .containsEntry("lfa_daily_quote_volume", "900000000")
                .containsEntry("lfa_allocation_weighting_mode", "MARKET_QUALITY");
    }

    @Test
    void weights_allocated_target_notional_by_projected_trade_statistics_when_configured() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000")),
                List.of(),
                marketData(
                        "BTCUSDT",
                        "50000.00",
                        "0.020",
                        "50000.50",
                        "0.010",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "100000", "takerBuyQuoteVolume", "50000000")
                ),
                marketData(
                        "ETHUSDT",
                        "3000.00",
                        "0.400",
                        "3000.03",
                        "0.200",
                        Map.of("quoteVolume", "100000000", "numberOfTrades", "900000", "takerBuyQuoteVolume", "70000000")
                )
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, null, 2, "MARKET_QUALITY"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(2);
        StrategySignalEvent btc = signal("BTCUSDT");
        StrategySignalEvent eth = signal("ETHUSDT");
        assertThat(new BigDecimal(eth.getTargetNotional().toString()))
                .isGreaterThan(new BigDecimal(btc.getTargetNotional().toString()));
        assertThat(eth.getAttributes())
                .containsEntry("lfa_daily_number_of_trades", "900000")
                .containsEntry("lfa_daily_taker_buy_quote_volume", "70000000")
                .containsEntry("lfa_allocation_weighting_mode", "MARKET_QUALITY");
    }

    @Test
    void blocks_allocation_when_margin_balance_is_required_but_missing() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                propertiesWithAllocation("0.02", null, "15"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:allocation_blocked");
        assertThat(result.blockers()).containsExactly("lfa_allocation:account_margin_balance_missing");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_fixed_sizing_when_strategy_run_notional_limit_would_be_exceeded() {
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                propertiesWithFixedStrategyRunNotionalCap("10"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:allocation_blocked");
        assertThat(result.blockers()).containsExactly("lfa_allocation:max_strategy_run_notional");
        assertThat(eventBus.envelopes()).isEmpty();
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
    void records_disabled_signal_runner_outcome_metrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LfaSignalRunner runner = runner(
                disabledProperties(),
                new TradingStateProjection(),
                enabledExecutionProperties(),
                null,
                reconciliationTracker(ReconciliationConfidenceStatus.CONFIDENT),
                new LfaSignalRunnerMetrics(meterRegistry)
        );

        runner.runOnce();

        assertThat(meterRegistry.get(LfaSignalRunnerMetrics.RUN_EVENTS)
                        .tag("provider", "unknown")
                        .tag("environment", "unknown")
                        .tag("account", "unknown")
                        .tag("market", "unknown")
                        .tag("enabled", "false")
                        .tag("status", "DISABLED")
                        .tag("reason", "lfa_signal_runner:disabled")
                        .tag("primary_blocker", "none")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void records_blocked_signal_runner_outcome_metrics_with_primary_blocker() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TradingStateProjection projection = projectionWith(
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithLifecycle("PAUSED", 1, 1),
                projection,
                enabledExecutionProperties(),
                null,
                reconciliationTracker(ReconciliationConfidenceStatus.CONFIDENT),
                new LfaSignalRunnerMetrics(meterRegistry)
        );

        runner.runOnce();

        assertThat(meterRegistry.get(LfaSignalRunnerMetrics.RUN_EVENTS)
                        .tag("provider", "binance")
                        .tag("environment", "demo")
                        .tag("account", "main")
                        .tag("market", "usdm_futures")
                        .tag("enabled", "true")
                        .tag("status", "BLOCKED")
                        .tag("reason", "lfa_signal_runner:lifecycle_blocked")
                        .tag("primary_blocker", "lfa_lifecycle:paused")
                        .counter()
                        .count())
                .isEqualTo(1.0);
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
    void blocks_before_analysis_when_account_open_order_budget_is_full() {
        TradingStateProjection projection = projectionWithOrders(
                List.of(openOrder("ETHUSDT"), openOrder("SOLUSDT")),
                List.of(),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithOpenOrderBudgets(2, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_open_orders");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_symbol_open_order_budget_is_full() {
        TradingStateProjection projection = projectionWithOrders(
                List.of(openOrder("BTCUSDT")),
                List.of(),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithOpenOrderBudgets(null, 1),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.candidateSignals()).isEqualTo(1);
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_open_orders");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_account_open_order_notional_budget_is_exceeded() {
        TradingStateProjection projection = projectionWithOrders(
                List.of(openOrder("ETHUSDT")),
                List.of(),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithOpenOrderBudgets(null, null, "40", null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_open_order_notional");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_projected_symbol_open_order_notional_exceeds_budget() {
        TradingStateProjection projection = projectionWithOrders(
                List.of(openOrder("BTCUSDT")),
                List.of(),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithOpenOrderBudgets(null, null, null, "80"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.candidateSignals()).isEqualTo(1);
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_open_order_notional");
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
                enabledPropertiesWithBudgets(3, 1, "640", null, null, null, null, null),
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
                enabledPropertiesWithBudgets(3, 1, null, null, null, null, null, "10"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_daily_realized_loss");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_current_account_unrealized_loss_exceeds_budget() {
        TradingStateProjection projection = projectionWith(
                List.of(
                        position("ETHUSDT", "BOTH", "0.20", "3000", "-12"),
                        position("SOLUSDT", "BOTH", "1.00", "100", "3")
                ),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 1, null, null, "10", null, null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_unrealized_loss");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_signal_when_current_symbol_unrealized_loss_exceeds_budget() {
        TradingStateProjection projection = projectionWith(
                List.of(position("BTCUSDT", "BOTH", "0.001", "50000", "-7")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 2, null, null, null, "5", null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.candidateSignals()).isEqualTo(1);
        assertThat(result.blockers()).containsExactly("lfa_budget:max_symbol_unrealized_loss");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void symbol_unrealized_loss_budget_ignores_missing_pnl_on_other_symbols() {
        TradingStateProjection projection = projectionWith(
                List.of(
                        position("BTCUSDT", "BOTH", "0.001", "50000", "-3"),
                        position("ETHUSDT", "BOTH", "0.100", "3000", null)
                ),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithBudgets(3, 2, null, null, null, "5", null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:published");
        assertThat(result.publishedSignals()).isEqualTo(1);
        assertThat(eventBus.envelopes()).hasSize(1);
    }

    @Test
    void blocks_before_analysis_when_account_margin_balance_is_below_floor() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("700", "1000", "100")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithAccountHealth("750", null, null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:min_account_margin_balance");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_account_margin_drawdown_exceeds_cap() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("700", "1000", "100")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithAccountHealth(null, "0.20", null),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_margin_drawdown");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_before_analysis_when_account_margin_utilization_exceeds_cap() {
        TradingStateProjection projection = projectionWith(
                List.of(),
                List.of(risk("1000", "1000", "850")),
                List.of(),
                marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")
        );
        LfaSignalRunner runner = runner(
                enabledPropertiesWithAccountHealth(null, null, "0.80"),
                projection,
                enabledExecutionProperties()
        );

        LfaSignalRunner.LfaSignalRunResult result = runner.runOnce();

        assertThat(result.reason()).isEqualTo("lfa_signal_runner:budget_blocked");
        assertThat(result.blockers()).containsExactly("lfa_budget:max_account_margin_utilization");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    private LfaSignalRunner runner(
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            ExecutionProperties executionProperties
    ) {
        return runner(
                properties,
                projection,
                executionProperties,
                reconciliationTracker(ReconciliationConfidenceStatus.CONFIDENT)
        );
    }

    private LfaSignalRunner runner(
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            ExecutionProperties executionProperties,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker
    ) {
        return runner(properties, projection, executionProperties, null, reconciliationConfidenceTracker);
    }

    private LfaSignalRunner runner(
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            ExecutionProperties executionProperties,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker
    ) {
        return runner(
                properties,
                projection,
                executionProperties,
                instrumentUniverseResolver,
                reconciliationConfidenceTracker,
                new LfaSignalRunnerMetrics()
        );
    }

    private LfaSignalRunner runner(
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            ExecutionProperties executionProperties,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            LfaSignalRunnerMetrics metrics
    ) {
        return new LfaSignalRunner(
                new LfaMarketSignalAnalyzer(),
                properties,
                projection,
                eventBus,
                executionProperties,
                instrumentUniverseResolver,
                reconciliationConfidenceTracker,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ReconciliationConfidenceTracker reconciliationTracker(ReconciliationConfidenceStatus status) {
        ReconciliationConfidenceTracker tracker = new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        tracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                TradingEventType.ORDER_RESULT,
                "binance/demo/main/usdm_futures/BTCUSDT",
                status,
                List.of()
        ));
        return tracker;
    }

    private ReconciliationConfidenceTracker reconciliationTrackerForSymbols(String... symbols) {
        ReconciliationConfidenceTracker tracker = new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        for (String symbol : symbols) {
            tracker.record(new ReconciliationObservation(
                    "binance",
                    "demo",
                    "main",
                    "usdm_futures",
                    TradingEventType.ORDER_RESULT,
                    "binance|demo|main|usdm_futures|" + symbol,
                    ReconciliationConfidenceStatus.CONFIDENT,
                    List.of()
            ));
        }
        return tracker;
    }

    private StrategyInstrumentUniverseResolver strategyInstrumentUniverseResolver(
            InstrumentExchangeMetadata.Instrument... instruments
    ) {
        return new StrategyInstrumentUniverseResolver(
                new ExchangeMetadataService(List.of(new StaticMetadataFetcher(new Metadata(List.of(instruments))))),
                (io.github.manu.config.runtime.ConfigManager) null
        );
    }

    private InstrumentExchangeMetadata.Instrument instrument(
            String symbol,
            String status,
            String quoteAsset,
            String contractType,
            List<String> orderTypes
    ) {
        return new InstrumentExchangeMetadata.Instrument(
                symbol,
                status,
                symbol.replace(quoteAsset, ""),
                quoteAsset,
                contractType,
                orderTypes
        );
    }

    private LfaStrategyProperties.SignalRunner enabledProperties() {
        return enabledPropertiesWithBudgets(3, 1, null, null, null, null, null, null);
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithLifecycle(
            String lifecycleState,
            Integer minWarmupMarketDataSymbols,
            Integer minWarmupTopOfBookSymbols
    ) {
        return enabledPropertiesWithLifecycle(
                lifecycleState,
                List.of("ACTIVE"),
                minWarmupMarketDataSymbols,
                minWarmupTopOfBookSymbols
        );
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithLifecycle(
            String lifecycleState,
            List<String> allowedLifecycleStates
    ) {
        return enabledPropertiesWithLifecycle(lifecycleState, allowedLifecycleStates, 1, 1);
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithLifecycle(
            String lifecycleState,
            List<String> allowedLifecycleStates,
            Integer minWarmupMarketDataSymbols,
            Integer minWarmupTopOfBookSymbols
    ) {
        return properties(
                true,
                lifecycleState,
                allowedLifecycleStates,
                minWarmupMarketDataSymbols,
                minWarmupTopOfBookSymbols,
                null,
                null,
                null,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithBudgets(
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            String maxAccountPositionNotional,
            String maxSymbolPositionNotional,
            String maxAccountUnrealizedLoss,
            String maxSymbolUnrealizedLoss,
            String maxAccountDailyRealizedLoss,
            String maxSymbolDailyRealizedLoss
    ) {
        return properties(
                true,
                "ACTIVE",
                1,
                1,
                null,
                null,
                null,
                maxAccountOpenPositions,
                maxSymbolOpenPositions,
                maxAccountPositionNotional,
                maxSymbolPositionNotional,
                maxAccountUnrealizedLoss,
                maxSymbolUnrealizedLoss,
                maxAccountDailyRealizedLoss,
                maxSymbolDailyRealizedLoss
        );
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithOpenOrderBudgets(
            Integer maxAccountOpenOrders,
            Integer maxSymbolOpenOrders
    ) {
        return enabledPropertiesWithOpenOrderBudgets(maxAccountOpenOrders, maxSymbolOpenOrders, null, null);
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithOpenOrderBudgets(
            Integer maxAccountOpenOrders,
            Integer maxSymbolOpenOrders,
            String maxAccountOpenOrderNotional,
            String maxSymbolOpenOrderNotional
    ) {
        return properties(
                true,
                "ACTIVE",
                List.of("ACTIVE"),
                1,
                1,
                null,
                maxAccountOpenOrders,
                maxSymbolOpenOrders,
                maxAccountOpenOrderNotional,
                maxSymbolOpenOrderNotional,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private LfaStrategyProperties.SignalRunner enabledPropertiesWithAccountHealth(
            String minAccountMarginBalance,
            String maxAccountMarginDrawdownFraction,
            String maxAccountMarginUtilization
    ) {
        return properties(
                true,
                "ACTIVE",
                List.of("ACTIVE"),
                1,
                1,
                null,
                null,
                null,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                minAccountMarginBalance,
                maxAccountMarginDrawdownFraction,
                maxAccountMarginUtilization,
                null,
                null
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithCandidateCap(Integer maxCandidateMarketDataSymbols) {
        return properties(
                true,
                "ACTIVE",
                1,
                1,
                maxCandidateMarketDataSymbols,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithAllocation(
            String targetNotionalMarginBalanceFraction,
            String minAllocatedTargetNotional,
            String maxAllocatedTargetNotional
    ) {
        return propertiesWithAllocation(
                targetNotionalMarginBalanceFraction,
                minAllocatedTargetNotional,
                maxAllocatedTargetNotional,
                1
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithAllocation(
            String targetNotionalMarginBalanceFraction,
            String minAllocatedTargetNotional,
            String maxAllocatedTargetNotional,
            int maxSignalsPerRun
    ) {
        return propertiesWithAllocation(
                targetNotionalMarginBalanceFraction,
                minAllocatedTargetNotional,
                maxAllocatedTargetNotional,
                maxSignalsPerRun,
                "EQUAL"
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithAllocation(
            String targetNotionalMarginBalanceFraction,
            String minAllocatedTargetNotional,
            String maxAllocatedTargetNotional,
            int maxSignalsPerRun,
            String allocationWeightingMode
    ) {
        return propertiesWithAllocation(
                targetNotionalMarginBalanceFraction,
                minAllocatedTargetNotional,
                maxAllocatedTargetNotional,
                maxSignalsPerRun,
                allocationWeightingMode,
                null
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithAllocation(
            String targetNotionalMarginBalanceFraction,
            String minAllocatedTargetNotional,
            String maxAllocatedTargetNotional,
            int maxSignalsPerRun,
            String allocationWeightingMode,
            String maxStrategyRunNotional
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
                "ACTIVE",
                List.of("ACTIVE"),
                null,
                false,
                true,
                1,
                1,
                30_000L,
                true,
                null,
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000L,
                "0.001",
                null,
                decimal(targetNotionalMarginBalanceFraction),
                decimal(minAllocatedTargetNotional),
                decimal(maxAllocatedTargetNotional),
                decimal(maxStrategyRunNotional),
                true,
                allocationWeightingMode,
                new BigDecimal("100000000"),
                new BigDecimal("100000"),
                new BigDecimal("50000000"),
                maxSignalsPerRun,
                null,
                null,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true,
                true
        );
    }

    private LfaStrategyProperties.SignalRunner propertiesWithFixedStrategyRunNotionalCap(
            String maxStrategyRunNotional
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
                "ACTIVE",
                List.of("ACTIVE"),
                null,
                false,
                true,
                1,
                1,
                30_000L,
                true,
                null,
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000L,
                "0.001",
                null,
                null,
                null,
                null,
                decimal(maxStrategyRunNotional),
                true,
                "EQUAL",
                new BigDecimal("100000000"),
                new BigDecimal("100000"),
                new BigDecimal("50000000"),
                1,
                null,
                null,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true,
                true
        );
    }

    private LfaStrategyProperties.SignalRunner properties(
            boolean enabled,
            String lifecycleState,
            Integer minWarmupMarketDataSymbols,
            Integer minWarmupTopOfBookSymbols,
            Integer maxCandidateMarketDataSymbols,
            Integer maxAccountOpenOrders,
            Integer maxSymbolOpenOrders,
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            String maxAccountPositionNotional,
            String maxSymbolPositionNotional,
            String maxAccountUnrealizedLoss,
            String maxSymbolUnrealizedLoss,
            String maxAccountDailyRealizedLoss,
            String maxSymbolDailyRealizedLoss
    ) {
        return properties(
                enabled,
                lifecycleState,
                List.of("ACTIVE"),
                minWarmupMarketDataSymbols,
                minWarmupTopOfBookSymbols,
                maxCandidateMarketDataSymbols,
                maxAccountOpenOrders,
                maxSymbolOpenOrders,
                null,
                null,
                maxAccountOpenPositions,
                maxSymbolOpenPositions,
                maxAccountPositionNotional,
                maxSymbolPositionNotional,
                maxAccountUnrealizedLoss,
                maxSymbolUnrealizedLoss,
                null,
                null,
                null,
                maxAccountDailyRealizedLoss,
                maxSymbolDailyRealizedLoss
        );
    }

    private LfaStrategyProperties.SignalRunner properties(
            boolean enabled,
            String lifecycleState,
            List<String> allowedLifecycleStates,
            Integer minWarmupMarketDataSymbols,
            Integer minWarmupTopOfBookSymbols,
            Integer maxCandidateMarketDataSymbols,
            Integer maxAccountOpenOrders,
            Integer maxSymbolOpenOrders,
            String maxAccountOpenOrderNotional,
            String maxSymbolOpenOrderNotional,
            Integer maxAccountOpenPositions,
            Integer maxSymbolOpenPositions,
            String maxAccountPositionNotional,
            String maxSymbolPositionNotional,
            String maxAccountUnrealizedLoss,
            String maxSymbolUnrealizedLoss,
            String minAccountMarginBalance,
            String maxAccountMarginDrawdownFraction,
            String maxAccountMarginUtilization,
            String maxAccountDailyRealizedLoss,
            String maxSymbolDailyRealizedLoss
    ) {
        return new LfaStrategyProperties.SignalRunner(
                enabled,
                30_000L,
                30_000L,
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                lifecycleState,
                allowedLifecycleStates,
                null,
                false,
                true,
                minWarmupMarketDataSymbols,
                minWarmupTopOfBookSymbols,
                30_000L,
                true,
                maxCandidateMarketDataSymbols,
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000L,
                "0.001",
                null,
                null,
                null,
                null,
                null,
                true,
                "EQUAL",
                new BigDecimal("100000000"),
                new BigDecimal("100000"),
                new BigDecimal("50000000"),
                1,
                maxAccountOpenOrders,
                maxSymbolOpenOrders,
                decimal(maxAccountOpenOrderNotional),
                decimal(maxSymbolOpenOrderNotional),
                maxAccountOpenPositions,
                maxSymbolOpenPositions,
                decimal(maxAccountPositionNotional),
                decimal(maxSymbolPositionNotional),
                decimal(maxAccountUnrealizedLoss),
                decimal(maxSymbolUnrealizedLoss),
                decimal(minAccountMarginBalance),
                decimal(maxAccountMarginDrawdownFraction),
                decimal(maxAccountMarginUtilization),
                decimal(maxAccountDailyRealizedLoss),
                decimal(maxSymbolDailyRealizedLoss),
                true,
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
        return executionProperties(signalPlannerEnabled, null);
    }

    private ExecutionProperties executionProperties(
            boolean signalPlannerEnabled,
            ExecutionProperties.SignalPlanner.InstrumentUniverse instrumentUniverse
    ) {
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
                        ),
                        List.of(),
                        instrumentUniverse
                ),
                null
        );
    }

    private ExecutionProperties.SignalPlanner.InstrumentUniverse instrumentUniverse(
            List<String> includedSymbols,
            boolean requireIncludedSymbol
    ) {
        return instrumentUniverse(includedSymbols, requireIncludedSymbol, false);
    }

    private ExecutionProperties.SignalPlanner.InstrumentUniverse instrumentUniverse(
            List<String> includedSymbols,
            boolean requireIncludedSymbol,
            boolean requireExchangeMetadata
    ) {
        return new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                true,
                includedSymbols,
                List.of(),
                false,
                requireExchangeMetadata,
                requireIncludedSymbol,
                true,
                false,
                "TRADING",
                null,
                List.of(),
                List.of(),
                null,
                true,
                true,
                30_000L,
                "5",
                "250",
                null,
                List.of()
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
        return projectionWith(positions, List.of(), dailyPnl, marketData);
    }

    private TradingStateProjection projectionWith(
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.DailyRealizedPnlState> dailyPnl,
            TradingStateProjection.MarketDataState... marketData
    ) {
        return projectionWith(List.of(), positions, risks, dailyPnl, marketData);
    }

    private TradingStateProjection projectionWithOrders(
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.DailyRealizedPnlState> dailyPnl,
            TradingStateProjection.MarketDataState... marketData
    ) {
        return projectionWith(orders, positions, List.of(), dailyPnl, marketData);
    }

    private TradingStateProjection projectionWith(
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.DailyRealizedPnlState> dailyPnl,
            TradingStateProjection.MarketDataState... marketData
    ) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                positions,
                orders,
                risks,
                List.of(marketData),
                dailyPnl,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection.RiskState risk(String marginBalance) {
        return risk(marginBalance, marginBalance, null);
    }

    private TradingStateProjection.RiskState risk(
            String marginBalance,
            String maxMarginBalance,
            String maintenanceMargin
    ) {
        return new TradingStateProjection.RiskState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "ACCOUNT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                marginBalance,
                maxMarginBalance,
                maintenanceMargin,
                NOW,
                "risk-account"
        );
    }

    private TradingStateProjection.OrderState openOrder(String symbol) {
        return new TradingStateProjection.OrderState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                symbol,
                "cmd-open-" + symbol,
                "client-open-" + symbol,
                "exchange-open-" + symbol,
                "ACCEPTED",
                "NEW",
                "BUY",
                "LIMIT",
                "50000.00",
                "0.001",
                "0",
                null,
                null,
                "ORDER_RESULT",
                "NEW",
                true,
                false,
                null,
                NOW,
                "order-" + symbol
        );
    }

    private TradingStateProjection.PositionState position(
            String symbol,
            String positionSide,
            String amount,
            String markPrice
    ) {
        return position(symbol, positionSide, amount, markPrice, "0");
    }

    private TradingStateProjection.PositionState position(
            String symbol,
            String positionSide,
            String amount,
            String markPrice,
            String unrealizedPnl
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
                unrealizedPnl,
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

    private StrategySignalEvent signal(String symbol) {
        return eventBus.envelopes().stream()
                .map(envelope -> (StrategySignalEvent) envelope.value())
                .filter(signal -> symbol.contentEquals(signal.getSymbol()))
                .findFirst()
                .orElseThrow();
    }

    private TradingStateProjection.MarketDataState marketData(
            String symbol,
            String bidPrice,
            String bidQuantity,
            String askPrice,
            String askQuantity
    ) {
        return marketData(symbol, bidPrice, bidQuantity, askPrice, askQuantity, Map.of());
    }

    private TradingStateProjection.MarketDataState marketData(
            String symbol,
            String bidPrice,
            String bidQuantity,
            String askPrice,
            String askQuantity,
            String quoteVolume
    ) {
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("stream", symbol.toLowerCase() + "@bookTicker");
        if (quoteVolume != null) {
            attributes.put("quoteVolume", quoteVolume);
        }
        return marketData(symbol, bidPrice, bidQuantity, askPrice, askQuantity, attributes);
    }

    private TradingStateProjection.MarketDataState marketData(
            String symbol,
            String bidPrice,
            String bidQuantity,
            String askPrice,
            String askQuantity,
            Map<String, String> attributes
    ) {
        Map<String, String> resolvedAttributes = new java.util.LinkedHashMap<>();
        resolvedAttributes.put("stream", symbol.toLowerCase() + "@bookTicker");
        if (attributes != null) {
            resolvedAttributes.putAll(attributes);
        }
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
                resolvedAttributes,
                NOW,
                "evt-" + symbol
        );
    }

    private record Metadata(List<InstrumentExchangeMetadata.Instrument> instruments) implements InstrumentExchangeMetadata {

        private Metadata {
            instruments = List.copyOf(instruments);
        }

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Instant fetchedAt() {
            return NOW;
        }
    }

    private record StaticMetadataFetcher(Metadata metadata) implements ExchangeMetadataFetcher {

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Optional<? extends ExchangeMetadata> current() {
            return Optional.of(metadata);
        }

        @Override
        public Optional<? extends ExchangeMetadata> refresh(ResolvedExchangeConfig config) {
            return current();
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
