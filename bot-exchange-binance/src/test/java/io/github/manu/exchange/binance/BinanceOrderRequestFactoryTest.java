package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOrderRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_validated_new_order_request_from_command() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                null,
                null,
                null,
                null,
                null,
                "tb_smoke_1",
                null,
                new BigDecimal("0.001000"),
                null,
                new BigDecimal("50000.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&newClientOrderId=tb_smoke_1"
                        + "&quantity=0.001&price=50000"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_gtd_price_match_order_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTD",
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                "QUEUE",
                null,
                null,
                null,
                null,
                "tb_gtd_1",
                1_771_111_111_000L,
                new BigDecimal("0.001000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTD&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&priceMatch=QUEUE"
                        + "&newClientOrderId=tb_gtd_1&goodTillDate=1771111111000&quantity=0.001"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_futures_conditional_order_with_trigger_controls() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "SELL",
                "STOP_MARKET",
                null,
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                null,
                "MARK_PRICE",
                null,
                null,
                null,
                "tb_stop_1",
                null,
                new BigDecimal("0.001000"),
                null,
                null,
                new BigDecimal("45000.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=SELL&type=STOP_MARKET&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&workingType=MARK_PRICE"
                        + "&newClientOrderId=tb_stop_1&quantity=0.001&stopPrice=45000&priceProtect=false"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_spot_pegged_limit_order_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                null,
                "FULL",
                "NONE",
                null,
                null,
                null,
                "PRIMARY_PEG",
                "PRICE_LEVEL",
                5,
                "tb_peg_1",
                null,
                new BigDecimal("0.001000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC"
                        + "&newOrderRespType=FULL&selfTradePreventionMode=NONE"
                        + "&pegPriceType=PRIMARY_PEG&pegOffsetType=PRICE_LEVEL&pegOffsetValue=5"
                        + "&newClientOrderId=tb_peg_1&quantity=0.001"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/order?");
    }

    @Test
    void builds_margin_order_with_side_effect_controls() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(marginBinance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "MARKET",
                null,
                null,
                "FULL",
                "NONE",
                "AUTO_BORROW_REPAY",
                null,
                null,
                null,
                null,
                null,
                "tb_margin_1",
                null,
                new BigDecimal("0.001000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=MARKET&newOrderRespType=FULL"
                        + "&selfTradePreventionMode=NONE&sideEffectType=AUTO_BORROW_REPAY"
                        + "&newClientOrderId=tb_margin_1&quantity=0.001"
                        + "&autoRepayAtCancel=false&isIsolated=true"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/order?");
    }

    @Test
    void builds_cancel_order_request_by_original_client_order_id() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.cancelOrder("BTCUSDT", "tb_smoke_1", "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&origClientOrderId=tb_smoke_1&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_query_order_request_by_original_client_order_id() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.queryOrder("BTCUSDT", "tb_smoke_1", "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&origClientOrderId=tb_smoke_1&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_open_orders_request_with_optional_symbol() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.openOrders("BTCUSDT", "test-secret");

        assertThat(request.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/openOrders?");
    }

    @Test
    void builds_futures_cancel_all_and_countdown_cancel_all_requests() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest cancelAll = factory.cancelAllOpenOrders("BTCUSDT", "test-secret");
        BinanceSignedRequest countdown = factory.countdownCancelAll("BTCUSDT", 120_000L, "test-secret");

        assertThat(cancelAll.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(cancelAll.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/allOpenOrders?");
        assertThat(countdown.payload())
                .isEqualTo("symbol=BTCUSDT&countdownTime=120000&timestamp=1499827319559&recvWindow=5000");
        assertThat(countdown.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/countdownCancelAll?");
    }

    @Test
    void builds_futures_cancel_multiple_orders_requests() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest byOrderIds = factory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(1234567L, 2345678L), List.of()),
                "test-secret"
        );
        BinanceSignedRequest byClientOrderIds = factory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(), List.of("my_id_1", "my_id_2")),
                "test-secret"
        );

        assertThat(byOrderIds.payload())
                .isEqualTo("symbol=BTCUSDT&orderIdList=%5B1234567%2C2345678%5D&timestamp=1499827319559&recvWindow=5000");
        assertThat(byOrderIds.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/batchOrders?");
        assertThat(byClientOrderIds.payload())
                .isEqualTo("symbol=BTCUSDT&origClientOrderIdList=%5B%22my_id_1%22%2C%22my_id_2%22%5D"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byClientOrderIds.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/batchOrders?");
    }

    @Test
    void builds_futures_batch_orders_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.batchOrders(List.of(
                limitCommand("BTCUSDT", "BUY", "batch_1", "50000.00", "0.001000"),
                limitCommand("BTCUSDT", "SELL", "batch_2", "51000.00", "0.002000")
        ), "test-secret");

        assertThat(request.payload())
                .isEqualTo("batchOrders=%5B%7B%22symbol%22%3A%22BTCUSDT%22%2C%22side%22%3A%22BUY%22"
                        + "%2C%22type%22%3A%22LIMIT%22%2C%22timeInForce%22%3A%22GTC%22"
                        + "%2C%22positionSide%22%3A%22BOTH%22%2C%22newOrderRespType%22%3A%22RESULT%22"
                        + "%2C%22selfTradePreventionMode%22%3A%22EXPIRE_MAKER%22"
                        + "%2C%22newClientOrderId%22%3A%22batch_1%22%2C%22quantity%22%3A%220.001%22"
                        + "%2C%22price%22%3A%2250000%22%7D%2C%7B%22symbol%22%3A%22BTCUSDT%22"
                        + "%2C%22side%22%3A%22SELL%22%2C%22type%22%3A%22LIMIT%22"
                        + "%2C%22timeInForce%22%3A%22GTC%22%2C%22positionSide%22%3A%22BOTH%22"
                        + "%2C%22newOrderRespType%22%3A%22RESULT%22"
                        + "%2C%22selfTradePreventionMode%22%3A%22EXPIRE_MAKER%22"
                        + "%2C%22newClientOrderId%22%3A%22batch_2%22%2C%22quantity%22%3A%220.002%22"
                        + "%2C%22price%22%3A%2251000%22%7D%5D"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/batchOrders?");
    }

    @Test
    void builds_futures_modify_order_requests() {
        BinanceOrderRequestFactory usdmFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory coinmFactory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest byOrderId = usdmFactory.modifyOrder(
                new BinanceModifyOrderCommand(
                        "BTCUSDT",
                        12345L,
                        null,
                        "BUY",
                        new BigDecimal("0.001000"),
                        new BigDecimal("50000.00"),
                        null
                ),
                "test-secret"
        );
        BinanceSignedRequest byClientOrderId = coinmFactory.modifyOrder(
                new BinanceModifyOrderCommand("BTCUSD_PERP", null, "client_1", "SELL", new BigDecimal("1"), null, "QUEUE"),
                "test-secret"
        );

        assertThat(byOrderId.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=12345&side=BUY&quantity=0.001&price=50000"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byOrderId.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
        assertThat(byClientOrderId.payload())
                .isEqualTo("symbol=BTCUSD_PERP&origClientOrderId=client_1&side=SELL&quantity=1&priceMatch=QUEUE"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byClientOrderId.uri().toString()).startsWith("https://demo-dapi.binance.com/dapi/v1/order?");
    }

    @Test
    void builds_futures_modify_multiple_orders_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.modifyMultipleOrders(List.of(
                new BinanceModifyOrderCommand(
                        "BTCUSDT",
                        12345L,
                        null,
                        "BUY",
                        new BigDecimal("0.001000"),
                        new BigDecimal("50000.00"),
                        null
                ),
                new BinanceModifyOrderCommand(
                        "BTCUSDT",
                        null,
                        "client_2",
                        "SELL",
                        new BigDecimal("0.002000"),
                        null,
                        "QUEUE"
                )
        ), "test-secret");

        assertThat(request.payload())
                .isEqualTo("batchOrders=%5B%7B%22symbol%22%3A%22BTCUSDT%22%2C%22orderId%22%3A%2212345%22"
                        + "%2C%22side%22%3A%22BUY%22%2C%22quantity%22%3A%220.001%22"
                        + "%2C%22price%22%3A%2250000%22%7D%2C%7B%22symbol%22%3A%22BTCUSDT%22"
                        + "%2C%22origClientOrderId%22%3A%22client_2%22%2C%22side%22%3A%22SELL%22"
                        + "%2C%22quantity%22%3A%220.002%22%2C%22priceMatch%22%3A%22QUEUE%22%7D%5D"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/batchOrders?");
    }

    @Test
    void builds_futures_modify_order_history_request() {
        BinanceOrderRequestFactory usdmFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory coinmFactory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest byOrderId = usdmFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", 12345L, null, 1_700_000_000_000L, 1_700_001_000_000L, 100),
                "test-secret"
        );
        BinanceSignedRequest byClientOrderId = coinmFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSD_PERP", null, "client_1", null, null, 50),
                "test-secret"
        );

        assertThat(byOrderId.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=12345&startTime=1700000000000&endTime=1700001000000&limit=100"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byOrderId.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/orderAmendment?");
        assertThat(byClientOrderId.payload())
                .isEqualTo("symbol=BTCUSD_PERP&origClientOrderId=client_1&limit=50"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byClientOrderId.uri().toString()).startsWith("https://demo-dapi.binance.com/dapi/v1/orderAmendment?");
    }

    @Test
    void rejects_invalid_futures_cancel_all_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.cancelAllOpenOrders(" ", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> futuresFactory.countdownCancelAll("BTCUSDT", -1L, "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countdownTime must be zero or positive");
        assertThatThrownBy(() -> spotFactory.cancelAllOpenOrders("BTCUSDT", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelAllOpenOrdersPath is not configured");
    }

    @Test
    void rejects_invalid_futures_cancel_multiple_orders_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(), List.of()),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of orderIds or originalClientOrderIds is required");
        assertThatThrownBy(() -> futuresFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(1L), List.of("client-1")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of orderIds or originalClientOrderIds is required");
        assertThatThrownBy(() -> futuresFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), List.of()),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 10 values");
        assertThatThrownBy(() -> futuresFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(0L), List.of()),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderIds must be positive");
        assertThatThrownBy(() -> futuresFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(), List.of(" ")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalClientOrderIds must not contain blank values");
        assertThatThrownBy(() -> spotFactory.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(1L), List.of()),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelMultipleOrdersPath is not configured");
    }

    @Test
    void rejects_invalid_futures_batch_orders_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.batchOrders(List.of(), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one order");
        assertThatThrownBy(() -> futuresFactory.batchOrders(List.of(
                limitCommand("BTCUSDT", "BUY", "batch_1", "50000", "0.001"),
                limitCommand("BTCUSDT", "BUY", "batch_2", "50000", "0.001"),
                limitCommand("BTCUSDT", "BUY", "batch_3", "50000", "0.001"),
                limitCommand("BTCUSDT", "BUY", "batch_4", "50000", "0.001"),
                limitCommand("BTCUSDT", "BUY", "batch_5", "50000", "0.001"),
                limitCommand("BTCUSDT", "BUY", "batch_6", "50000", "0.001")
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 5 orders");
        assertThatThrownBy(() -> futuresFactory.batchOrders(List.of(
                limitCommand("BTCUSDT", "BUY", "batch_1", null, "0.001")
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIMIT orders require price");
        assertThatThrownBy(() -> spotFactory.batchOrders(List.of(
                limitCommand("BTCUSDT", "BUY", "batch_1", "50000", "0.001")
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchOrdersPath is not configured");
    }

    @Test
    void rejects_invalid_futures_modify_order_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.modifyOrder(
                new BinanceModifyOrderCommand("BTCUSDT", null, null, "BUY", new BigDecimal("0.001"), new BigDecimal("50000"), null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId or origClientOrderId is required");
        assertThatThrownBy(() -> futuresFactory.modifyOrder(
                new BinanceModifyOrderCommand("BTCUSDT", 123L, null, "BUY", null, new BigDecimal("50000"), null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity is required");
        assertThatThrownBy(() -> futuresFactory.modifyOrder(
                new BinanceModifyOrderCommand("BTCUSDT", 123L, null, "BUY", new BigDecimal("0.001"), null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price or priceMatch is required");
        assertThatThrownBy(() -> futuresFactory.modifyOrder(
                new BinanceModifyOrderCommand(
                        "BTCUSDT",
                        123L,
                        null,
                        "BUY",
                        new BigDecimal("0.001"),
                        new BigDecimal("50000"),
                        "QUEUE"
                ),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceMatch cannot be used with price");
        assertThatThrownBy(() -> spotFactory.modifyOrder(
                new BinanceModifyOrderCommand("BTCUSDT", 123L, null, "BUY", new BigDecimal("0.001"), new BigDecimal("50000"), null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modifyOrderPath is not configured");
    }

    @Test
    void rejects_invalid_futures_modify_multiple_orders_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.modifyMultipleOrders(List.of(), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one order");
        assertThatThrownBy(() -> futuresFactory.modifyMultipleOrders(List.of(
                modifyCommand(1L),
                modifyCommand(2L),
                modifyCommand(3L),
                modifyCommand(4L),
                modifyCommand(5L),
                modifyCommand(6L)
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 5 orders");
        assertThatThrownBy(() -> futuresFactory.modifyMultipleOrders(List.of(
                new BinanceModifyOrderCommand("BTCUSDT", null, null, "BUY", new BigDecimal("0.001"), new BigDecimal("50000"), null)
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId or origClientOrderId is required");
        assertThatThrownBy(() -> spotFactory.modifyMultipleOrders(List.of(modifyCommand(1L)), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modifyMultipleOrdersPath is not configured");
    }

    @Test
    void rejects_invalid_futures_modify_order_history_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId or origClientOrderId is required");
        assertThatThrownBy(() -> futuresFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", 1L, null, null, null, 101),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be less than or equal to 100");
        assertThatThrownBy(() -> futuresFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", 0L, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId must be positive");
        assertThatThrownBy(() -> spotFactory.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", 1L, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modifyOrderHistoryPath is not configured");
    }

    @Test
    void builds_all_orders_history_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.allOrders(
                new BinanceOrderHistoryQuery("BTCUSDT", null, 12_345L, 1_700_000_000_000L, 1_700_001_000_000L, 100, null),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=12345&startTime=1700000000000&endTime=1700001000000"
                        + "&limit=100&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/allOrders?");
    }

    @Test
    void builds_margin_account_trade_history_request_with_isolated_flag() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(marginBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.accountTrades(
                new BinanceTradeHistoryQuery("BTCUSDT", null, null, null, null, 98L, 500, true),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&fromId=98&limit=500&isIsolated=TRUE"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/myTrades?");
    }

    @Test
    void builds_spot_commission_rates_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.commissionRates("BTCUSDT", "test-secret");

        assertThat(request.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/account/commission?");
    }

    @Test
    void rejects_invalid_spot_commission_rates_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.commissionRates(" ", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> futuresFactory.commissionRates("BTCUSDT", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commissionRatesPath is not configured");
    }

    @Test
    void builds_spot_prevented_matches_requests() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest byPreventedMatchId = factory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", 1L, null, null, null),
                "test-secret"
        );
        BinanceSignedRequest byOrderId = factory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", null, 5L, 10L, 100),
                "test-secret"
        );

        assertThat(byPreventedMatchId.payload())
                .isEqualTo("symbol=BTCUSDT&preventedMatchId=1&timestamp=1499827319559&recvWindow=5000");
        assertThat(byPreventedMatchId.uri().toString()).startsWith("https://api.binance.com/api/v3/myPreventedMatches?");
        assertThat(byOrderId.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=5&fromPreventedMatchId=10&limit=100"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(byOrderId.uri().toString()).startsWith("https://api.binance.com/api/v3/myPreventedMatches?");
    }

    @Test
    void rejects_invalid_spot_prevented_matches_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.preventedMatches(
                new BinancePreventedMatchesQuery(" ", 1L, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> spotFactory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preventedMatchId or orderId is required");
        assertThatThrownBy(() -> spotFactory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", 1L, 2L, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preventedMatchId cannot be combined");
        assertThatThrownBy(() -> spotFactory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", null, 2L, null, 100),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit requires fromPreventedMatchId");
        assertThatThrownBy(() -> spotFactory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", null, 2L, 1L, 1001),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be less than or equal to 1000");
        assertThatThrownBy(() -> futuresFactory.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", 1L, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preventedMatchesPath is not configured");
    }

    @Test
    void builds_spot_amend_keep_priority_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", 33L, null, "amended-33", new BigDecimal("0.500000")),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=33&newClientOrderId=amended-33&newQty=0.5"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/order/amend/keepPriority?");
    }

    @Test
    void rejects_invalid_spot_amend_keep_priority_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand(" ", 33L, null, null, new BigDecimal("0.5")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> spotFactory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", null, null, null, new BigDecimal("0.5")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId or origClientOrderId is required");
        assertThatThrownBy(() -> spotFactory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", 33L, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newQty is required");
        assertThatThrownBy(() -> spotFactory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", 33L, null, null, BigDecimal.ZERO),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newQty must be positive");
        assertThatThrownBy(() -> futuresFactory.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", 33L, null, null, new BigDecimal("0.5")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amendKeepPriorityPath is not configured");
    }

    @Test
    void builds_spot_cancel_replace_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitCommand("BTCUSDT", "BUY", "replace-1", "50000.00", "0.001000"),
                "STOP_ON_FAILURE",
                "cancel-1",
                null,
                33L,
                "ONLY_NEW",
                "DO_NOTHING"
        ), "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&newOrderRespType=FULL"
                        + "&selfTradePreventionMode=NONE&newClientOrderId=replace-1&quantity=0.001&price=50000"
                        + "&cancelReplaceMode=STOP_ON_FAILURE&cancelNewClientOrderId=cancel-1&cancelOrderId=33"
                        + "&cancelRestrictions=ONLY_NEW&orderRateLimitExceededMode=DO_NOTHING"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/order/cancelReplace?");
    }

    @Test
    void rejects_invalid_spot_cancel_replace_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitCommand("BTCUSDT", "BUY", "replace-1", "50000", "0.001"),
                "BAD_MODE",
                null,
                "cancel-1",
                null,
                null,
                null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelReplaceMode must be one of");
        assertThatThrownBy(() -> spotFactory.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitCommand("BTCUSDT", "BUY", "replace-1", "50000", "0.001"),
                "STOP_ON_FAILURE",
                null,
                null,
                null,
                null,
                null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelOrderId or cancelOrigClientOrderId is required");
        assertThatThrownBy(() -> spotFactory.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitCommand("BTCUSDT", "BUY", "replace-1", "50000", "0.001"),
                "STOP_ON_FAILURE",
                null,
                "cancel-1",
                null,
                "BAD_RESTRICTION",
                null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelRestrictions must be one of");
        assertThatThrownBy(() -> futuresFactory.cancelReplace(new BinanceCancelReplaceCommand(
                limitCommand("BTCUSDT", "BUY", "replace-1", "50000", "0.001"),
                "STOP_ON_FAILURE",
                null,
                "cancel-1",
                null,
                null,
                null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelReplacePath is not configured");
    }

    @Test
    void builds_spot_sor_order_requests() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest live = factory.sorOrder(
                spotLimitCommand("BTCUSDT", "BUY", "sor-1", "50000.00", "0.001000"),
                "test-secret"
        );
        BinanceSignedRequest test = factory.sorTestOrder(
                spotLimitCommand("BTCUSDT", "BUY", "sor-1", "50000.00", "0.001000"),
                true,
                "test-secret"
        );

        assertThat(live.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&newOrderRespType=FULL"
                        + "&selfTradePreventionMode=NONE&newClientOrderId=sor-1&quantity=0.001&price=50000"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(live.uri().toString()).startsWith("https://api.binance.com/api/v3/sor/order?");
        assertThat(test.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&newOrderRespType=FULL"
                        + "&selfTradePreventionMode=NONE&newClientOrderId=sor-1&quantity=0.001&price=50000"
                        + "&computeCommissionRates=true&timestamp=1499827319559&recvWindow=5000");
        assertThat(test.uri().toString()).startsWith("https://api.binance.com/api/v3/sor/order/test?");
    }

    @Test
    void rejects_invalid_spot_sor_order_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.sorOrder(
                spotStopLossCommand("BTCUSDT", "SELL", "sor-stop", "0.001"),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOR orders only support LIMIT and MARKET");
        assertThatThrownBy(() -> spotFactory.sorOrder(
                new BinanceOrderCommand(
                        "BTCUSDT", "BUY", "MARKET", null, null, "FULL", "NONE",
                        null, null, null, null, null, null, "sor-market",
                        null, null, new BigDecimal("50"), null, null, null,
                        null, null, null, null, null, null, null, null, null
                ),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity is required for SOR orders");
        assertThatThrownBy(() -> futuresFactory.sorOrder(
                limitCommand("BTCUSDT", "BUY", "sor-1", "50000", "0.001"),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorOrderPath is not configured");
    }

    @Test
    void builds_spot_oco_order_list_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.ocoOrderList(ocoCommand(), "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&listClientOrderId=oco-list-1&side=SELL&quantity=0.01"
                        + "&aboveType=LIMIT_MAKER&aboveClientOrderId=oco-above-1&abovePrice=52000"
                        + "&belowType=STOP_LOSS_LIMIT&belowClientOrderId=oco-below-1&belowPrice=48000"
                        + "&belowStopPrice=48100&belowTimeInForce=GTC&newOrderRespType=RESULT"
                        + "&selfTradePreventionMode=NONE&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/orderList/oco?");
    }

    @Test
    void rejects_invalid_spot_oco_order_list_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.ocoOrderList(new BinanceOcoOrderListCommand(
                "BTCUSDT", "oco-list-1", "SELL", null,
                "LIMIT_MAKER", null, null, new BigDecimal("52000"), null, null, null, null, null, null, null, null,
                "STOP_LOSS_LIMIT", null, null, new BigDecimal("48000"), new BigDecimal("48100"), null, "GTC",
                null, null, null, null, null, "RESULT", "NONE"
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity is required for OCO order lists");
        assertThatThrownBy(() -> spotFactory.ocoOrderList(new BinanceOcoOrderListCommand(
                "BTCUSDT", "oco-list-1", "SELL", new BigDecimal("0.01"),
                "LIMIT_MAKER", null, null, new BigDecimal("52000"), null, null, null, null, null, null, null, null,
                "TAKE_PROFIT", null, null, null, new BigDecimal("53000"), null, null,
                null, null, null, null, null, "RESULT", "NONE"
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one limit/profit leg and one stop-loss leg");
        assertThatThrownBy(() -> spotFactory.ocoOrderList(new BinanceOcoOrderListCommand(
                "BTCUSDT", "oco-list-1", "SELL", new BigDecimal("0.01"),
                "LIMIT_MAKER", null, null, new BigDecimal("52000"), null, null, null, null, null, null, null, null,
                "STOP_LOSS_LIMIT", null, null, new BigDecimal("48000"), null, null, "GTC",
                null, null, null, null, null, "RESULT", "NONE"
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belowStopPrice or belowTrailingDelta is required");
        assertThatThrownBy(() -> futuresFactory.ocoOrderList(ocoCommand(), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderListOcoPath is not configured");
    }

    @Test
    void builds_spot_oto_order_list_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.otoOrderList(otoCommand(), "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&listClientOrderId=oto-list-1&newOrderRespType=RESULT"
                        + "&selfTradePreventionMode=NONE&workingType=LIMIT&workingSide=SELL"
                        + "&workingClientOrderId=oto-working-1&workingPrice=52000&workingQuantity=0.01"
                        + "&workingTimeInForce=GTC&pendingType=STOP_LOSS_LIMIT&pendingSide=BUY"
                        + "&pendingClientOrderId=oto-pending-1&pendingPrice=48000&pendingStopPrice=48100"
                        + "&pendingQuantity=0.01&pendingTimeInForce=GTC"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/orderList/oto?");
    }

    @Test
    void rejects_invalid_spot_oto_order_list_requests() {
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> spotFactory.otoOrderList(new BinanceOtoOrderListCommand(
                "BTCUSDT", "oto-list-1", "RESULT", "NONE",
                "MARKET", "SELL", "oto-working-1", new BigDecimal("52000"), new BigDecimal("0.01"), null, "GTC",
                null, null, null, null, null,
                "STOP_LOSS_LIMIT", "BUY", "oto-pending-1", new BigDecimal("48000"), new BigDecimal("48100"), null,
                new BigDecimal("0.01"), null, "GTC", null, null, null, null, null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingType must be one of");
        assertThatThrownBy(() -> spotFactory.otoOrderList(new BinanceOtoOrderListCommand(
                "BTCUSDT", "oto-list-1", "RESULT", "NONE",
                "LIMIT", "SELL", "oto-working-1", new BigDecimal("52000"), new BigDecimal("0.01"), null, null,
                null, null, null, null, null,
                "STOP_LOSS_LIMIT", "BUY", "oto-pending-1", new BigDecimal("48000"), new BigDecimal("48100"), null,
                new BigDecimal("0.01"), null, "GTC", null, null, null, null, null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workingTimeInForce is required");
        assertThatThrownBy(() -> spotFactory.otoOrderList(new BinanceOtoOrderListCommand(
                "BTCUSDT", "oto-list-1", "RESULT", "NONE",
                "LIMIT", "SELL", "oto-working-1", new BigDecimal("52000"), new BigDecimal("0.01"), null, "GTC",
                null, null, null, null, null,
                "STOP_LOSS_LIMIT", "BUY", "oto-pending-1", new BigDecimal("48000"), null, null,
                new BigDecimal("0.01"), null, "GTC", null, null, null, null, null
        ), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingStopPrice or pendingTrailingDelta is required");
        assertThatThrownBy(() -> futuresFactory.otoOrderList(otoCommand(), "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderListOtoPath is not configured");
    }

    @Test
    void builds_coin_m_history_request_by_pair() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.allOrders(
                new BinanceOrderHistoryQuery(null, "BTCUSD", null, null, null, 50, null),
                "test-secret"
        );

        assertThat(request.payload()).isEqualTo("pair=BTCUSD&limit=50&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-dapi.binance.com/dapi/v1/allOrders?");
    }

    @Test
    void rejects_invalid_history_query_shapes() {
        BinanceOrderRequestFactory usdmFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory coinmFactory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> usdmFactory.allOrders(
                new BinanceOrderHistoryQuery(null, "BTCUSD", null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> coinmFactory.accountTrades(
                new BinanceTradeHistoryQuery(null, "BTCUSD", 123L, null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId requires symbol");
        assertThatThrownBy(() -> coinmFactory.accountTrades(
                new BinanceTradeHistoryQuery(null, "BTCUSD", null, null, null, 123L, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromId cannot be sent with pair");
        assertThatThrownBy(() -> usdmFactory.allOrders(
                new BinanceOrderHistoryQuery("BTCUSDT", null, null, null, null, 0, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        assertThatThrownBy(() -> usdmFactory.accountTrades(
                new BinanceTradeHistoryQuery("BTCUSDT", null, null, null, null, null, 100, true),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isIsolated is only supported");
    }

    private BinanceProperties binance() {
        return new BinanceProperties(
                "FUTURES_USD_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                trading(),
                userData(),
                new BinanceProperties.FuturesAccount(
                        "ONE_WAY",
                        List.of("ONE_WAY", "HEDGE"),
                        "/fapi/v1/positionSide/dual",
                        "/fapi/v1/marginType",
                        "/fapi/v1/leverage",
                        "/fapi/v3/balance",
                        "/fapi/v3/account",
                        "/fapi/v3/positionRisk",
                        "/fapi/v1/adlQuantile",
                        "/fapi/v1/forceOrders",
                        "/fapi/v1/income",
                        "/fapi/v1/fundingRate",
                        "/fapi/v1/multiAssetsMargin",
                        1,
                        125,
                        List.of("CROSSED", "ISOLATED"),
                        false,
                        false
                )
        );
    }

    private BinanceOrderCommand limitCommand(String symbol, String side, String clientOrderId, String price, String quantity) {
        return new BinanceOrderCommand(
                symbol,
                side,
                "LIMIT",
                "GTC",
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                null,
                null,
                null,
                null,
                null,
                clientOrderId,
                null,
                quantity == null ? null : new BigDecimal(quantity),
                null,
                price == null ? null : new BigDecimal(price),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceOrderCommand spotLimitCommand(String symbol, String side, String clientOrderId, String price, String quantity) {
        return new BinanceOrderCommand(
                symbol,
                side,
                "LIMIT",
                "GTC",
                null,
                "FULL",
                "NONE",
                null,
                null,
                null,
                null,
                null,
                null,
                clientOrderId,
                null,
                quantity == null ? null : new BigDecimal(quantity),
                null,
                price == null ? null : new BigDecimal(price),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceOrderCommand spotStopLossCommand(String symbol, String side, String clientOrderId, String quantity) {
        return new BinanceOrderCommand(
                symbol,
                side,
                "STOP_LOSS",
                null,
                null,
                "FULL",
                "NONE",
                null,
                null,
                null,
                null,
                null,
                null,
                clientOrderId,
                null,
                quantity == null ? null : new BigDecimal(quantity),
                null,
                null,
                new BigDecimal("49000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceModifyOrderCommand modifyCommand(long orderId) {
        return new BinanceModifyOrderCommand(
                "BTCUSDT",
                orderId,
                null,
                "BUY",
                new BigDecimal("0.001"),
                new BigDecimal("50000"),
                null
        );
    }

    private BinanceOcoOrderListCommand ocoCommand() {
        return new BinanceOcoOrderListCommand(
                "BTCUSDT",
                "oco-list-1",
                "SELL",
                new BigDecimal("0.010000"),
                "LIMIT_MAKER",
                "oco-above-1",
                null,
                new BigDecimal("52000.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "oco-below-1",
                null,
                new BigDecimal("48000.00"),
                new BigDecimal("48100.00"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "RESULT",
                "NONE"
        );
    }

    private BinanceOtoOrderListCommand otoCommand() {
        return new BinanceOtoOrderListCommand(
                "BTCUSDT",
                "oto-list-1",
                "RESULT",
                "NONE",
                "LIMIT",
                "SELL",
                "oto-working-1",
                new BigDecimal("52000.00"),
                new BigDecimal("0.010000"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "BUY",
                "oto-pending-1",
                new BigDecimal("48000.00"),
                new BigDecimal("48100.00"),
                null,
                new BigDecimal("0.010000"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceProperties spotBinance() {
        return new BinanceProperties(
                "SPOT",
                new BinanceProperties.Credentials(
                        "binance_real_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "TRADE")
                ),
                spotRest(),
                websocket(),
                spotTrading(),
                userData(),
                null
        );
    }

    private BinanceProperties marginBinance() {
        return new BinanceProperties(
                "MARGIN_ISOLATED",
                new BinanceProperties.Credentials(
                        "binance_real_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "MARGIN", "TRADE")
                ),
                spotRest(),
                websocket(),
                marginTrading(),
                userData(),
                null
        );
    }

    private BinanceProperties coinmBinance() {
        return new BinanceProperties(
                "FUTURES_COIN_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                coinmRest(),
                websocket(),
                coinmTrading(),
                userData(),
                new BinanceProperties.FuturesAccount(
                        "ONE_WAY",
                        List.of("ONE_WAY", "HEDGE"),
                        "/dapi/v1/positionSide/dual",
                        "/dapi/v1/marginType",
                        "/dapi/v1/leverage",
                        "/dapi/v1/balance",
                        "/dapi/v1/account",
                        "/dapi/v1/positionRisk",
                        "/dapi/v1/adlQuantile",
                        "/dapi/v1/forceOrders",
                        "/dapi/v1/income",
                        "/dapi/v1/fundingRate",
                        null,
                        1,
                        125,
                        List.of("CROSSED", "ISOLATED"),
                        false,
                        false
                )
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://demo-fapi.binance.com",
                "/fapi/v1/exchangeInfo",
                "/fapi/v1/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                5000,
                2000,
                5000,
                3,
                200,
                List.of(408, 429, 500, 502, 503, 504),
                List.of("X-MBX-USED-WEIGHT"),
                List.of("X-MBX-ORDER-COUNT"),
                "RESULT",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Rest spotRest() {
        return new BinanceProperties.Rest(
                "https://api.binance.com",
                "/api/v3/exchangeInfo",
                "/api/v3/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                5000,
                2000,
                5000,
                3,
                200,
                List.of(408, 429, 500, 502, 503, 504),
                List.of("X-MBX-USED-WEIGHT"),
                List.of("X-MBX-ORDER-COUNT"),
                "FULL",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Rest coinmRest() {
        return new BinanceProperties.Rest(
                "https://demo-dapi.binance.com",
                "/dapi/v1/exchangeInfo",
                "/dapi/v1/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                5000,
                2000,
                5000,
                3,
                200,
                List.of(408, 429, 500, 502, 503, 504),
                List.of("X-MBX-USED-WEIGHT"),
                List.of("X-MBX-ORDER-COUNT"),
                "RESULT",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://fstream.binancefuture.com",
                "/public",
                "/market",
                "/private",
                "/ws",
                "/stream",
                24,
                10,
                null,
                3,
                null,
                10,
                10,
                1024,
                null,
                "MILLISECONDS"
        );
    }

    private BinanceProperties.Trading trading() {
        return new BinanceProperties.Trading(
                "/fapi/v1/order",
                null,
                "/fapi/v1/order",
                "/fapi/v1/order",
                "/fapi/v1/openOrders",
                "/fapi/v1/allOrders",
                "/fapi/v1/userTrades",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "/fapi/v1/batchOrders",
                "/fapi/v1/order",
                "/fapi/v1/batchOrders",
                "/fapi/v1/orderAmendment",
                "/fapi/v1/batchOrders",
                "/fapi/v1/allOpenOrders",
                "/fapi/v1/countdownCancelAll",
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("GTC", "IOC", "FOK", "GTX", "GTD"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
                List.of("LIMIT", "STOP", "TAKE_PROFIT"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("MARK_PRICE", "CONTRACT_PRICE"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private BinanceProperties.Trading spotTrading() {
        return new BinanceProperties.Trading(
                "/api/v3/order",
                "/api/v3/order/test",
                "/api/v3/order",
                "/api/v3/order",
                "/api/v3/openOrders",
                "/api/v3/allOrders",
                "/api/v3/myTrades",
                "/api/v3/account/commission",
                "/api/v3/myPreventedMatches",
                "/api/v3/order/amend/keepPriority",
                "/api/v3/order/cancelReplace",
                "/api/v3/sor/order",
                "/api/v3/sor/order/test",
                "/api/v3/orderList/oco",
                "/api/v3/orderList/oto",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT", "LIMIT_MAKER"),
                List.of("GTC", "IOC", "FOK"),
                List.of("ACK", "RESULT", "FULL"),
                List.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("LIMIT", "LIMIT_MAKER", "STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT"),
                List.of("PRIMARY_PEG", "MARKET_PEG"),
                List.of("PRICE_LEVEL"),
                List.of(),
                List.of(),
                100,
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                false,
                false,
                false
        );
    }

    private BinanceProperties.Trading marginTrading() {
        return new BinanceProperties.Trading(
                "/sapi/v1/margin/order",
                null,
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/openOrders",
                "/sapi/v1/margin/allOrders",
                "/sapi/v1/margin/myTrades",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT", "LIMIT_MAKER"),
                List.of("GTC", "IOC", "FOK"),
                List.of("ACK", "RESULT", "FULL"),
                List.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("NO_SIDE_EFFECT", "MARGIN_BUY", "AUTO_REPAY", "AUTO_BORROW_REPAY"),
                List.of("MARGIN_BUY", "AUTO_BORROW_REPAY"),
                null,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false
        );
    }

    private BinanceProperties.Trading coinmTrading() {
        return new BinanceProperties.Trading(
                "/dapi/v1/order",
                null,
                "/dapi/v1/order",
                "/dapi/v1/order",
                "/dapi/v1/openOrders",
                "/dapi/v1/allOrders",
                "/dapi/v1/userTrades",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "/dapi/v1/batchOrders",
                "/dapi/v1/order",
                "/dapi/v1/batchOrders",
                "/dapi/v1/orderAmendment",
                "/dapi/v1/batchOrders",
                "/dapi/v1/allOpenOrders",
                "/dapi/v1/countdownCancelAll",
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("GTC", "IOC", "FOK", "GTX", "GTD"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
                List.of("LIMIT", "STOP", "TAKE_PROFIT"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("MARK_PRICE", "CONTRACT_PRICE"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private BinanceProperties.UserDataStream userData() {
        return new BinanceProperties.UserDataStream(
                "listen_key",
                "/fapi/v1/listenKey",
                "/fapi/v1/listenKey",
                "/fapi/v1/listenKey",
                60,
                30,
                1
        );
    }
}
