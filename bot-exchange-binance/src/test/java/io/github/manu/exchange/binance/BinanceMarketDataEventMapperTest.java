package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.events.v1.MarketDataEventType;
import io.github.manu.events.v1.TradingEventKeyEntityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceMarketDataEventMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-05-25T10:30:00Z");
    private static final BinanceMarketDataEventMapper.Context SPOT_CONTEXT =
            new BinanceMarketDataEventMapper.Context("binance", "demo", "main", "spot", RECEIVED_AT);
    private static final BinanceMarketDataEventMapper.Context FUTURES_CONTEXT =
            new BinanceMarketDataEventMapper.Context("binance", "demo", "main", "usd_m_futures", RECEIVED_AT);

    private final BinanceMarketDataEventMapper mapper = new BinanceMarketDataEventMapper();

    @Test
    void maps_spot_trade_stream() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "e": "trade",
                  "E": 1672515782136,
                  "s": "BNBBTC",
                  "t": 12345,
                  "p": "0.001",
                  "q": "100",
                  "T": 1672515782136,
                  "m": true,
                  "M": true
                }
                """, SPOT_CONTEXT);

        assertThat(envelopes).hasSize(1);
        TradingEventEnvelope<MarketDataEvent> envelope = envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.MARKET_DATA);
        assertThat(envelope.key().getEntityType()).isEqualTo(TradingEventKeyEntityType.SYMBOL);
        assertThat(envelope.key().getPartitionKey()).hasToString(
                "market_data|symbol|binance|demo|main|spot|bnbbtc|bnbbtc"
        );

        MarketDataEvent value = envelope.value();
        assertThat(value.getEventId()).hasToString("binance:demo:main:spot:TRADE:BNBBTC:12345:-");
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.TRADE);
        assertThat(value.getOccurredAtMicros()).isEqualTo(Instant.ofEpochMilli(1_672_515_782_136L));
        assertThat(value.getReceivedAtMicros()).isEqualTo(RECEIVED_AT);
        assertThat(value.getTradeId()).hasToString("12345");
        assertThat(value.getSide()).hasToString("SELL");
        assertThat(value.getPrice()).hasToString("0.001");
        assertThat(value.getQuantity()).hasToString("100");
        assertThat(attributes(value.getAttributes()))
                .containsEntry("rawEventType", "trade")
                .containsEntry("tradeTime", "1672515782136")
                .containsEntry("buyerMarketMaker", "true");
    }

    @Test
    void maps_combined_futures_aggregate_trade_stream() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "stream": "btcusdt@aggTrade",
                  "data": {
                    "e": "aggTrade",
                    "E": 123456789,
                    "s": "BTCUSDT",
                    "a": 5933014,
                    "p": "0.001",
                    "q": "100",
                    "nq": "99",
                    "f": 100,
                    "l": 105,
                    "T": 123456785,
                    "m": false
                  }
                }
                """, FUTURES_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.TRADE);
        assertThat(value.getExchangeSequence()).isEqualTo(5_933_014L);
        assertThat(value.getTradeId()).hasToString("5933014");
        assertThat(value.getSide()).hasToString("BUY");
        assertThat(attributes(value.getAttributes()))
                .containsEntry("stream", "btcusdt@aggTrade")
                .containsEntry("aggregateTradeId", "5933014")
                .containsEntry("firstTradeId", "100")
                .containsEntry("lastTradeId", "105")
                .containsEntry("normalQuantity", "99");
    }

    @Test
    void maps_book_ticker_without_exchange_event_time_using_receive_time() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "u": 400900217,
                  "s": "BNBUSDT",
                  "b": "25.35190000",
                  "B": "31.21000000",
                  "a": "25.36520000",
                  "A": "40.66000000"
                }
                """, SPOT_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.BOOK_TICKER);
        assertThat(value.getExchangeSequence()).isEqualTo(400_900_217L);
        assertThat(value.getOccurredAtMicros()).isEqualTo(RECEIVED_AT);
        assertThat(value.getBids()).singleElement().satisfies(level -> {
            assertThat(level.getPrice()).hasToString("25.35190000");
            assertThat(level.getQuantity()).hasToString("31.21000000");
        });
        assertThat(value.getAsks()).singleElement().satisfies(level -> {
            assertThat(level.getPrice()).hasToString("25.36520000");
            assertThat(level.getQuantity()).hasToString("40.66000000");
        });
    }

    @Test
    void maps_depth_snapshot_symbol_from_combined_stream_name() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "stream": "bnbbtc@depth5",
                  "data": {
                    "lastUpdateId": 160,
                    "bids": [["0.0024", "10"]],
                    "asks": [["0.0026", "100"]]
                  }
                }
                """, SPOT_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.DEPTH_SNAPSHOT);
        assertThat(value.getSymbol()).hasToString("BNBBTC");
        assertThat(value.getExchangeSequence()).isEqualTo(160L);
        assertThat(value.getBids()).singleElement()
                .satisfies(level -> assertThat(level.getPrice()).hasToString("0.0024"));
        assertThat(value.getAsks()).singleElement()
                .satisfies(level -> assertThat(level.getQuantity()).hasToString("100"));
        assertThat(attributes(value.getAttributes())).containsEntry("stream", "bnbbtc@depth5");
    }

    @Test
    void maps_futures_depth_delta_with_previous_update_id() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "e": "depthUpdate",
                  "E": 123456789,
                  "T": 123456788,
                  "s": "BTCUSDT",
                  "U": 157,
                  "u": 160,
                  "pu": 149,
                  "b": [["0.0024", "10"]],
                  "a": [["0.0026", "100"]]
                }
                """, FUTURES_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.DEPTH_DELTA);
        assertThat(value.getExchangeSequence()).isEqualTo(160L);
        assertThat(attributes(value.getAttributes()))
                .containsEntry("firstUpdateId", "157")
                .containsEntry("previousFinalUpdateId", "149")
                .containsEntry("transactionTime", "123456788");
    }

    @Test
    void maps_futures_mark_price_stream() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "e": "markPriceUpdate",
                  "E": 1562305380000,
                  "s": "BTCUSDT",
                  "p": "11794.15000000",
                  "ap": "11794.15000000",
                  "i": "11784.62659091",
                  "P": "11784.25641265",
                  "r": "0.00038167",
                  "T": 1562306400000
                }
                """, FUTURES_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.MARK_PRICE);
        assertThat(value.getPrice()).hasToString("11794.15000000");
        assertThat(attributes(value.getAttributes()))
                .containsEntry("movingAverageMarkPrice", "11794.15000000")
                .containsEntry("indexPrice", "11784.62659091")
                .containsEntry("fundingRate", "0.00038167")
                .containsEntry("nextFundingTime", "1562306400000");
    }

    @Test
    void maps_kline_stream() {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes = mapper.map("""
                {
                  "e": "kline",
                  "E": 1672515782136,
                  "s": "BNBBTC",
                  "k": {
                    "t": 1672515780000,
                    "T": 1672515839999,
                    "s": "BNBBTC",
                    "i": "1m",
                    "f": 100,
                    "L": 200,
                    "o": "0.0010",
                    "c": "0.0020",
                    "h": "0.0025",
                    "l": "0.0015",
                    "v": "1000",
                    "n": 100,
                    "x": false,
                    "q": "1.0000",
                    "V": "500",
                    "Q": "0.500"
                  }
                }
                """, SPOT_CONTEXT);

        MarketDataEvent value = envelopes.getFirst().value();
        assertThat(value.getEventType()).isEqualTo(MarketDataEventType.KLINE);
        assertThat(value.getExchangeSequence()).isEqualTo(200L);
        assertThat(value.getPrice()).hasToString("0.0020");
        assertThat(value.getQuantity()).hasToString("1000");
        assertThat(attributes(value.getAttributes()))
                .containsEntry("interval", "1m")
                .containsEntry("openPrice", "0.0010")
                .containsEntry("highPrice", "0.0025")
                .containsEntry("lowPrice", "0.0015")
                .containsEntry("closed", "false");
    }

    @Test
    void ignores_control_and_unknown_events() {
        assertThat(mapper.map("{\"result\":null,\"id\":1}", SPOT_CONTEXT)).isEmpty();
        assertThat(mapper.map("{\"e\":\"serverShutdown\",\"E\":1770123456789}", SPOT_CONTEXT)).isEmpty();
    }

    @Test
    void rejects_recognized_payload_without_required_symbol() {
        assertThatThrownBy(() -> mapper.map("""
                {
                  "lastUpdateId": 160,
                  "bids": [["0.0024", "10"]],
                  "asks": [["0.0026", "100"]]
                }
                """, SPOT_CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing symbol");
    }

    private Map<String, String> attributes(Map<CharSequence, CharSequence> attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        attributes.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }
}
