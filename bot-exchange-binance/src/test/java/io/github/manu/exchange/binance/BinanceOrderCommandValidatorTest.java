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
    void accepts_options_limit_post_only_order() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .symbol("BTC-240628-70000-C")
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("1")
                .price("100")
                .postOnly(true)
                .build(), BinanceMarketType.OPTIONS);
    }

    @Test
    void rejects_post_only_when_market_does_not_support_it() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .postOnly(true)
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postOnly is not supported");
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
    void accepts_futures_conditional_order_with_trigger_controls() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("STOP_MARKET")
                .stopPrice("45000")
                .quantity("0.001")
                .workingType("MARK_PRICE")
                .priceProtect(true)
                .build(), BinanceMarketType.FUTURES_USD_M);
    }

    @Test
    void rejects_futures_trigger_controls_on_plain_limit_order() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .workingType("MARK_PRICE")
                .priceProtect(false)
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingType is not supported for order type LIMIT")
                .hasMessageContaining("priceProtect is not supported for order type LIMIT");
    }

    @Test
    void rejects_futures_unknown_working_type() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("STOP_MARKET")
                .stopPrice("45000")
                .quantity("0.001")
                .workingType("LAST_PRICE")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingType must be one of");
    }

    @Test
    void rejects_futures_trigger_controls_on_spot() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .workingType("MARK_PRICE")
                .priceProtect(true)
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingType is not supported")
                .hasMessageContaining("priceProtect is not supported");
    }

    @Test
    void rejects_futures_self_trade_prevention_none() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .selfTradePreventionMode("NONE")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selfTradePreventionMode must be one of");
    }

    @Test
    void rejects_futures_price_match_with_explicit_price() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .priceMatch("QUEUE")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceMatch cannot be used with price");
    }

    @Test
    void accepts_futures_limit_order_with_price_match_instead_of_price() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .priceMatch("QUEUE")
                .build(), BinanceMarketType.FUTURES_USD_M);
    }

    @Test
    void rejects_spot_price_match_as_price_substitute() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .priceMatch("QUEUE")
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceMatch is not supported")
                .hasMessageContaining("LIMIT orders require price or pegPriceType");
    }

    @Test
    void accepts_spot_pegged_limit_order_without_price() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .pegPriceType("PRIMARY_PEG")
                .build(), BinanceMarketType.SPOT);
    }

    @Test
    void accepts_spot_pegged_limit_maker_order_without_price() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT_MAKER")
                .quantity("0.001")
                .pegPriceType("MARKET_PEG")
                .pegOffsetType("PRICE_LEVEL")
                .pegOffsetValue(5)
                .build(), BinanceMarketType.SPOT);
    }

    @Test
    void rejects_pegged_order_on_unsupported_market() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .pegPriceType("PRIMARY_PEG")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pegged orders are not supported");
    }

    @Test
    void rejects_peg_offset_without_matching_offset_field() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .pegPriceType("PRIMARY_PEG")
                .pegOffsetValue(5)
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pegOffsetType and pegOffsetValue must be provided together");
    }

    @Test
    void rejects_peg_offset_above_documented_limit() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .pegPriceType("PRIMARY_PEG")
                .pegOffsetType("PRICE_LEVEL")
                .pegOffsetValue(101)
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pegOffsetValue must be less than or equal to 100");
    }

    @Test
    void rejects_peg_fields_on_market_order() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quantity("0.001")
                .pegPriceType("PRIMARY_PEG")
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pegged orders are not supported for order type MARKET");
    }

    @Test
    void accepts_cross_margin_order_with_side_effect_type() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quantity("0.001")
                .sideEffectType("AUTO_BORROW_REPAY")
                .autoRepayAtCancel(false)
                .build(), BinanceMarketType.MARGIN_CROSS);
    }

    @Test
    void accepts_isolated_margin_order_with_side_effect_type() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .sideEffectType("MARGIN_BUY")
                .autoRepayAtCancel(true)
                .isolatedMargin(true)
                .build(), BinanceMarketType.MARGIN_ISOLATED);
    }

    @Test
    void rejects_margin_side_effect_type_on_spot() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quantity("0.001")
                .sideEffectType("AUTO_BORROW_REPAY")
                .build(), BinanceMarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sideEffectType is not supported");
    }

    @Test
    void rejects_auto_repay_at_cancel_without_borrowing_side_effect_type() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("MARKET")
                .quantity("0.001")
                .sideEffectType("AUTO_REPAY")
                .autoRepayAtCancel(false)
                .build(), BinanceMarketType.MARGIN_CROSS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autoRepayAtCancel requires a configured borrow sideEffectType");
    }

    @Test
    void accepts_futures_gtd_limit_order_with_good_till_date() {
        BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTD")
                .quantity("0.001")
                .price("50000")
                .goodTillDate(1_771_111_111_000L)
                .build(), BinanceMarketType.FUTURES_USD_M);
    }

    @Test
    void rejects_futures_gtd_order_without_good_till_date() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTD")
                .quantity("0.001")
                .price("50000")
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GTD orders require goodTillDate");
    }

    @Test
    void rejects_good_till_date_without_gtd() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .goodTillDate(1_771_111_111_000L)
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goodTillDate requires timeInForce GTD");
    }

    @Test
    void rejects_reduce_only_in_hedge_mode_order() {
        assertThatThrownBy(() -> BinanceOrderCommandValidator.validate(commandBuilder()
                .type("LIMIT")
                .timeInForce("GTC")
                .quantity("0.001")
                .price("50000")
                .positionSide("LONG")
                .reduceOnly(true)
                .build(), BinanceMarketType.FUTURES_USD_M))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reduceOnly is not supported in hedge-mode orders");
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
                .hasMessageContaining("LIMIT orders require price or pegPriceType")
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
        private String sideEffectType;
        private String priceMatch;
        private String workingType;
        private String pegPriceType;
        private String pegOffsetType;
        private Integer pegOffsetValue;
        private Long goodTillDate;
        private BigDecimal quantity;
        private BigDecimal quoteOrderQty;
        private BigDecimal price;
        private BigDecimal stopPrice;
        private Boolean reduceOnly;
        private Boolean closePosition;
        private Boolean priceProtect;
        private Boolean autoRepayAtCancel;
        private Boolean isolatedMargin;
        private Boolean marketMakerProtection;
        private Boolean postOnly;

        CommandBuilder symbol(String value) {
            symbol = value;
            return this;
        }

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

        CommandBuilder priceMatch(String value) {
            priceMatch = value;
            return this;
        }

        CommandBuilder sideEffectType(String value) {
            sideEffectType = value;
            return this;
        }

        CommandBuilder workingType(String value) {
            workingType = value;
            return this;
        }

        CommandBuilder pegPriceType(String value) {
            pegPriceType = value;
            return this;
        }

        CommandBuilder pegOffsetType(String value) {
            pegOffsetType = value;
            return this;
        }

        CommandBuilder pegOffsetValue(int value) {
            pegOffsetValue = value;
            return this;
        }

        CommandBuilder goodTillDate(long value) {
            goodTillDate = value;
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

        CommandBuilder priceProtect(boolean value) {
            priceProtect = value;
            return this;
        }

        CommandBuilder autoRepayAtCancel(boolean value) {
            autoRepayAtCancel = value;
            return this;
        }

        CommandBuilder isolatedMargin(boolean value) {
            isolatedMargin = value;
            return this;
        }

        CommandBuilder marketMakerProtection(boolean value) {
            marketMakerProtection = value;
            return this;
        }

        CommandBuilder postOnly(boolean value) {
            postOnly = value;
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
                    sideEffectType,
                    priceMatch,
                    workingType,
                    pegPriceType,
                    pegOffsetType,
                    pegOffsetValue,
                    null,
                    goodTillDate,
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
                    priceProtect,
                    autoRepayAtCancel,
                    isolatedMargin,
                    marketMakerProtection,
                    postOnly
            );
        }
    }
}
