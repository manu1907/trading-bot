package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.List;

record BinanceCrossMarginAccountSnapshot(
        Boolean created,
        Boolean borrowEnabled,
        BigDecimal marginLevel,
        BigDecimal collateralMarginLevel,
        BigDecimal totalAssetOfBtc,
        BigDecimal totalLiabilityOfBtc,
        BigDecimal totalNetAssetOfBtc,
        BigDecimal totalCollateralValueInUsdt,
        BigDecimal totalOpenOrderLossInUsdt,
        Boolean tradeEnabled,
        Boolean transferInEnabled,
        Boolean transferOutEnabled,
        String accountType,
        List<BinanceMarginAssetBalance> userAssets
) {
    BinanceCrossMarginAccountSnapshot {
        userAssets = List.copyOf(userAssets);
    }
}
