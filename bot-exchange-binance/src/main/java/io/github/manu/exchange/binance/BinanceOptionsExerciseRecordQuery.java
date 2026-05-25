package io.github.manu.exchange.binance;

record BinanceOptionsExerciseRecordQuery(
        String symbol,
        Long startTime,
        Long endTime,
        Integer limit
) {
}
