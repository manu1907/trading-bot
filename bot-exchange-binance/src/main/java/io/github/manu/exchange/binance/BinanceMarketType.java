package io.github.manu.exchange.binance;

public enum BinanceMarketType {
    SPOT(
            "/api/v3/exchangeInfo",
            null
    ),
    FUTURES_USD_M(
            "/fapi/v1/exchangeInfo",
            "/fapi/v1/listenKey"
    );

    private final String exchangeInfoPath;
    private final String listenKeyPath;

    BinanceMarketType(String exchangeInfoPath, String listenKeyPath) {
        this.exchangeInfoPath = exchangeInfoPath;
        this.listenKeyPath = listenKeyPath;
    }

    public String exchangeInfoPath() {
        return exchangeInfoPath;
    }

    public String listenKeyPath() {
        return listenKeyPath;
    }

    public static BinanceMarketType fromConfigValue(String value) {
        return BinanceMarketType.valueOf(value.toUpperCase());
    }
}
