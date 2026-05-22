package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOrderCommandValidatorTest {

    @Test
    void accepts_spot_limit_order_with_required_parameters() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .orderResponseType("FULL")
                .selfTradePreventionMode("NONE")
                .build(), BinanceMarketType.SPOT);
    }

    @Test
    void accepts_spot_market_order_with_quote_quantity() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quoteOrderQty("100")
                .build(), BinanceMarketType.SPOT);
    }

    @Test
    void rejects_futures_market_order_with_quote_quantity() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quoteOrderQty("100")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quoteOrderQty is not supported")
                .hasMessageContaining("MARKET orders require quantity");
    }

    @Test
    void accepts_futures_stop_market_close_position_without_quantity() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("STOP_MARKET")
                .stopPrice("45000")
                .closePosition(true)
                .positionSide("BOTH")
                .build(), BinanceMarketType.FUTURES_USD_M);
    }

    @Test
    void rejects_spot_reduce_only_order() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .reduceOnly(true)
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reduceOnly is not supported");
    }

    @Test
    void rejects_limit_order_missing_price_and_time_in_force() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .quantity("0.001")
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIMIT orders require price")
                .hasMessageContaining("timeInForce is required");
    }

    @Test
    void accepts_options_limit_order_with_market_maker_protection() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("1")
                .price("100")
                .marketMakerProtection(true)
                .build(), BinanceMarketType.OPTIONS);
    }

    @Test
    void rejects_options_market_order() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quantity("1")
                .build(), BinanceMarketType.OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must be one of");
    }

    private CommandBuilder commandBuilder() {
        return new CommandBuilder();
    }

    private static final class CommandBuilder {
        private String symbol = "BTCUSDT";
        private String side = "BUY";
        private String type;
        private String timeInForce;
        private String positionSide;
        private String orderResponseType;
        private String selfTradePreventionMode;
        private BigDecimal quantity;
        private BigDecimal quoteOrderQty;
        private BigDecimal price;
        private BigDecimal stopPrice;
        private Boolean reduceOnly;
        private Boolean closePosition;
        private Boolean marketMakerProtection;

        CommandBuilder type(String value) {
            type = value;
            return this;
        }

        CommandBuilder timeInForce(String value) {
            timeInForce = value;
            return this;
        }

        CommandBuilder positionSide(String value) {
            positionSide = value;
            return this;
        }

        CommandBuilder orderResponseType(String value) {
            orderResponseType = value;
            return this;
        }

        CommandBuilder selfTradePreventionMode(String value) {
            selfTradePreventionMode = value;
            return this;
        }

        CommandBuilder quantity(String value) {
            quantity = new BigDecimal(value);
            return this;
        }

        CommandBuilder quoteOrderQty(String value) {
            quoteOrderQty = new BigDecimal(value);
            return this;
        }

        CommandBuilder price(String value) {
            price = new BigDecimal(value);
            return this;
        }

        CommandBuilder stopPrice(String value) {
            stopPrice = new BigDecimal(value);
            return this;
        }

        CommandBuilder reduceOnly(boolean value) {
            reduceOnly = value;
            return this;
        }

        CommandBuilder closePosition(boolean value) {
            closePosition = value;
            return this;
        }

        CommandBuilder marketMakerProtection(boolean value) {
            marketMakerProtection = value;
            return this;
        }

        BinanceOrderCommand build() {
            return new BinanceOrderCommand(
                    symbol,
                    side,
                    type,
                    timeInForce,
                    positionSide,
                    orderResponseType,
                    selfTradePreventionMode,
                    null,
                    null,
                    quantity,
                    quoteOrderQty,
                    price,
                    stopPrice,
                    null,
                    null,
                    null,
                    null,
                    reduceOnly,
                    closePosition,
                    null,
                    marketMakerProtection
            );
        }
    }
}
