package io.github.manu.exchange.binance;

record BinanceRequestParameter(String name, String value) {

    BinanceRequestParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Request parameter name is required");
        }
        if (value == null) {
            throw new IllegalArgumentException("Request parameter value is required for " + name);
        }
    }

    static BinanceRequestParameter of(String name, Object value) {
        return new BinanceRequestParameter(name, String.valueOf(value));
    }
}
