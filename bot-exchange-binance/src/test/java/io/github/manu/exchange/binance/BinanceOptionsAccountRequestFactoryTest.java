package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOptionsAccountRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_options_margin_account_and_position_requests() {
        BinanceOptionsAccountRequestFactory factory = new BinanceOptionsAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest account = factory.marginAccount("test-secret");
        BinanceSignedRequest positions = factory.positions("BTC-240628-70000-C", "test-secret");

        assertThat(account.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
        assertThat(account.uri().toString()).startsWith("https://eapi.binance.com/eapi/v1/marginAccount?");
        assertThat(positions.payload())
                .isEqualTo("symbol=BTC-240628-70000-C&timestamp=1499827319559&recvWindow=5000");
        assertThat(positions.uri().toString()).startsWith("https://eapi.binance.com/eapi/v1/position?");
    }

    @Test
    void omits_optional_position_symbol_when_absent() {
        BinanceOptionsAccountRequestFactory factory = new BinanceOptionsAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.positions(null, "test-secret");

        assertThat(request.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
    }

    @Test
    void requires_options_account_config() {
        BinanceProperties missingAccount = new BinanceProperties(
                "OPTIONS",
                credentials(),
                rest(),
                websocket(),
                trading(),
                userData(),
                null,
                null
        );

        assertThatThrownBy(() -> new BinanceOptionsAccountRequestFactory(missingAccount, FIXED_CLOCK, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("options account config is required");
    }

    private BinanceProperties binance() {
        return new BinanceProperties(
                "OPTIONS",
                credentials(),
                rest(),
                websocket(),
                trading(),
                userData(),
                null,
                null,
                marketData(),
                reconciliation(),
                optionsAccount()
        );
    }

    private BinanceProperties.Credentials credentials() {
        return new BinanceProperties.Credentials(
                "binance_options",
                "api-key",
                "api-secret",
                "HMAC_SHA256",
                List.of("USER_DATA", "TRADE")
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://eapi.binance.com",
                "/eapi/v1/exchangeInfo",
                "/eapi/v1/time",
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
                "wss://fstream.binance.com",
                null,
                null,
                null,
                "/ws",
                "/stream",
                24,
                10,
                null,
                5,
                null,
                15,
                10,
                200,
                null,
                "MILLISECONDS",
                null,
                null
        );
    }

    private BinanceProperties.UserDataStream userData() {
        return new BinanceProperties.UserDataStream(
                "listen_key",
                false,
                "/eapi/v1/listenKey",
                "/eapi/v1/listenKey",
                "/eapi/v1/listenKey",
                60,
                55,
                1
        );
    }

    private BinanceProperties.MarketDataStream marketData() {
        return new BinanceProperties.MarketDataStream(false, "combined", "default", List.of());
    }

    private BinanceProperties.Reconciliation reconciliation() {
        return new BinanceProperties.Reconciliation(false, 60, 10_000, false, List.of(), false, false, false, false, false, List.of());
    }

    private BinanceProperties.Trading trading() {
        return new BinanceProperties.Trading(
                "/eapi/v1/order",
                null,
                "/eapi/v1/order",
                "/eapi/v1/order",
                "/eapi/v1/openOrders",
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
                null,
                null,
                null,
                null,
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT"),
                List.of("GTC"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true
        );
    }

    private BinanceProperties.OptionsAccount optionsAccount() {
        return new BinanceProperties.OptionsAccount(
                "/eapi/v1/marginAccount",
                "/eapi/v1/position"
        );
    }
}
