package io.github.manu.exchange.binance;

import java.util.List;

record BinanceOptionsCommissionRates(List<BinanceOptionsCommission> commissions) {
    BinanceOptionsCommissionRates {
        commissions = List.copyOf(commissions);
    }
}
