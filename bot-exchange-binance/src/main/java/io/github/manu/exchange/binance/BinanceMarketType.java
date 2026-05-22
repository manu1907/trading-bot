package io.github.manu.exchange.binance;

public enum BinanceMarketType {
    SPOT(
            "/api/v3/exchangeInfo",
            "/api/v3/userDataStream",
            false
    ),
    MARGIN_CROSS(
            "/api/v3/exchangeInfo",
            "/sapi/v1/userDataStream",
            false
    ),
    MARGIN_ISOLATED(
            "/api/v3/exchangeInfo",
            "/sapi/v1/userDataStream/isolated",
            false
    ),
    FUTURES_USD_M(
            "/fapi/v1/exchangeInfo",
            "/fapi/v1/listenKey",
            true
    ),
    FUTURES_COIN_M(
            "/dapi/v1/exchangeInfo",
            "/dapi/v1/listenKey",
            true
    ),
    OPTIONS(
            "/eapi/v1/exchangeInfo",
            "/eapi/v1/listenKey",
            false
    );

    private final String exchangeInfoPath;
    private final String listenKeyPath;
    private final boolean futures;

    BinanceMarketType(String exchangeInfoPath, String listenKeyPath, boolean futures) {
        this.exchangeInfoPath = exchangeInfoPath;
        this.listenKeyPath = listenKeyPath;
        this.futures = futures;
    }

    public String exchangeInfoPath() {
        return exchangeInfoPath;
    }

    public String listenKeyPath() {
        return listenKeyPath;
    }

    public boolean futures() {
        return futures;
    }

    public static BinanceMarketType fromConfigValue(String value) {
        return BinanceMarketType.valueOf(value.toUpperCase());
    }
}
