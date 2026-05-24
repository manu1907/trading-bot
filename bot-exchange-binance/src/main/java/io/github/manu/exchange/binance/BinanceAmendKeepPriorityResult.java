package io.github.manu.exchange.binance;

record BinanceAmendKeepPriorityResult(
        Long transactTime,
        Long executionId,
        BinanceAmendedOrder amendedOrder
) {
}
