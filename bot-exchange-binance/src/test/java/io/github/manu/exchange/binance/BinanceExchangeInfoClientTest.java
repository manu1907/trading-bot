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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceExchangeInfoClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void fetches_and_parses_usdm_exchange_info_fixture() throws Exception {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, exchangeInfoFixture()));
        BinanceExchangeInfoClient client = client(transport);

        BinanceExchangeMetadata metadata = client.fetch();

        assertThat(transport.publicCalls).containsExactly(new PublicCall(
                "GET",
                "https://demo-fapi.binance.com/fapi/v1/exchangeInfo"
        ));
        assertThat(metadata.fetchedAt()).isEqualTo(Instant.parse("2026-05-22T20:00:00Z"));
        assertThat(metadata.restBaseUrl()).isEqualTo("https://demo-fapi.binance.com");
        assertThat(metadata.timezone()).isEqualTo("UTC");
        assertThat(metadata.rateLimits()).containsExactly(
                new BinanceExchangeMetadata.RateLimit("REQUEST_WEIGHT", "MINUTE", 1, 2400),
                new BinanceExchangeMetadata.RateLimit("ORDERS", "MINUTE", 1, 1200)
        );
        assertThat(metadata.assets()).containsExactly(
                new BinanceExchangeMetadata.AssetInfo("USDT", true, "0"),
                new BinanceExchangeMetadata.AssetInfo("BNB", false, null)
        );
        assertThat(metadata.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.symbol()).isEqualTo("BTCUSDT");
            assertThat(symbol.contractType()).isEqualTo("PERPETUAL");
            assertThat(symbol.deliveryDate()).isEqualTo(4_133_404_800_000L);
            assertThat(symbol.onboardDate()).isEqualTo(1_598_252_400_000L);
            assertThat(symbol.pricePrecision()).isEqualTo(2);
            assertThat(symbol.quantityPrecision()).isEqualTo(3);
            assertThat(symbol.underlyingSubType()).containsExactly("PoW");
            assertThat(symbol.triggerProtect()).isEqualTo("0.0500");
            assertThat(symbol.liquidationFee()).isEqualTo("0.012500");
            assertThat(symbol.marketTakeBound()).isEqualTo("0.30");
            assertThat(symbol.orderTypes()).containsExactly("LIMIT", "MARKET", "STOP_MARKET");
            assertThat(symbol.timeInForce()).containsExactly("GTC", "IOC", "FOK", "GTX");
            assertThat(symbol.filters()).containsExactly(
                    filter("PRICE_FILTER", "0.01", "1000000", "0.01", null, null, null, null, null, null, null, null),
                    filter("MIN_NOTIONAL", null, null, null, null, null, null, "5", null, null, null, null),
                    filter("PERCENT_PRICE", null, null, null, null, null, null, null, "1.1500", "0.8500", "4", null),
                    filter("MAX_NUM_ORDERS", null, null, null, null, null, null, null, null, null, null, 200)
            );
        });
    }

    @Test
    void throws_sanitized_exception_for_exchange_info_http_failure() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(503, """
                {"code": -1008, "msg": "Request throttled by system-level protection."}
                """));
        BinanceExchangeInfoClient client = client(transport);

        assertThatThrownBy(client::fetch)
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=503")
                .hasMessageContaining("exchangeInfo fetch failed");
    }

    private BinanceExchangeInfoClient client(FakeTransport transport) {
        return new BinanceExchangeInfoClient(
                rest(),
                transport,
                JsonMapperFactory.create(),
                FIXED_CLOCK,
                new BinanceExchangeInfoParser()
        );
    }

    private BinanceExchangeMetadata.Filter filter(String filterType,
                                                  String minPrice,
                                                  String maxPrice,
                                                  String tickSize,
                                                  String minQty,
                                                  String maxQty,
                                                  String stepSize,
                                                  String notional,
                                                  String multiplierUp,
                                                  String multiplierDown,
                                                  String multiplierDecimal,
                                                  Integer limit) {
        return new BinanceExchangeMetadata.Filter(
                filterType,
                minPrice,
                maxPrice,
                tickSize,
                minQty,
                maxQty,
                stepSize,
                notional,
                multiplierUp,
                multiplierDown,
                multiplierDecimal,
                limit
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

    private String exchangeInfoFixture() {
        return """
                {
                  "timezone": "UTC",
                  "rateLimits": [
                    {"rateLimitType": "REQUEST_WEIGHT", "interval": "MINUTE", "intervalNum": 1, "limit": 2400},
                    {"rateLimitType": "ORDERS", "interval": "MINUTE", "intervalNum": 1, "limit": 1200}
                  ],
                  "assets": [
                    {"asset": "USDT", "marginAvailable": true, "autoAssetExchange": "0"},
                    {"asset": "BNB", "marginAvailable": false, "autoAssetExchange": null}
                  ],
                  "symbols": [
                    {
                      "symbol": "BTCUSDT",
                      "pair": "BTCUSDT",
                      "contractType": "PERPETUAL",
                      "deliveryDate": 4133404800000,
                      "onboardDate": 1598252400000,
                      "status": "TRADING",
                      "baseAsset": "BTC",
                      "quoteAsset": "USDT",
                      "marginAsset": "USDT",
                      "pricePrecision": 2,
                      "quantityPrecision": 3,
                      "baseAssetPrecision": 8,
                      "quotePrecision": 8,
                      "underlyingType": "COIN",
                      "underlyingSubType": ["PoW"],
                      "triggerProtect": "0.0500",
                      "liquidationFee": "0.012500",
                      "marketTakeBound": "0.30",
                      "filters": [
                        {"filterType": "PRICE_FILTER", "minPrice": "0.01", "maxPrice": "1000000", "tickSize": "0.01"},
                        {"filterType": "MIN_NOTIONAL", "notional": "5"},
                        {
                          "filterType": "PERCENT_PRICE",
                          "multiplierUp": "1.1500",
                          "multiplierDown": "0.8500",
                          "multiplierDecimal": "4"
                        },
                        {"filterType": "MAX_NUM_ORDERS", "limit": 200}
                      ],
                      "orderTypes": ["LIMIT", "MARKET", "STOP_MARKET"],
                      "timeInForce": ["GTC", "IOC", "FOK", "GTX"]
                    }
                  ]
                }
                """;
    }

    private record PublicCall(String method, String uri) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {

        private final List<BinanceHttpResponse> responses;
        private final List<PublicCall> publicCalls = new ArrayList<>();

        private FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            publicCalls.add(new PublicCall(method, uri.toString()));
            return responses.removeFirst();
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            throw new UnsupportedOperationException("signed requests are not used by this test");
        }
    }
}
