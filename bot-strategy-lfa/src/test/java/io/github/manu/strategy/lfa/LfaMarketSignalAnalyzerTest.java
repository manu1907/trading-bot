package io.github.manu.strategy.lfa;

import io.github.manu.events.v1.MarketDataEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.projection.TradingStateProjection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LfaMarketSignalAnalyzerTest {

    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");
    private final LfaMarketSignalAnalyzer analyzer = new LfaMarketSignalAnalyzer();

    @Test
    void emits_symbol_specific_long_signal_for_strong_bid_imbalance() {
        List<StrategySignalEvent> signals = analyzer.analyze(
                request(),
                List.of(marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010")),
                NOW
        );

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.getStrategyId()).isEqualTo("lfa");
            assertThat(signal.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(signal.getSignalType()).isEqualTo(StrategySignalType.ENTER_LONG);
            assertThat(signal.getTargetQuantity()).isEqualTo("0.001");
            assertThat(signal.getLimitPrice()).isEqualTo("50000.50");
            assertThat(signal.getAttributes())
                    .containsEntry("source", "lfa_market_signal_analyzer")
                    .containsKey("lfa_imbalance_ratio");
        });
    }

    @Test
    void emits_symbol_specific_short_signal_for_strong_ask_imbalance() {
        List<StrategySignalEvent> signals = analyzer.analyze(
                request(),
                List.of(marketData("ETHUSDT", "3000.00", "1.000", "3000.10", "2.000")),
                NOW
        );

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(signal.getSignalType()).isEqualTo(StrategySignalType.ENTER_SHORT);
            assertThat(signal.getLimitPrice()).isEqualTo("3000.00");
        });
    }

    @Test
    void suppresses_weak_or_thin_or_wide_markets_instead_of_emitting_ambiguous_signals() {
        List<StrategySignalEvent> signals = analyzer.analyze(
                request(),
                List.of(
                        marketData("BTCUSDT", "50000.00", "0.010", "50000.50", "0.009"),
                        marketData("ETHUSDT", "3000.00", "0.010", "3000.10", "0.020"),
                        marketData("SOLUSDT", "100.00", "10.0", "101.00", "20.0")
                ),
                NOW
        );

        assertThat(signals).isEmpty();
    }

    @Test
    void sorts_multiple_signals_by_confidence_then_symbol() {
        List<StrategySignalEvent> signals = analyzer.analyze(
                request(),
                List.of(
                        marketData("BTCUSDT", "50000.00", "0.020", "50000.50", "0.010"),
                        marketData("ETHUSDT", "3000.00", "3.000", "3000.10", "1.000")
                ),
                NOW
        );

        assertThat(signals).extracting(signal -> signal.getSymbol().toString())
                .containsExactly("ETHUSDT", "BTCUSDT");
    }

    @Test
    void validates_request_risk_inputs() {
        assertThatThrownBy(() -> new LfaSignalRequest(
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                BigDecimal.ONE,
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000,
                "0.001",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minImbalanceRatio must be greater than 1");
    }

    @Test
    void suppresses_stale_market_data() {
        TradingStateProjection.MarketDataState stale = new TradingStateProjection.MarketDataState(
                "binance",
                "demo",
                "usdm_futures",
                "BTCUSDT",
                MarketDataEventType.BOOK_TICKER.name(),
                "50000.00",
                "0.020",
                "50000.50",
                "0.010",
                NOW.minusSeconds(31),
                null,
                null,
                null,
                null,
                Map.of("stream", "btcusdt@bookTicker"),
                NOW.minusSeconds(31),
                "evt-stale"
        );

        assertThat(analyzer.analyze(request(), List.of(stale), NOW)).isEmpty();
    }

    private LfaSignalRequest request() {
        return LfaSignalRequest.conservativeUsdMFutures(
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "0.001"
        );
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
                MarketDataEventType.BOOK_TICKER.name(),
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
}
