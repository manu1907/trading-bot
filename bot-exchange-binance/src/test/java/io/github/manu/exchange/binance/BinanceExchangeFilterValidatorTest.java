package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceExchangeFilterValidatorTest {

    private final BinanceExchangeFilterValidator validator = new BinanceExchangeFilterValidator();

    @Test
    void accepts_order_that_satisfies_symbol_filters() {
        validator.validate(command("0.002", "50000.10"), metadata());
    }

    @Test
    void rejects_order_below_minimum_quantity() {
        assertThatThrownBy(() -> validator.validate(command("0.0005", "50000.10"), metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity 0.0005 is below exchangeInfo minimum 0.001");
    }

    @Test
    void rejects_order_with_price_not_aligned_to_tick_size() {
        assertThatThrownBy(() -> validator.validate(command("0.002", "50000.15"), metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price 50000.15 does not align with exchangeInfo step 0.10");
    }

    @Test
    void rejects_order_below_minimum_notional() {
        assertThatThrownBy(() -> validator.validate(command("0.001", "1000.00"), metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notional 1.00000 is below exchangeInfo minimum 5");
    }

    @Test
    void rejects_symbol_that_is_not_trading() {
        BinanceExchangeMetadata haltedMetadata = metadata("BREAK", "BTCUSDT");

        assertThatThrownBy(() -> validator.validate(command("0.002", "50000.10"), haltedMetadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol BTCUSDT is not trading");
    }

    private BinanceOrderCommand command(String quantity, String price) {
        return new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                null,
                "RESULT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "client-1",
                null,
                new BigDecimal(quantity),
                null,
                new BigDecimal(price),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceExchangeMetadata metadata() {
        return metadata("TRADING", "BTCUSDT");
    }

    private BinanceExchangeMetadata metadata(String status, String symbol) {
        return new BinanceExchangeMetadata(
                Instant.parse("2026-05-22T20:00:00Z"),
                "https://fapi.binance.com",
                "UTC",
                List.of(),
                List.of(),
                List.of(new BinanceExchangeMetadata.SymbolInfo(
                        symbol,
                        null,
                        "PERPETUAL",
                        null,
                        null,
                        status,
                        "BTC",
                        "USDT",
                        "USDT",
                        2,
                        3,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("LIMIT", "MARKET"),
                        List.of("GTC", "IOC", "FOK"),
                        List.of(
                                filter("PRICE_FILTER", "0.10", "1000000", "0.10", null, null, null, null, null),
                                filter("LOT_SIZE", null, null, null, "0.001", "100", "0.001", null, null),
                                filter("MIN_NOTIONAL", null, null, null, null, null, null, "5", null)
                        )
                ))
        );
    }

    private BinanceExchangeMetadata.Filter filter(
            String filterType,
            String minPrice,
            String maxPrice,
            String tickSize,
            String minQty,
            String maxQty,
            String stepSize,
            String notional,
            String maxNotional
    ) {
        return new BinanceExchangeMetadata.Filter(
                filterType,
                minPrice,
                maxPrice,
                tickSize,
                minQty,
                maxQty,
                stepSize,
                notional,
                null,
                maxNotional,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
