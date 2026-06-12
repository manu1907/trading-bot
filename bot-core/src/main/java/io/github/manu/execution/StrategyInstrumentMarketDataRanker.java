package io.github.manu.execution;

import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class StrategyInstrumentMarketDataRanker {

    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private final TradingStateProjection projection;
    private final Clock clock;

    StrategyInstrumentMarketDataRanker(TradingStateProjection projection, Clock clock) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<RankedInstrument> rank(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            ExecutionProperties.SignalPlanner.SymbolPolicy policy,
            String provider,
            String environment,
            String market,
            String symbol
    ) {
        if (!universe.requireMarketData()
                && !universe.requireTopOfBook()
                && universe.maxSpreadBps() == null
                && universe.minTopOfBookQuoteNotional() == null
                && (policy == null || (policy.maxSpreadBps() == null && policy.minTopOfBookQuoteNotional() == null))) {
            return Optional.of(new RankedInstrument(symbol, null, null, null, null, 0.0D));
        }
        Optional<TradingStateProjection.MarketDataState> state =
                projection.marketData(provider, environment, market, symbol);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        TradingStateProjection.MarketDataState marketData = state.get();
        Instant referenceTime = universe.requireTopOfBook()
                ? marketData.topOfBookUpdatedAt()
                : marketData.updatedAt();
        if (referenceTime == null) {
            return Optional.empty();
        }
        Long maxAgeMillis = universe.maxMarketDataAgeMillis();
        if (maxAgeMillis != null && Duration.between(referenceTime, Instant.now(clock)).toMillis() > maxAgeMillis) {
            return Optional.empty();
        }
        if (universe.requireTopOfBook() && !marketData.hasTopOfBook()) {
            return Optional.empty();
        }
        BigDecimal spreadBps = spreadBps(marketData).orElse(null);
        BigDecimal maxSpreadBps = maxSpreadBps(universe, policy);
        if (maxSpreadBps != null) {
            if (spreadBps == null || spreadBps.compareTo(maxSpreadBps) > 0) {
                return Optional.empty();
            }
        }
        BigDecimal minTopOfBookQuoteNotional = minTopOfBookQuoteNotional(universe, policy);
        BigDecimal topOfBookQuoteNotional = topOfBookQuoteNotional(marketData).orElse(null);
        if (minTopOfBookQuoteNotional != null) {
            if (topOfBookQuoteNotional == null || topOfBookQuoteNotional.compareTo(minTopOfBookQuoteNotional) < 0) {
                return Optional.empty();
            }
        }
        Long ageMillis = Duration.between(referenceTime, Instant.now(clock)).toMillis();
        return Optional.of(new RankedInstrument(
                symbol,
                marketData,
                spreadBps,
                topOfBookQuoteNotional,
                ageMillis,
                score(spreadBps, topOfBookQuoteNotional, minTopOfBookQuoteNotional, ageMillis)
        ));
    }

    private Optional<BigDecimal> spreadBps(TradingStateProjection.MarketDataState marketData) {
        BigDecimal bid = decimal(marketData.bestBidPrice());
        BigDecimal ask = decimal(marketData.bestAskPrice());
        if (bid == null || ask == null || bid.compareTo(BigDecimal.ZERO) <= 0 || ask.compareTo(bid) < 0) {
            return Optional.empty();
        }
        BigDecimal mid = bid.add(ask).divide(new BigDecimal("2"), 18, RoundingMode.HALF_UP);
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(ask.subtract(bid).multiply(TEN_THOUSAND).divide(mid, 8, RoundingMode.HALF_UP));
    }

    private Optional<BigDecimal> topOfBookQuoteNotional(TradingStateProjection.MarketDataState marketData) {
        BigDecimal bidPrice = decimal(marketData.bestBidPrice());
        BigDecimal bidQuantity = decimal(marketData.bestBidQuantity());
        BigDecimal askPrice = decimal(marketData.bestAskPrice());
        BigDecimal askQuantity = decimal(marketData.bestAskQuantity());
        if (!positive(bidPrice) || !positive(bidQuantity) || !positive(askPrice) || !positive(askQuantity)) {
            return Optional.empty();
        }
        BigDecimal bidNotional = bidPrice.multiply(bidQuantity);
        BigDecimal askNotional = askPrice.multiply(askQuantity);
        return Optional.of(bidNotional.min(askNotional));
    }

    private BigDecimal maxSpreadBps(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            ExecutionProperties.SignalPlanner.SymbolPolicy policy
    ) {
        String configured = policy == null || policy.maxSpreadBps() == null ? universe.maxSpreadBps() : policy.maxSpreadBps();
        return decimal(configured);
    }

    private BigDecimal minTopOfBookQuoteNotional(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            ExecutionProperties.SignalPlanner.SymbolPolicy policy
    ) {
        String configured = policy == null || policy.minTopOfBookQuoteNotional() == null
                ? universe.minTopOfBookQuoteNotional()
                : policy.minTopOfBookQuoteNotional();
        return decimal(configured);
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

    private double score(
            BigDecimal spreadBps,
            BigDecimal topOfBookQuoteNotional,
            BigDecimal minTopOfBookQuoteNotional,
            Long ageMillis
    ) {
        double spreadPenalty = spreadBps == null ? 0.0D : spreadBps.doubleValue();
        double agePenalty = ageMillis == null ? 0.0D : ageMillis.doubleValue() / 1000.0D;
        double liquidityPenalty = liquidityPenalty(topOfBookQuoteNotional, minTopOfBookQuoteNotional);
        return 1.0D / (1.0D + spreadPenalty + liquidityPenalty + agePenalty);
    }

    private double liquidityPenalty(BigDecimal topOfBookQuoteNotional, BigDecimal minTopOfBookQuoteNotional) {
        if (topOfBookQuoteNotional == null || minTopOfBookQuoteNotional == null) {
            return 0.0D;
        }
        if (topOfBookQuoteNotional.compareTo(BigDecimal.ZERO) <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return minTopOfBookQuoteNotional
                .divide(topOfBookQuoteNotional, 8, RoundingMode.HALF_UP)
                .doubleValue();
    }

    record RankedInstrument(
            String symbol,
            TradingStateProjection.MarketDataState marketData,
            BigDecimal spreadBps,
            BigDecimal topOfBookQuoteNotional,
            Long ageMillis,
            double score
    ) {
    }
}
