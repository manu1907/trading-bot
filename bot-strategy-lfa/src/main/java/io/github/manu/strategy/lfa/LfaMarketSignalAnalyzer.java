package io.github.manu.strategy.lfa;

import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class LfaMarketSignalAnalyzer {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    public List<StrategySignalEvent> analyze(
            LfaSignalRequest request,
            List<TradingStateProjection.MarketDataState> marketData,
            Instant emittedAt
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(marketData, "marketData");
        Objects.requireNonNull(emittedAt, "emittedAt");

        return marketData.stream()
                .map(state -> signal(request, state, emittedAt))
                .flatMap(Optional::stream)
                .sorted(Comparator
                        .comparing((StrategySignalEvent signal) -> -signal.getConfidence())
                        .thenComparing(signal -> signal.getSymbol().toString()))
                .toList();
    }

    private Optional<StrategySignalEvent> signal(
            LfaSignalRequest request,
            TradingStateProjection.MarketDataState state,
            Instant emittedAt
    ) {
        if (!matchesTarget(request, state)) {
            return Optional.empty();
        }
        long marketDataAgeMillis = marketDataAgeMillis(request, state, emittedAt).orElse(-1L);
        if (marketDataAgeMillis < 0 || marketDataAgeMillis > request.maxMarketDataAgeMillis()) {
            return Optional.empty();
        }
        TopOfBook topOfBook = topOfBook(state).orElse(null);
        if (topOfBook == null) {
            return Optional.empty();
        }
        BigDecimal spreadBps = spreadBps(topOfBook).orElse(null);
        if (spreadBps == null || spreadBps.compareTo(request.maxSpreadBps()) > 0) {
            return Optional.empty();
        }
        BigDecimal bidNotional = topOfBook.bidPrice().multiply(topOfBook.bidQuantity());
        BigDecimal askNotional = topOfBook.askPrice().multiply(topOfBook.askQuantity());
        BigDecimal effectiveDepth = bidNotional.min(askNotional);
        if (effectiveDepth.compareTo(request.minTopOfBookQuoteNotional()) < 0) {
            return Optional.empty();
        }
        StrategySignalType signalType = signalType(request, bidNotional, askNotional).orElse(null);
        if (signalType == null) {
            return Optional.empty();
        }
        BigDecimal imbalanceRatio = larger(bidNotional, askNotional)
                .divide(smaller(bidNotional, askNotional), 8, RoundingMode.HALF_UP);
        double confidence = confidence(request, spreadBps, effectiveDepth, imbalanceRatio);
        String symbol = state.symbol().toUpperCase(Locale.ROOT);
        String emittedAtMicros = Long.toString(epochMicros(emittedAt));
        String signalId = String.join(":",
                request.strategyId(),
                request.provider(),
                request.environment(),
                request.account(),
                request.market(),
                symbol,
                emittedAtMicros
        );

        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("source", "lfa_market_signal_analyzer");
        attributes.put("lfa_signal_reason", "top_of_book_imbalance");
        attributes.put("lfa_market_data_age_millis", Long.toString(marketDataAgeMillis));
        attributes.put("lfa_spread_bps", spreadBps.toPlainString());
        attributes.put("lfa_top_of_book_quote_notional", effectiveDepth.toPlainString());
        attributes.put("lfa_bid_quote_notional", bidNotional.toPlainString());
        attributes.put("lfa_ask_quote_notional", askNotional.toPlainString());
        attributes.put("lfa_imbalance_ratio", imbalanceRatio.toPlainString());
        String quoteVolume = state.attributes().get("quoteVolume");
        if (positive(decimal(quoteVolume))) {
            attributes.put("lfa_daily_quote_volume", quoteVolume.trim());
        }
        String numberOfTrades = state.attributes().get("numberOfTrades");
        if (positive(decimal(numberOfTrades))) {
            attributes.put("lfa_daily_number_of_trades", numberOfTrades.trim());
        }
        String takerBuyQuoteVolume = state.attributes().get("takerBuyQuoteVolume");
        if (positive(decimal(takerBuyQuoteVolume))) {
            attributes.put("lfa_daily_taker_buy_quote_volume", takerBuyQuoteVolume.trim());
        }

        StrategySignalEvent event = StrategySignalEvent.newBuilder()
                .setEventId("strategy-signal:" + signalId)
                .setSchemaVersion(1)
                .setSignalId(signalId)
                .setStrategyId(request.strategyId())
                .setProvider(request.provider())
                .setEnvironment(request.environment())
                .setAccount(request.account())
                .setMarket(request.market())
                .setSymbol(symbol)
                .setSignalType(signalType)
                .setConfidence(confidence)
                .setTargetQuantity(request.targetQuantity())
                .setTargetNotional(request.targetNotional())
                .setLimitPrice(limitPrice(signalType, topOfBook))
                .setStopPrice(null)
                .setEmittedAtMicros(emittedAt)
                .setFeatures(Map.<CharSequence, CharSequence>of("order_type", "LIMIT", "time_in_force", "GTC"))
                .setAttributes(attributes)
                .build();
        return Optional.of(event);
    }

    private boolean matchesTarget(LfaSignalRequest request, TradingStateProjection.MarketDataState state) {
        return equalsIgnoreCase(request.provider(), state.provider())
                && equalsIgnoreCase(request.environment(), state.environment())
                && equalsIgnoreCase(request.market(), state.market())
                && state.symbol() != null
                && !state.symbol().isBlank();
    }

    private Optional<Long> marketDataAgeMillis(
            LfaSignalRequest request,
            TradingStateProjection.MarketDataState state,
            Instant emittedAt
    ) {
        Instant observedAt = state.topOfBookUpdatedAt() == null ? state.updatedAt() : state.topOfBookUpdatedAt();
        if (observedAt == null) {
            return Optional.empty();
        }
        long ageMillis = Duration.between(observedAt, emittedAt).toMillis();
        if (ageMillis < 0 || ageMillis > request.maxMarketDataAgeMillis()) {
            return Optional.empty();
        }
        return Optional.of(ageMillis);
    }

    private Optional<TopOfBook> topOfBook(TradingStateProjection.MarketDataState state) {
        BigDecimal bidPrice = decimal(state.bestBidPrice());
        BigDecimal bidQuantity = decimal(state.bestBidQuantity());
        BigDecimal askPrice = decimal(state.bestAskPrice());
        BigDecimal askQuantity = decimal(state.bestAskQuantity());
        if (!positive(bidPrice) || !positive(bidQuantity) || !positive(askPrice) || !positive(askQuantity)) {
            return Optional.empty();
        }
        if (askPrice.compareTo(bidPrice) < 0) {
            return Optional.empty();
        }
        return Optional.of(new TopOfBook(bidPrice, bidQuantity, askPrice, askQuantity));
    }

    private Optional<BigDecimal> spreadBps(TopOfBook topOfBook) {
        BigDecimal mid = topOfBook.bidPrice().add(topOfBook.askPrice()).divide(TWO, 18, RoundingMode.HALF_UP);
        if (!positive(mid)) {
            return Optional.empty();
        }
        return Optional.of(topOfBook.askPrice().subtract(topOfBook.bidPrice())
                .multiply(TEN_THOUSAND)
                .divide(mid, 8, RoundingMode.HALF_UP));
    }

    private Optional<StrategySignalType> signalType(
            LfaSignalRequest request,
            BigDecimal bidNotional,
            BigDecimal askNotional
    ) {
        BigDecimal minRatio = request.minImbalanceRatio();
        if (bidNotional.compareTo(askNotional.multiply(minRatio)) >= 0) {
            return Optional.of(StrategySignalType.ENTER_LONG);
        }
        if (askNotional.compareTo(bidNotional.multiply(minRatio)) >= 0) {
            return Optional.of(StrategySignalType.ENTER_SHORT);
        }
        return Optional.empty();
    }

    private String limitPrice(StrategySignalType signalType, TopOfBook topOfBook) {
        return switch (signalType) {
            case ENTER_LONG -> topOfBook.askPrice().toPlainString();
            case ENTER_SHORT -> topOfBook.bidPrice().toPlainString();
            default -> throw new IllegalArgumentException("Unsupported LFA entry signal type: " + signalType);
        };
    }

    private double confidence(
            LfaSignalRequest request,
            BigDecimal spreadBps,
            BigDecimal effectiveDepth,
            BigDecimal imbalanceRatio
    ) {
        double imbalanceScore = Math.min(1.0D, imbalanceRatio.subtract(request.minImbalanceRatio()).doubleValue());
        double spreadScore = Math.max(0.0D, 1.0D - spreadBps.divide(request.maxSpreadBps(), 8, RoundingMode.HALF_UP).doubleValue());
        double depthScore = Math.min(1.0D, effectiveDepth.divide(request.minTopOfBookQuoteNotional(), 8, RoundingMode.HALF_UP).doubleValue() - 1.0D);
        return Math.max(0.0D, Math.min(1.0D, 0.50D + (imbalanceScore * 0.30D) + (spreadScore * 0.10D) + (depthScore * 0.10D)));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal larger(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private BigDecimal smaller(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private long epochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000), instant.getNano() / 1_000);
    }

    private record TopOfBook(
            BigDecimal bidPrice,
            BigDecimal bidQuantity,
            BigDecimal askPrice,
            BigDecimal askQuantity
    ) {
    }
}
