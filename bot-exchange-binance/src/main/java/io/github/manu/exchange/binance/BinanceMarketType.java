package io.github.manu.exchange.binance;

public enum BinanceMarketType {
    SPOT(
            "/api/v3/exchangeInfo",
            null,
            false
    ),
    MARGIN_CROSS(
            "/api/v3/exchangeInfo",
            "/sapi/v1/userListenToken",
            false
    ),
    MARGIN_ISOLATED(
            "/api/v3/exchangeInfo",
            "/sapi/v1/userListenToken",
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
    private final String userDataStartPath;
    private final boolean futures;

    BinanceMarketType(String exchangeInfoPath, String userDataStartPath, boolean futures) {
        this.exchangeInfoPath = exchangeInfoPath;
        this.userDataStartPath = userDataStartPath;
        this.futures = futures;
    }

    public String exchangeInfoPath() {
        return exchangeInfoPath;
    }

    public String userDataStartPath() {
        return userDataStartPath;
    }

    public boolean futures() {
        return futures;
    }

    public static BinanceMarketType fromConfigValue(String value) {
        return BinanceMarketType.valueOf(value.toUpperCase());
    }
}
