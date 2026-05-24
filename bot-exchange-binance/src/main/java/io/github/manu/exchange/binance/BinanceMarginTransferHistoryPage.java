package io.github.manu.exchange.binance;

import java.util.List;

record BinanceMarginTransferHistoryPage(
        List<BinanceMarginTransferRecord> rows,
        long total
) {
    BinanceMarginTransferHistoryPage {
        rows = List.copyOf(rows);
    }
}
