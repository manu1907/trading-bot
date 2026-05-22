package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceRestRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_public_uri_with_encoded_parameters_in_order() {
        BinanceRestRequestFactory factory = new BinanceRestRequestFactory(rest("MILLISECONDS"), FIXED_CLOCK, 0);

        URI uri = factory.publicUri("/fapi/v1/exchangeInfo", List.of(
                BinanceRequestParameter.of("symbol", "BTC USDT"),
                BinanceRequestParameter.of("contractType", "PERPETUAL")
        ));

        assertThat(uri.toString())
                .isEqualTo("https://demo-fapi.binance.com/fapi/v1/exchangeInfo?symbol=BTC%20USDT&contractType=PERPETUAL");
    }

    @Test
    void builds_signed_uri_with_timestamp_recv_window_and_signature() {
        BinanceRestRequestFactory factory = new BinanceRestRequestFactory(rest("MILLISECONDS"), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.signedUri("/fapi/v1/order", List.of(
                BinanceRequestParameter.of("symbol", "LTCBTC"),
                BinanceRequestParameter.of("side", "BUY"),
                BinanceRequestParameter.of("type", "LIMIT"),
                BinanceRequestParameter.of("timeInForce", "GTC"),
                BinanceRequestParameter.of("quantity", "1"),
                BinanceRequestParameter.of("price", "0.1")
        ), "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1"
                        + "&price=0.1&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.signature()).isEqualTo("c51a88e22dc770ce009fee43dcca0bb52c493885654181d29599317cfda1d08b");
        assertThat(request.uri().toString())
                .isEqualTo("https://demo-fapi.binance.com/fapi/v1/order?" + request.payload()
                        + "&signature=" + request.signature());
    }

    @Test
    void supports_microsecond_timestamps_with_server_time_offset() {
        BinanceRestRequestFactory factory = new BinanceRestRequestFactory(rest("MICROSECONDS"), FIXED_CLOCK, 250);

        BinanceSignedRequest request = factory.signedUri("/fapi/v1/order", List.of(
                BinanceRequestParameter.of("symbol", "BTCUSDT")
        ), "secret");

        assertThat(request.payload()).contains("timestamp=1499827319809000");
    }

    private BinanceProperties.Rest rest(String timestampUnit) {
        return new BinanceProperties.Rest(
                "https://demo-fapi.binance.com",
                "/fapi/v1/exchangeInfo",
                "/fapi/v1/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                timestampUnit,
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
}
