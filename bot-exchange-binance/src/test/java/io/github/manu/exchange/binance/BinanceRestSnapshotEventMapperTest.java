package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.events.v1.TradingEventKeyEntityType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceRestSnapshotEventMapperTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-05-25T12:00:00Z");
    private static final BinanceRestSnapshotEventMapper.Context CONTEXT =
            new BinanceRestSnapshotEventMapper.Context("binance", "demo", "main", "usd_m_futures", OBSERVED_AT);
    private static final BinanceRestSnapshotEventMapper.Context OPTIONS_CONTEXT =
            new BinanceRestSnapshotEventMapper.Context("binance", "demo", "main", "options", OBSERVED_AT);

    private final BinanceRestSnapshotEventMapper mapper = new BinanceRestSnapshotEventMapper();

    @Test
    void maps_open_orders_to_order_result_reconciliation_events() {
        List<TradingEventEnvelope<OrderResultEvent>> envelopes = mapper.openOrders(List.of(new BinanceOrderResult(
                "BTCUSDT",
                12345L,
                "tb-1",
                "PARTIALLY_FILLED",
                "BUY",
                "LIMIT",
                "LONG",
                decimal("50000.00"),
                decimal("0.010"),
                decimal("0.004"),
                decimal("50010.00"),
                decimal("200.04"),
                1_772_000_000_001L
        )), CONTEXT);

        assertThat(envelopes).hasSize(1);
        TradingEventEnvelope<OrderResultEvent> envelope = envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.ORDER_RESULT);
        assertThat(envelope.key().getEntityType()).isEqualTo(TradingEventKeyEntityType.ORDER);
        assertThat(envelope.key().getPartitionKey()).hasToString(
                "order_result|order|binance|demo|main|usd_m_futures|btcusdt|tb-1"
        );

        OrderResultEvent value = envelope.value();
        assertThat(value.getCommandId()).hasToString("reconciliation:tb-1");
        assertThat(value.getStatus()).isEqualTo(OrderResultStatus.PARTIALLY_FILLED);
        assertThat(value.getExchangeStatus()).hasToString("PARTIALLY_FILLED");
        assertThat(value.getExchangeOrderId()).hasToString("12345");
        assertThat(value.getPrice()).hasToString("50000");
        assertThat(value.getOriginalQuantity()).hasToString("0.01");
        assertThat(value.getExecutedQuantity()).hasToString("0.004");
        assertThat(value.getAveragePrice()).hasToString("50010");
        assertThat(value.getCumulativeQuote()).hasToString("200.04");
        assertThat(value.getExchangeTransactTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_772_000_000_001L));
        assertThat(value.getObservedAtMicros()).isEqualTo(OBSERVED_AT);
        assertThat(attributes(value.getAttributes()))
                .containsEntry("source", "rest_snapshot")
                .containsEntry("snapshotType", "open_orders")
                .containsEntry("side", "BUY")
                .containsEntry("positionSide", "LONG");
    }

    @Test
    void maps_futures_account_snapshot_to_balance_and_position_events() {
        BinanceFuturesAccountSnapshot snapshot = new BinanceFuturesAccountSnapshot(
                decimal("10"),
                decimal("2"),
                decimal("1000"),
                decimal("12.5"),
                decimal("1012.5"),
                decimal("8"),
                decimal("1"),
                decimal("900"),
                decimal("12.5"),
                decimal("700"),
                decimal("650"),
                true,
                true,
                true,
                0,
                1_772_000_000_000L,
                List.of(new BinanceFuturesAssetSnapshot(
                        "USDT",
                        decimal("1000.000"),
                        decimal("12.5"),
                        decimal("1012.5"),
                        decimal("2"),
                        decimal("10"),
                        decimal("8"),
                        decimal("1"),
                        decimal("900"),
                        decimal("12.5"),
                        decimal("700"),
                        decimal("650"),
                        1_772_000_000_002L
                )),
                List.of(position())
        );

        List<TradingEventEnvelope<?>> envelopes = mapper.futuresAccount(snapshot, CONTEXT);

        assertThat(envelopes).hasSize(2);
        BalanceUpdateEvent balance = balance(envelopes.getFirst());
        assertThat(balance.getAsset()).hasToString("USDT");
        assertThat(balance.getWalletBalance()).hasToString("1000");
        assertThat(balance.getCrossWalletBalance()).hasToString("900");
        assertThat(balance.getAvailableBalance()).hasToString("700");
        assertThat(balance.getUpdateReason()).hasToString("REST_SNAPSHOT");
        assertThat(balance.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_772_000_000_002L));
        assertThat(attributes(balance.getAttributes()))
                .containsEntry("snapshotType", "futures_account")
                .containsEntry("unrealizedProfit", "12.5")
                .containsEntry("canTrade", "true");

        PositionUpdateEvent position = position(envelopes.get(1));
        assertThat(position.getSymbol()).hasToString("BTCUSDT");
        assertThat(position.getPositionSide()).hasToString("LONG");
        assertThat(position.getPositionAmount()).hasToString("0.25");
        assertThat(position.getEntryPrice()).hasToString("50000");
        assertThat(position.getMarkPrice()).hasToString("50100");
        assertThat(position.getUnrealizedPnl()).hasToString("25");
        assertThat(position.getLeverage()).hasToString("20");
        assertThat(attributes(position.getAttributes()))
                .containsEntry("snapshotType", "futures_account")
                .containsEntry("breakEvenPrice", "50001")
                .containsEntry("marginAsset", "USDT");
    }

    @Test
    void maps_position_risk_snapshot_to_position_events() {
        List<TradingEventEnvelope<PositionUpdateEvent>> envelopes = mapper.futuresPositions(List.of(position()), CONTEXT);

        assertThat(envelopes).hasSize(1);
        PositionUpdateEvent value = envelopes.getFirst().value();
        assertThat(value.getEventTimeMicros()).isEqualTo(Instant.ofEpochMilli(1_772_000_000_003L));
        assertThat(attributes(value.getAttributes())).containsEntry("snapshotType", "position_risk");
    }

    @Test
    void maps_margin_account_snapshots_to_balance_events() {
        BinanceCrossMarginAccountSnapshot cross = new BinanceCrossMarginAccountSnapshot(
                true,
                true,
                decimal("1.5"),
                decimal("1.2"),
                decimal("0.2"),
                decimal("0.1"),
                decimal("0.1"),
                decimal("12000"),
                decimal("0"),
                true,
                true,
                true,
                "MARGIN_1",
                List.of(new BinanceMarginAssetBalance(
                        "BTC",
                        decimal("0.01"),
                        decimal("0.02"),
                        decimal("0.001"),
                        decimal("0.003"),
                        decimal("0.006")
                ))
        );
        BinanceIsolatedMarginAccountSnapshot isolated = new BinanceIsolatedMarginAccountSnapshot(List.of(
                new BinanceIsolatedMarginPairSnapshot(
                        new BinanceIsolatedMarginAssetBalance(
                                "ETH",
                                true,
                                decimal("0.1"),
                                decimal("1.0"),
                                decimal("0.01"),
                                decimal("0.2"),
                                decimal("0.69"),
                                decimal("0.03"),
                                true,
                                decimal("1.3")
                        ),
                        new BinanceIsolatedMarginAssetBalance(
                                "USDT",
                                true,
                                decimal("10"),
                                decimal("100"),
                                decimal("1"),
                                decimal("5"),
                                decimal("84"),
                                decimal("0.002"),
                                true,
                                decimal("105")
                        ),
                        "ETHUSDT",
                        true,
                        true,
                        decimal("1.8"),
                        "NORMAL",
                        decimal("1.1"),
                        decimal("3000"),
                        decimal("1200"),
                        decimal("1.05"),
                        true
                )
        ), decimal("0.03"), decimal("0.01"), decimal("0.02"));

        List<TradingEventEnvelope<BalanceUpdateEvent>> crossBalances = mapper.crossMarginAccount(cross, CONTEXT);
        List<TradingEventEnvelope<BalanceUpdateEvent>> isolatedBalances = mapper.isolatedMarginAccount(isolated, CONTEXT);

        assertThat(crossBalances).hasSize(1);
        BalanceUpdateEvent crossBalance = crossBalances.getFirst().value();
        assertThat(crossBalance.getAsset()).hasToString("BTC");
        assertThat(crossBalance.getWalletBalance()).hasToString("0.006");
        assertThat(crossBalance.getAvailableBalance()).hasToString("0.02");
        assertThat(attributes(crossBalance.getAttributes()))
                .containsEntry("snapshotType", "cross_margin_account")
                .containsEntry("borrowed", "0.01")
                .containsEntry("marginLevel", "1.5");

        assertThat(isolatedBalances).hasSize(2);
        BalanceUpdateEvent isolatedBalance = isolatedBalances.getFirst().value();
        assertThat(isolatedBalance.getAsset()).hasToString("ETH");
        assertThat(isolatedBalance.getWalletBalance()).hasToString("0.69");
        assertThat(isolatedBalance.getAvailableBalance()).hasToString("1");
        assertThat(attributes(isolatedBalance.getAttributes()))
                .containsEntry("snapshotType", "isolated_margin_account")
                .containsEntry("symbol", "ETHUSDT")
                .containsEntry("marginLevelStatus", "NORMAL");
    }

    @Test
    void maps_options_account_and_position_snapshots() {
        BinanceOptionsMarginAccountSnapshot account = new BinanceOptionsMarginAccountSnapshot(
                List.of(new BinanceOptionsAccountAsset(
                        "USDT",
                        decimal("10000475.51032086"),
                        decimal("10000371.61462086"),
                        decimal("9998120.00000000"),
                        decimal("32354.38562539"),
                        decimal("6089.28766956"),
                        decimal("16.10430000"),
                        decimal("10000475.51032086")
                )),
                List.of(new BinanceOptionsGreek(
                        "BTCUSDT",
                        decimal("-0.01304097"),
                        decimal("16.11648100"),
                        decimal("-0.00000124"),
                        decimal("-3.83444011")
                )),
                1_772_000_000_004L,
                true,
                true,
                true,
                false,
                0L
        );
        BinanceOptionsPositionSnapshot position = new BinanceOptionsPositionSnapshot(
                "BTC-251123-126000-C",
                "SHORT",
                decimal("-0.1000"),
                decimal("1200.00000000"),
                decimal("-120.00000000"),
                decimal("16.10430000"),
                decimal("1210.00000000"),
                decimal("126000"),
                1_764_000_000_000L,
                2,
                4,
                "CALL",
                "USDT",
                1_772_000_000_005L,
                decimal("0"),
                decimal("9.91")
        );

        List<TradingEventEnvelope<?>> accountEvents = mapper.optionsMarginAccount(account, OPTIONS_CONTEXT);
        List<TradingEventEnvelope<PositionUpdateEvent>> positionEvents = mapper.optionsPositions(List.of(position), OPTIONS_CONTEXT);

        assertThat(accountEvents).hasSize(2);
        BalanceUpdateEvent balance = balance(accountEvents.getFirst());
        assertThat(balance.getMarket()).hasToString("options");
        assertThat(balance.getAsset()).hasToString("USDT");
        assertThat(balance.getWalletBalance()).hasToString("10000475.51032086");
        assertThat(balance.getAvailableBalance()).hasToString("9998120");
        assertThat(attributes(balance.getAttributes()))
                .containsEntry("snapshotType", "options_margin_account")
                .containsEntry("equity", "10000371.61462086")
                .containsEntry("maintenanceMargin", "6089.28766956")
                .containsEntry("reduceOnly", "false");

        RiskUpdateEvent risk = risk(accountEvents.get(1));
        assertThat(risk.getRiskScope()).hasToString("UNDERLYING");
        assertThat(risk.getUnderlying()).hasToString("BTCUSDT");
        assertThat(risk.getDelta()).hasToString("-0.01304097");
        assertThat(risk.getGamma()).hasToString("-0.00000124");
        assertThat(risk.getTheta()).hasToString("16.116481");
        assertThat(risk.getVega()).hasToString("-3.83444011");
        assertThat(attributes(risk.getAttributes()))
                .containsEntry("source", "rest_snapshot")
                .containsEntry("snapshotType", "options_margin_account_greek");

        assertThat(positionEvents).hasSize(1);
        PositionUpdateEvent optionPosition = position(positionEvents.getFirst());
        assertThat(optionPosition.getMarket()).hasToString("options");
        assertThat(optionPosition.getSymbol()).hasToString("BTC-251123-126000-C");
        assertThat(optionPosition.getPositionSide()).hasToString("SHORT");
        assertThat(optionPosition.getPositionAmount()).hasToString("-0.1");
        assertThat(optionPosition.getEntryPrice()).hasToString("1200");
        assertThat(optionPosition.getMarkPrice()).hasToString("1210");
        assertThat(attributes(optionPosition.getAttributes()))
                .containsEntry("snapshotType", "options_position")
                .containsEntry("markValue", "-120")
                .containsEntry("optionSide", "CALL")
                .containsEntry("askQuantity", "9.91");
    }

    private BinanceFuturesPositionSnapshot position() {
        return new BinanceFuturesPositionSnapshot(
                "BTCUSDT",
                "LONG",
                decimal("0.250"),
                decimal("50000.00"),
                decimal("50001.00"),
                decimal("50100.00"),
                decimal("25.00"),
                decimal("45000.00"),
                20,
                decimal("10"),
                "isolated",
                true,
                false,
                decimal("1000"),
                decimal("900"),
                decimal("12525"),
                "USDT",
                decimal("10"),
                decimal("2"),
                decimal("8"),
                decimal("1"),
                2,
                decimal("0"),
                decimal("0"),
                1_772_000_000_003L
        );
    }

    private BalanceUpdateEvent balance(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.BALANCE_UPDATE);
        assertThat(envelope.value()).isInstanceOf(BalanceUpdateEvent.class);
        return (BalanceUpdateEvent) envelope.value();
    }

    private PositionUpdateEvent position(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.POSITION_UPDATE);
        assertThat(envelope.value()).isInstanceOf(PositionUpdateEvent.class);
        return (PositionUpdateEvent) envelope.value();
    }

    private RiskUpdateEvent risk(TradingEventEnvelope<?> envelope) {
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.RISK_UPDATE);
        assertThat(envelope.value()).isInstanceOf(RiskUpdateEvent.class);
        return (RiskUpdateEvent) envelope.value();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private Map<String, String> attributes(Map<CharSequence, CharSequence> attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        attributes.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }
}
