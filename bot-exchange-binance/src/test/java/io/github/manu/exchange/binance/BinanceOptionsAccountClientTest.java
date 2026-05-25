package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOptionsAccountClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void reads_options_margin_account_and_positions() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        {
                          "asset": [
                            {
                              "asset": "USDT",
                              "marginBalance": "99998.87365244",
                              "equity": "99998.87365244",
                              "available": "96883.72734374",
                              "initialMargin": "3115.14630870",
                              "maintMargin": "0.00000000",
                              "unrealizedPNL": "0.00000000",
                              "adjustedEquity": "99998.87365244"
                            }
                          ],
                          "greek": [
                            {
                              "underlying": "BTCUSDT",
                              "delta": "0.1",
                              "theta": "-0.2",
                              "gamma": "0.3",
                              "vega": "0.4"
                            }
                          ],
                          "time": 1762843368098,
                          "canTrade": true,
                          "canDeposit": true,
                          "canWithdraw": true,
                          "reduceOnly": false,
                          "tradeGroupId": -1
                        }
                        """, Map.of("X-MBX-USED-WEIGHT-1M", List.of("1"))),
                new BinanceHttpResponse(200, """
                        [
                          {
                            "entryPrice": "1000",
                            "symbol": "BTC-200730-9000-C",
                            "side": "SHORT",
                            "quantity": "-0.1",
                            "markValue": "105.00138",
                            "unrealizedPNL": "-5.00138",
                            "markPrice": "1050.0138",
                            "strikePrice": "9000",
                            "expiryDate": 1593511200000,
                            "priceScale": 2,
                            "quantityScale": 2,
                            "optionSide": "CALL",
                            "quoteAsset": "USDT",
                            "time": 1762872654561,
                            "bidQuantity": "0.0000",
                            "askQuantity": "0.0000"
                          }
                        ]
                        """)
        );
        BinanceOptionsAccountClient client = client(transport);

        BinanceOptionsMarginAccountSnapshot account = client.marginAccount();
        List<BinanceOptionsPositionSnapshot> positions = client.positions("BTC-200730-9000-C");

        assertThat(account.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.asset()).isEqualTo("USDT");
            assertThat(asset.available()).isEqualByComparingTo("96883.72734374");
            assertThat(asset.unrealizedPnl()).isEqualByComparingTo("0.00000000");
        });
        assertThat(account.greeks()).singleElement().satisfies(greek -> {
            assertThat(greek.underlying()).isEqualTo("BTCUSDT");
            assertThat(greek.theta()).isEqualByComparingTo("-0.2");
        });
        assertThat(account.canTrade()).isTrue();
        assertThat(account.tradeGroupId()).isEqualTo(-1L);
        assertThat(positions).singleElement().satisfies(position -> {
            assertThat(position.symbol()).isEqualTo("BTC-200730-9000-C");
            assertThat(position.quantity()).isEqualByComparingTo("-0.1");
            assertThat(position.optionSide()).isEqualTo("CALL");
            assertThat(position.expiryDate()).isEqualTo(1593511200000L);
        });
        assertThat(client.currentRateLimitUsage()).hasValueSatisfying(usage ->
                assertThat(usage.usedWeights()).containsEntry("X-MBX-USED-WEIGHT-1M", 1L)
        );
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/eapi/v1/marginAccount?timestamp="))
                .anySatisfy(uri -> assertThat(uri).contains("/eapi/v1/position?symbol=BTC-200730-9000-C"));
    }

    @Test
    void rejects_non_array_position_response() {
        BinanceOptionsAccountClient client = client(new FakeTransport(new BinanceHttpResponse(200, "{}")));

        assertThatThrownBy(() -> client.positions(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position array response");
    }

    @Test
    void reads_and_mutates_mmp_config_when_enabled() {
        FakeTransport transport = new FakeTransport(
                mmpConfig(),
                mmpConfig(),
                mmpConfig()
        );
        BinanceOptionsAccountClient client = client(transport);

        BinanceOptionsMmpConfig current = client.marketMakerProtection("BTCUSDT");
        BinanceOptionsMmpConfig updated = client.setMarketMakerProtection(
                new BinanceOptionsMmpConfigCommand(
                        "BTCUSDT",
                        3000L,
                        300000L,
                        new java.math.BigDecimal("2"),
                        new java.math.BigDecimal("2.3")
                )
        );
        BinanceOptionsMmpConfig reset = client.resetMarketMakerProtection("BTCUSDT");

        assertThat(current.underlying()).isEqualTo("BTCUSDT");
        assertThat(updated.deltaLimit()).isEqualByComparingTo("2.3");
        assertThat(reset.frozenTimeInMilliseconds()).isEqualTo(300000L);
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "POST", "POST");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/eapi/v1/mmp?underlying=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("/eapi/v1/mmpSet?underlying=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("/eapi/v1/mmpReset?underlying=BTCUSDT"));
    }

    @Test
    void raises_api_exception_for_error_response() {
        BinanceOptionsAccountClient client = client(new FakeTransport(new BinanceHttpResponse(400, """
                {
                  "code": -2015,
                  "msg": "Invalid API-key"
                }
                """)));

        assertThatThrownBy(client::marginAccount)
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("Invalid API-key");
    }

    private BinanceHttpResponse mmpConfig() {
        return new BinanceHttpResponse(200, """
                {
                  "underlyingId": 2,
                  "underlying": "BTCUSDT",
                  "windowTimeInMilliseconds": 3000,
                  "frozenTimeInMilliseconds": 300000,
                  "qtyLimit": "2",
                  "deltaLimit": "2.3",
                  "lastTriggerTime": 0
                }
                """);
    }

    private BinanceOptionsAccountClient client(FakeTransport transport) {
        return new BinanceOptionsAccountClient(
                binance(),
                "api-key",
                "test-secret",
                FIXED_CLOCK,
                0,
                transport,
                JsonMapperFactory.create()
        );
    }

    private BinanceProperties binance() {
        return new BinanceProperties(
                "OPTIONS",
                new BinanceProperties.Credentials(
                        "binance_options",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                trading(),
                userData(),
                null,
                null,
                marketData(),
                reconciliation(),
                new BinanceProperties.OptionsAccount(
                        "/eapi/v1/marginAccount",
                        "/eapi/v1/position",
                        "/eapi/v1/mmp",
                        "/eapi/v1/mmpSet",
                        "/eapi/v1/mmpReset",
                        5000,
                        true
                )
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
                "/eapi/v1/historyOrders",
                "/eapi/v1/userTrades",
                "/eapi/v1/commission",
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
                "/eapi/v1/exerciseRecord",
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

    private record FakeCall(String method, String uri) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {
        private final List<BinanceHttpResponse> responses;
        private final List<FakeCall> calls = new ArrayList<>();

        FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            calls.add(new FakeCall(method, uri.toString()));
            return responses.removeFirst();
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            calls.add(new FakeCall(method, request.uri().toString()));
            return responses.removeFirst();
        }

        List<FakeCall> calls() {
            return List.copyOf(calls);
        }
    }
}
