package io.github.manu.exchange.binance;

record BinanceCancelReplaceCommand(
        BinanceOrderCommand replacementOrder,
        String cancelReplaceMode,
        String cancelNewClientOrderId,
        String cancelOriginalClientOrderId,
        Long cancelOrderId,
        String cancelRestrictions,
        String orderRateLimitExceededMode
) {
}
