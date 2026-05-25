package io.github.manu.exchange.binance;

record BinancePreventedMatchesQuery(
        String symbol,
        Long preventedMatchId,
        Long orderId,
        Long fromPreventedMatchId,
        Integer limit,
        Boolean isolatedMargin
) {
}
