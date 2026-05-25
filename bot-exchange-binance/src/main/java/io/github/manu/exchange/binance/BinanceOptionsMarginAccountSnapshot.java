package io.github.manu.exchange.binance;

import java.util.List;

record BinanceOptionsMarginAccountSnapshot(
        List<BinanceOptionsAccountAsset> assets,
        List<BinanceOptionsGreek> greeks,
        Long time,
        Boolean canTrade,
        Boolean canDeposit,
        Boolean canWithdraw,
        Boolean reduceOnly,
        Long tradeGroupId
) {
    BinanceOptionsMarginAccountSnapshot {
        assets = assets == null ? List.of() : List.copyOf(assets);
        greeks = greeks == null ? List.of() : List.copyOf(greeks);
    }
}
