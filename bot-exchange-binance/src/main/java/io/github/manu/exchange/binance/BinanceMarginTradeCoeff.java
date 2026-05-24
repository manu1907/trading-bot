package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceMarginTradeCoeff(
        BigDecimal normalBar,
        BigDecimal marginCallBar,
        BigDecimal forceLiquidationBar
) {
}
