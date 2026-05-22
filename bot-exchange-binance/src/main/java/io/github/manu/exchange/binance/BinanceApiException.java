package io.github.manu.exchange.binance;

public final class BinanceApiException extends RuntimeException {

    private final int httpStatusCode;
    private final Integer exchangeCode;
    private final String exchangeMessage;

    BinanceApiException(int httpStatusCode, Integer exchangeCode, String exchangeMessage) {
        super("Binance API request failed: httpStatusCode=%d, exchangeCode=%s, message=%s"
                .formatted(httpStatusCode, exchangeCode, exchangeMessage));
        this.httpStatusCode = httpStatusCode;
        this.exchangeCode = exchangeCode;
        this.exchangeMessage = exchangeMessage;
    }

    public int httpStatusCode() {
        return httpStatusCode;
    }

    public Integer exchangeCode() {
        return exchangeCode;
    }

    public String exchangeMessage() {
        return exchangeMessage;
    }
}
