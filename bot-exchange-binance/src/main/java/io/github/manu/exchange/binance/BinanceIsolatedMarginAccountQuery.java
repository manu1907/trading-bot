package io.github.manu.exchange.binance;

import java.util.List;

record BinanceIsolatedMarginAccountQuery(
        List<String> symbols
) {
    BinanceIsolatedMarginAccountQuery {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }
}
