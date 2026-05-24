package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinancePreventedMatch(
        String symbol,
        Long preventedMatchId,
        Long takerOrderId,
        String makerSymbol,
        Long makerOrderId,
        Long tradeGroupId,
        String selfTradePreventionMode,
        BigDecimal price,
        BigDecimal makerPreventedQuantity,
        Long transactTime
) {
}
