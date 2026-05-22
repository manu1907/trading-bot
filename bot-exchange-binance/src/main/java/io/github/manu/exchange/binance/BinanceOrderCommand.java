package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOrderCommand(
        String symbol,
        String side,
        String type,
        String timeInForce,
        String positionSide,
        String orderResponseType,
        String selfTradePreventionMode,
        String priceMatch,
        String clientOrderId,
        BigDecimal quantity,
        BigDecimal quoteOrderQty,
        BigDecimal price,
        BigDecimal stopPrice,
        BigDecimal trailingDelta,
        BigDecimal callbackRate,
        BigDecimal activationPrice,
        BigDecimal icebergQty,
        Boolean reduceOnly,
        Boolean closePosition,
        Boolean isolatedMargin,
        Boolean marketMakerProtection
) {
}
