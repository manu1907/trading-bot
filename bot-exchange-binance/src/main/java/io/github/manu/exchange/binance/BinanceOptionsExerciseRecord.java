package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsExerciseRecord(
        String id,
        String currency,
        String symbol,
        BigDecimal exercisePrice,
        BigDecimal quantity,
        BigDecimal amount,
        BigDecimal fee,
        Long createDate,
        Integer priceScale,
        Integer quantityScale,
        String optionSide,
        String positionSide,
        String quoteAsset
) {
}
