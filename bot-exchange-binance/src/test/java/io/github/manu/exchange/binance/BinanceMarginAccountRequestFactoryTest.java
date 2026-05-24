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

class BinanceMarginAccountRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_cross_margin_borrow_repay_request() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_CROSS"),
                FIXED_CLOCK,
                0
        );

        BinanceSignedRequest request = factory.borrowRepay(
                new BinanceMarginBorrowRepayCommand("USDT", new BigDecimal("25.5000"), "BORROW", false, null),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("asset=USDT&isIsolated=FALSE&amount=25.5&type=BORROW&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/borrow-repay?");
    }

    @Test
    void builds_isolated_margin_repay_request() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_ISOLATED"),
                FIXED_CLOCK,
                0
        );

        BinanceSignedRequest request = factory.borrowRepay(
                new BinanceMarginBorrowRepayCommand("BTC", new BigDecimal("0.010000"), "REPAY", true, "BTCUSDT"),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("asset=BTC&isIsolated=TRUE&symbol=BTCUSDT&amount=0.01&type=REPAY"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/borrow-repay?");
    }

    @Test
    void validates_borrow_repay_command_shape() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_ISOLATED"),
                FIXED_CLOCK,
                0
        );

        assertThatThrownBy(() -> factory.borrowRepay(
                new BinanceMarginBorrowRepayCommand("BTC", new BigDecimal("0.01"), "REPAY", true, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> factory.borrowRepay(
                new BinanceMarginBorrowRepayCommand("USDT", new BigDecimal("25"), "BORROW", false, "BTCUSDT"),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is only supported for isolated margin borrow/repay");
        assertThatThrownBy(() -> factory.borrowRepay(
                new BinanceMarginBorrowRepayCommand("USDT", new BigDecimal("25"), "TRANSFER", false, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must be one of");
    }

    @Test
    void builds_transfer_history_request() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_CROSS"),
                FIXED_CLOCK,
                0
        );

        BinanceSignedRequest request = factory.transferHistory(
                new BinanceMarginTransferHistoryQuery(
                        "USDT",
                        "ROLL_OUT",
                        1_700_000_000_000L,
                        1_700_086_400_000L,
                        2L,
                        100L,
                        "BTCUSDT"
                ),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("asset=USDT&type=ROLL_OUT&startTime=1700000000000&endTime=1700086400000"
                        + "&current=2&size=100&isolatedSymbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/transfer?");
    }

    @Test
    void builds_max_transferable_request() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_ISOLATED"),
                FIXED_CLOCK,
                0
        );

        BinanceSignedRequest request = factory.maxTransferable("USDT", "BTCUSDT", "test-secret");

        assertThat(request.payload())
                .isEqualTo("asset=USDT&isolatedSymbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/maxTransferable?");
    }

    @Test
    void builds_margin_account_and_risk_read_requests() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_ISOLATED"),
                FIXED_CLOCK,
                0
        );

        BinanceSignedRequest crossAccount = factory.crossAccount("test-secret");
        BinanceSignedRequest isolatedAccount = factory.isolatedAccount(
                new BinanceIsolatedMarginAccountQuery(List.of("BTCUSDT", "ETHUSDT")),
                "test-secret"
        );
        BinanceSignedRequest isolatedLimit = factory.isolatedAccountLimit("test-secret");
        BinanceSignedRequest tradeCoeff = factory.tradeCoeff("test-secret");

        assertThat(crossAccount.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
        assertThat(crossAccount.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/account?");
        assertThat(isolatedAccount.payload())
                .isEqualTo("symbols=BTCUSDT%2CETHUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(isolatedAccount.uri().toString())
                .startsWith("https://api.binance.com/sapi/v1/margin/isolated/account?");
        assertThat(isolatedLimit.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
        assertThat(isolatedLimit.uri().toString())
                .startsWith("https://api.binance.com/sapi/v1/margin/isolated/accountLimit?");
        assertThat(tradeCoeff.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
        assertThat(tradeCoeff.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/tradeCoeff?");
    }

    @Test
    void validates_transfer_queries_against_documented_limits() {
        BinanceMarginAccountRequestFactory factory = new BinanceMarginAccountRequestFactory(
                marginBinance("MARGIN_CROSS"),
                FIXED_CLOCK,
                0
        );

        assertThatThrownBy(() -> factory.transferHistory(
                new BinanceMarginTransferHistoryQuery(null, "TRANSFER", null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must be one of");
        assertThatThrownBy(() -> factory.transferHistory(
                new BinanceMarginTransferHistoryQuery(null, null, 1_700_000_000_000L, 1_702_678_400_001L, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval must be at most 30 days");
        assertThatThrownBy(() -> factory.transferHistory(
                new BinanceMarginTransferHistoryQuery(null, null, null, null, null, 101L, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be at most 100");
        assertThatThrownBy(() -> factory.maxTransferable(null, null, "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset is required");
        assertThatThrownBy(() -> factory.isolatedAccount(
                new BinanceIsolatedMarginAccountQuery(List.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "ADAUSDT", "SOLUSDT", "XRPUSDT")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbols size must be at most 5");
        assertThatThrownBy(() -> factory.isolatedAccount(
                new BinanceIsolatedMarginAccountQuery(List.of(" ")),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbols is required");
    }

    @Test
    void requires_margin_account_config() {
        assertThatThrownBy(() -> new BinanceMarginAccountRequestFactory(spotBinance(), FIXED_CLOCK, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Binance margin account config is required");
    }

    private BinanceProperties marginBinance(String marketType) {
        return new BinanceProperties(
                marketType,
                credentials(),
                rest(),
                websocket(),
                null,
                null,
                marginAccount(),
                null
        );
    }

    private BinanceProperties spotBinance() {
        return new BinanceProperties(
                "SPOT",
                credentials(),
                rest(),
                websocket(),
                null,
                null,
                null,
                null
        );
    }

    private BinanceProperties.Credentials credentials() {
        return new BinanceProperties.Credentials(
                "binance_real_main",
                "api-key",
                "api-secret",
                "HMAC_SHA256",
                List.of("USER_DATA", "MARGIN", "TRADE")
        );
    }

    private BinanceProperties.Rest rest() {
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

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://stream.binance.com:9443",
                "/ws",
                "/ws",
                "/ws",
                "/ws",
                "/stream",
                24,
                null,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MILLISECONDS",
                null,
                null
        );
    }

    private BinanceProperties.MarginAccount marginAccount() {
        return new BinanceProperties.MarginAccount(
                "/sapi/v1/margin/borrow-repay",
                "/sapi/v1/margin/transfer",
                "/sapi/v1/margin/maxTransferable",
                "/sapi/v1/margin/account",
                "/sapi/v1/margin/isolated/account",
                "/sapi/v1/margin/isolated/accountLimit",
                "/sapi/v1/margin/tradeCoeff",
                List.of("BORROW", "REPAY"),
                List.of("ROLL_IN", "ROLL_OUT"),
                30,
                100,
                5
        );
    }
}
