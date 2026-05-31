package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.Optional;

interface BinanceReferencePriceProvider {

    Optional<BigDecimal> weightedAveragePrice(String symbol);
}
