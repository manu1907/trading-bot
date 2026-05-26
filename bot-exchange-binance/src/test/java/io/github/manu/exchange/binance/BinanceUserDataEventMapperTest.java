package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.TradingEventKeyEntityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceUserDataEventMapperTest {

    private static final BinanceUserDataEventMapper.Context SPOT_CONTEXT =
            new BinanceUserDataEventMapper.Context("binance", "demo", "main", "spot");
    private static final BinanceUserDataEventMapper.Context FUTURES_CONTEXT =
            new BinanceUserDataEventMapper.Context("binance", "demo", "main", "usd_m_futures");
    private static final BinanceUserDataEventMapper.Context OPTIONS_CONTEXT =
            new BinanceUserDataEventMapper.Context("binance", "demo", "main", "options");

    private final BinanceUserDataEventMapper mapper = new BinanceUserDataEventMapper();

    @Test
    void maps_spot_execution_report_wrapped_by_websocket_api_subscription() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "subscriptionId": 0,
                  "event": {
                    "e": "executionReport",
                    "E": 1499405658658,
                    "s": "ETHBTC",
                    "c": "tb-1",
                    "S": "BUY",
                    "o": "LIMIT",
                    "f": "GTC",
                    "q": "1.00000000",
                    "p": "0.10264410",
                    "P": "0.00000000",
                    "F": "0.00000000",
                    "g": -1,
                    "C": "",
                    "x": "TRADE",
                    "X": "PARTIALLY_FILLED",
                    "r": "NONE",
                    "i": 4293153,
                    "l": "0.10000000",
                    "z": "0.10000000",
                    "L": "0.10264410",
                    "Z": "0.10264410",
                    "n": "0.00100000",
                    "N": "BNB",
                    "T": 1499405658657,
                    "t": 123,
                    "I": 8641984,
                    "m": false,
                    "O": 1499405658655,
                    "W": 1499405658656,
                    "V": "NONE"
                  }
                }
                """, SPOT_CONTEXT);

        assertThat(envelopes).hasSize(1);
        TradingEventEnvelope<?> envelope = envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.EXECUTION_REPORT);
        assertThat(envelope.key().getEntityType()).isEqualTo(TradingEventKeyEntityType.ORDER);
        assertThat(envelope.key().getPartitionKey()).hasToString(
                "execution_report|order|binance|demo|main|spot|ethbtc|tb-1"
        );

        ExecutionReportEvent value = execution(envelope);
        assertThat(value.getEventId()).hasToString("binance:demo:main:spot:executionReport:ETHBTC:tb-1:4293153:8641984:1499405658658");
        assertThat(value.getProvider()).hasToString("binance");
        assertThat(value.getMarket()).hasToString("spot");
        assertThat(value.getSymbol()).hasToString("ETHBTC");
        assertThat(value.getClientOrderId()).hasToString("tb-1");
        assertThat(value.getExchangeOrderId()).hasToString("4293153");
        assertThat(value.getExecutionId()).hasToString("8641984");
        assertThat(value.getTradeId()).hasToString("123");
        assertThat(value.getSide()).hasToString("BUY");
        assertThat(value.getOrderType()).hasToString("LIMIT");
        assertThat(value.getOrderStatus()).hasToString("PARTIALLY_FILLED");
        assertThat(value.getExecutionType()).hasToString("TRADE");
        assertThat(value.getLastExecutedQuantity()).hasToString("0.10000000");
        assertThat(value.getLastExecutedPrice()).hasToString("0.10264410");
        assertThat(value.getCumulativeFilledQuantity()).hasToString("0.10000000");
        assertThat(value.getCumulativeQuoteQuantity()).hasToString("0.10264410");
        assertThat(value.getCommissionAsset()).hasToString("BNB");
        assertThat(value.getCommissionAmount()).hasToString("0.00100000");
        assertThat(value.getMaker()).isFalse();
        assertThat(value.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_499_405_658_658L));
        assertThat(value.getTransactionTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_499_405_658_657L));
        assertThat(attributes(value.getAttributes()))
                .containsEntry("timeInForce", "GTC")
                .containsEntry("orderPrice", "0.10264410")
                .containsEntry("selfTradePreventionMode", "NONE");
    }

    @Test
    void maps_futures_order_trade_update() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "e": "ORDER_TRADE_UPDATE",
                  "E": 1568879465651,
                  "T": 1568879465650,
                  "o": {
                    "s": "BTCUSDT",
                    "c": "TEST",
                    "S": "SELL",
                    "o": "TRAILING_STOP_MARKET",
                    "f": "GTC",
                    "q": "0.001",
                    "p": "0",
                    "ap": "0",
                    "sp": "7103.04",
                    "x": "NEW",
                    "X": "NEW",
                    "i": 8886774,
                    "l": "0",
                    "z": "0",
                    "L": "0",
                    "N": "USDT",
                    "n": "0",
                    "T": 1568879465650,
                    "t": 0,
                    "m": false,
                    "R": false,
                    "wt": "CONTRACT_PRICE",
                    "ot": "TRAILING_STOP_MARKET",
                    "ps": "LONG",
                    "cp": false,
                    "AP": "7476.89",
                    "cr": "5.0",
                    "pP": false,
                    "rp": "0",
                    "V": "EXPIRE_TAKER",
                    "pm": "OPPONENT",
                    "gtd": 0,
                    "er": "0"
                  }
                }
                """, FUTURES_CONTEXT);

        assertThat(envelopes).hasSize(1);
        ExecutionReportEvent value = execution(envelopes.getFirst());
        assertThat(value.getEventId()).hasToString("binance:demo:main:usd_m_futures:ORDER_TRADE_UPDATE:BTCUSDT:TEST:8886774:0:1568879465651");
        assertThat(value.getTradeId()).isNull();
        assertThat(value.getOrderType()).hasToString("TRAILING_STOP_MARKET");
        assertThat(value.getCumulativeQuoteQuantity()).isNull();
        assertThat(value.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_568_879_465_651L));
        assertThat(attributes(value.getAttributes()))
                .containsEntry("rawEventType", "ORDER_TRADE_UPDATE")
                .containsEntry("positionSide", "LONG")
                .containsEntry("workingType", "CONTRACT_PRICE")
                .containsEntry("activationPrice", "7476.89");
    }

    @Test
    void maps_options_order_trade_update() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "e": "ORDER_TRADE_UPDATE",
                  "E": 1568879465651,
                  "T": 1568879465650,
                  "o": {
                    "s": "BTC-240628-70000-C",
                    "c": "TEST",
                    "S": "SELL",
                    "o": "LIMIT",
                    "f": "GTC",
                    "q": "0.001",
                    "p": "100",
                    "ap": "0",
                    "x": "NEW",
                    "X": "NEW",
                    "i": 8886774,
                    "l": "0",
                    "z": "0",
                    "L": "0",
                    "N": "USDT",
                    "n": "0",
                    "T": 1568879465650,
                    "t": 0,
                    "b": "0",
                    "a": "9.91",
                    "m": false,
                    "R": false,
                    "ot": "LIMIT",
                    "rp": "0",
                    "V": "EXPIRE_TAKER"
                  }
                }
                """, OPTIONS_CONTEXT);

        assertThat(envelopes).hasSize(1);
        TradingEventEnvelope<?> envelope = envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.EXECUTION_REPORT);
        assertThat(envelope.key().getPartitionKey()).hasToString(
                "execution_report|order|binance|demo|main|options|btc-240628-70000-c|test"
        );
        ExecutionReportEvent value = execution(envelope);
        assertThat(value.getMarket()).hasToString("options");
        assertThat(value.getSymbol()).hasToString("BTC-240628-70000-C");
        assertThat(value.getClientOrderId()).hasToString("TEST");
        assertThat(value.getExchangeOrderId()).hasToString("8886774");
        assertThat(value.getOrderType()).hasToString("LIMIT");
        assertThat(value.getTradeId()).isNull();
        assertThat(attributes(value.getAttributes()))
                .containsEntry("rawEventType", "ORDER_TRADE_UPDATE")
                .containsEntry("bidQuantity", "0")
                .containsEntry("askQuantity", "9.91")
                .containsEntry("originalOrderType", "LIMIT")
                .containsEntry("selfTradePreventionMode", "EXPIRE_TAKER");
    }

    @Test
    void maps_spot_balance_updates() {
        List<TradingEventEnvelope<?>> accountPosition = mapper.map("""
                {
                  "e": "outboundAccountPosition",
                  "E": 1564034571105,
                  "u": 1564034571073,
                  "B": [
                    { "a": "ETH", "f": "10000.000000", "l": "0.000000" },
                    { "a": "BTC", "f": "2.000000", "l": "1.000000" }
                  ]
                }
                """, SPOT_CONTEXT);
        List<TradingEventEnvelope<?>> balanceDelta = mapper.map("""
                {
                  "e": "balanceUpdate",
                  "E": 1573200697110,
                  "a": "BTC",
                  "d": "100.00000000",
                  "T": 1573200697068
                }
                """, SPOT_CONTEXT);

        assertThat(accountPosition).hasSize(2);
        BalanceUpdateEvent eth = balance(accountPosition.getFirst());
        assertThat(accountPosition.getFirst().eventType()).isEqualTo(TradingEventType.BALANCE_UPDATE);
        assertThat(accountPosition.getFirst().key().getEntityType()).isEqualTo(TradingEventKeyEntityType.ACCOUNT);
        assertThat(eth.getAsset()).hasToString("ETH");
        assertThat(eth.getAvailableBalance()).hasToString("10000.000000");
        assertThat(eth.getUpdateReason()).hasToString("outboundAccountPosition");
        assertThat(attributes(eth.getAttributes()))
                .containsEntry("lockedBalance", "0.000000")
                .containsEntry("lastAccountUpdateTime", "1564034571073");

        assertThat(balanceDelta).hasSize(1);
        BalanceUpdateEvent btc = balance(balanceDelta.getFirst());
        assertThat(btc.getAsset()).hasToString("BTC");
        assertThat(btc.getBalanceDelta()).hasToString("100.00000000");
        assertThat(btc.getUpdateReason()).hasToString("balanceUpdate");
        assertThat(btc.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_573_200_697_110L));
    }

    @Test
    void maps_futures_account_update_into_balance_and_position_events() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "e": "ACCOUNT_UPDATE",
                  "E": 1564745798939,
                  "T": 1564745798938,
                  "i": "SfsR",
                  "a": {
                    "m": "ORDER",
                    "B": [
                      {
                        "a": "USDT",
                        "wb": "122624.12345678",
                        "cw": "100.12345678",
                        "bc": "50.12345678"
                      }
                    ],
                    "P": [
                      {
                        "s": "BTCUSDT",
                        "pa": "20",
                        "ep": "6563.66500",
                        "bep": "6563.60000",
                        "cr": "0",
                        "up": "2850.21200",
                        "mt": "isolated",
                        "iw": "13200.70726908",
                        "ps": "LONG"
                      }
                    ]
                  }
                }
                """, FUTURES_CONTEXT);

        assertThat(envelopes).hasSize(2);
        BalanceUpdateEvent balance = balance(envelopes.getFirst());
        assertThat(balance.getAsset()).hasToString("USDT");
        assertThat(balance.getWalletBalance()).hasToString("122624.12345678");
        assertThat(balance.getCrossWalletBalance()).hasToString("100.12345678");
        assertThat(balance.getBalanceDelta()).hasToString("50.12345678");
        assertThat(balance.getUpdateReason()).hasToString("ORDER");

        TradingEventEnvelope<?> positionEnvelope = envelopes.get(1);
        assertThat(positionEnvelope.eventType()).isEqualTo(TradingEventType.POSITION_UPDATE);
        assertThat(positionEnvelope.key().getEntityType()).isEqualTo(TradingEventKeyEntityType.SYMBOL);
        PositionUpdateEvent position = position(positionEnvelope);
        assertThat(position.getSymbol()).hasToString("BTCUSDT");
        assertThat(position.getPositionSide()).hasToString("LONG");
        assertThat(position.getPositionAmount()).hasToString("20");
        assertThat(position.getEntryPrice()).hasToString("6563.66500");
        assertThat(position.getUnrealizedPnl()).hasToString("2850.21200");
        assertThat(position.getMarginType()).hasToString("isolated");
        assertThat(position.getIsolatedMargin()).hasToString("13200.70726908");
        assertThat(attributes(position.getAttributes()))
                .containsEntry("updateReason", "ORDER")
                .containsEntry("breakEvenPrice", "6563.60000")
                .containsEntry("accumulatedRealized", "0");
    }

    @Test
    void maps_options_account_data_wrapped_by_stream_payload() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "stream": "89ljxuL6jFTN3Ej85aYOqH2BYXQ7eeuNYcGm7ktV",
                  "data": {
                    "e": "ACCOUNT_UPDATE",
                    "E": 1762914568643,
                    "T": 1762914568619,
                    "eq": "10000371.61462086",
                    "aeq": "10000475.51032086",
                    "b": "10000475.51032086",
                    "m": "-103.89570000",
                    "u": "16.10430000",
                    "i": "32354.38562539",
                    "M": "6089.28766956"
                  }
                }
                """, OPTIONS_CONTEXT);

        assertThat(envelopes).hasSize(1);
        BalanceUpdateEvent balance = balance(envelopes.getFirst());
        assertThat(balance.getAsset()).hasToString("USDT");
        assertThat(balance.getWalletBalance()).hasToString("10000475.51032086");
        assertThat(balance.getUpdateReason()).hasToString("ACCOUNT_UPDATE");
        assertThat(balance.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_762_914_568_643L));
        assertThat(attributes(balance.getAttributes()))
                .containsEntry("rawEventType", "ACCOUNT_UPDATE")
                .containsEntry("equity", "10000371.61462086")
                .containsEntry("adjustedEquity", "10000475.51032086")
                .containsEntry("positionValue", "-103.89570000")
                .containsEntry("unrealizedPnl", "16.10430000")
                .containsEntry("initialMargin", "32354.38562539")
                .containsEntry("maintenanceMargin", "6089.28766956");
    }

    @Test
    void rejects_account_update_without_futures_account_payload_outside_options_market() {
        assertThatThrownBy(() -> mapper.map("""
                {
                  "e": "ACCOUNT_UPDATE",
                  "E": 1762914568643,
                  "T": 1762914568619,
                  "eq": "10000371.61462086",
                  "b": "10000475.51032086"
                }
                """, FUTURES_CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCOUNT_UPDATE payload missing a");
    }

    @Test
    void maps_options_balance_position_update() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "e": "BALANCE_POSITION_UPDATE",
                  "E": 1762917544216,
                  "T": 1762917544206,
                  "m": "ORDER",
                  "B": [
                    {
                      "a": "USDT",
                      "b": "10000471.37940900",
                      "bc": "0"
                    }
                  ],
                  "P": [
                    {
                      "s": "BTC-251123-126000-C",
                      "c": "-0.1000",
                      "p": "-120.00000000",
                      "a": "1200.00000000"
                    }
                  ]
                }
                """, OPTIONS_CONTEXT);

        assertThat(envelopes).hasSize(2);
        BalanceUpdateEvent balance = balance(envelopes.getFirst());
        assertThat(balance.getAsset()).hasToString("USDT");
        assertThat(balance.getWalletBalance()).hasToString("10000471.37940900");
        assertThat(balance.getBalanceDelta()).hasToString("0");
        assertThat(balance.getUpdateReason()).hasToString("ORDER");

        TradingEventEnvelope<?> positionEnvelope = envelopes.get(1);
        assertThat(positionEnvelope.eventType()).isEqualTo(TradingEventType.POSITION_UPDATE);
        assertThat(positionEnvelope.key().getPartitionKey()).hasToString(
                "position_update|symbol|binance|demo|main|options|btc-251123-126000-c|btc-251123-126000-c"
        );
        PositionUpdateEvent position = position(positionEnvelope);
        assertThat(position.getSymbol()).hasToString("BTC-251123-126000-C");
        assertThat(position.getPositionSide()).hasToString("SHORT");
        assertThat(position.getPositionAmount()).hasToString("-0.1000");
        assertThat(position.getEntryPrice()).hasToString("1200.00000000");
        assertThat(position.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_762_917_544_216L));
        assertThat(attributes(position.getAttributes()))
                .containsEntry("updateReason", "ORDER")
                .containsEntry("positionValue", "-120.00000000")
                .containsEntry("transactionTime", "1762917544206");
    }

    @Test
    void ignores_unknown_user_data_events() {
        List<TradingEventEnvelope<?>> envelopes = mapper.map("""
                {
                  "e": "listenKeyExpired",
                  "E": 1728973001334
                }
                """, SPOT_CONTEXT);

        assertThat(envelopes).isEmpty();
    }

    private ExecutionReportEvent execution(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.value()).isInstanceOf(ExecutionReportEvent.class);
        return (ExecutionReportEvent) envelope.value();
    }

    private BalanceUpdateEvent balance(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.value()).isInstanceOf(BalanceUpdateEvent.class);
        return (BalanceUpdateEvent) envelope.value();
    }

    private PositionUpdateEvent position(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.value()).isInstanceOf(PositionUpdateEvent.class);
        return (PositionUpdateEvent) envelope.value();
    }

    private Map<String, String> attributes(Map<CharSequence, CharSequence> attributes) {
        Map<String, String> converted = new LinkedHashMap<>();
        attributes.forEach((key, value) -> converted.put(key.toString(), value.toString()));
        return converted;
    }
}
