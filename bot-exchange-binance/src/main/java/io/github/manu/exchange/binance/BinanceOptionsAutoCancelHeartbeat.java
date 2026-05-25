package io.github.manu.exchange.binance;

import java.util.List;

record BinanceOptionsAutoCancelHeartbeat(
        List<String> underlyings
) {
    BinanceOptionsAutoCancelHeartbeat {
        underlyings = underlyings == null ? List.of() : List.copyOf(underlyings);
    }
}
