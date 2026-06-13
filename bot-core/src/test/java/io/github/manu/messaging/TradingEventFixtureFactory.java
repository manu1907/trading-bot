package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.events.v1.MarketDataEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.events.v1.StrategyLifecycleEvent;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.specific.SpecificRecord;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class TradingEventFixtureFactory {

    private static final Instant TIMESTAMP = Instant.parse("2026-05-23T11:00:00Z");
    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usdm_futures";
    private static final String SYMBOL = "BTCUSDT";
    private static final String CLIENT_ORDER_ID = "tb-lfa-001";

    private TradingEventFixtureFactory() {
    }

    static Map<TradingEventType, TradingEventEnvelope<? extends SpecificRecord>> allEnvelopes() {
        Map<TradingEventType, TradingEventEnvelope<? extends SpecificRecord>> envelopes =
                new EnumMap<>(TradingEventType.class);
        envelopes.put(TradingEventType.MARKET_DATA, marketData());
        envelopes.put(TradingEventType.ORDER_COMMAND, orderCommand());
        envelopes.put(TradingEventType.ORDER_RESULT, orderResult());
        envelopes.put(TradingEventType.EXECUTION_REPORT, executionReport());
        envelopes.put(TradingEventType.BALANCE_UPDATE, balanceUpdate());
        envelopes.put(TradingEventType.POSITION_UPDATE, positionUpdate());
        envelopes.put(TradingEventType.RISK_UPDATE, riskUpdate());
        envelopes.put(TradingEventType.RISK_DECISION, riskDecision());
        envelopes.put(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT, interventionAcknowledgement());
        envelopes.put(TradingEventType.REMEDIATION_DECISION, remediationDecision());
        envelopes.put(TradingEventType.STRATEGY_SIGNAL, strategySignal());
        envelopes.put(TradingEventType.STRATEGY_LIFECYCLE, strategyLifecycle());
        envelopes.put(TradingEventType.CONFIG_CHANGE, configChange());
        return Map.copyOf(envelopes);
    }

    static TradingEventEnvelope<ConfigChangeEvent> configChange() {
        TradingEventKey key = TradingEventKeys.config(
                TradingEventType.CONFIG_CHANGE,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                "/providers/binance/environments/demo/accounts/main/enabled"
        );
        ConfigChangeEvent event = ConfigChangeEvent.newBuilder()
                .setEventId("evt-config")
                .setSchemaVersion(1)
                .setChangeId("cfg-001")
                .setSource(ConfigChangeSource.RUNTIME_FILE)
                .setProfile("live")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setPath("/providers/binance/environments/demo/accounts/main/enabled")
                .setOldValue("false")
                .setNewValue("true")
                .setApplied(true)
                .setRejectedReason(null)
                .setChangedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.CONFIG_CHANGE, key, event);
    }

    private static TradingEventEnvelope<MarketDataEvent> marketData() {
        TradingEventKey key = TradingEventKeys.symbol(
                TradingEventType.MARKET_DATA,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL
        );
        MarketDataEvent event = MarketDataEvent.newBuilder()
                .setEventId("evt-market")
                .setSchemaVersion(1)
                .setEventType(MarketDataEventType.TRADE)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setOccurredAtMicros(TIMESTAMP)
                .setReceivedAtMicros(TIMESTAMP)
                .setExchangeSequence(1001L)
                .setTradeId("trade-001")
                .setSide("BUY")
                .setPrice("50000.00")
                .setQuantity("0.001")
                .setBids(List.of())
                .setAsks(List.of())
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.MARKET_DATA, key, event);
    }

    private static TradingEventEnvelope<OrderCommandEvent> orderCommand() {
        TradingEventKey key = orderKey(TradingEventType.ORDER_COMMAND);
        OrderCommandEvent event = OrderCommandEvent.newBuilder()
                .setEventId("evt-command")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.00")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId(CLIENT_ORDER_ID)
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, key, event);
    }

    private static TradingEventEnvelope<OrderResultEvent> orderResult() {
        TradingEventKey key = orderKey(TradingEventType.ORDER_RESULT);
        OrderResultEvent event = OrderResultEvent.newBuilder()
                .setEventId("evt-result")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(CLIENT_ORDER_ID)
                .setExchangeOrderId("123456")
                .setStatus(OrderResultStatus.ACCEPTED)
                .setExchangeStatus("NEW")
                .setPrice("50000.00")
                .setOriginalQuantity("0.001")
                .setExecutedQuantity("0")
                .setAveragePrice(null)
                .setCumulativeQuote("0")
                .setExchangeTransactTimeMicros(TIMESTAMP)
                .setObservedAtMicros(TIMESTAMP)
                .setRejectCode(null)
                .setRejectMessage(null)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.ORDER_RESULT, key, event);
    }

    private static TradingEventEnvelope<ExecutionReportEvent> executionReport() {
        TradingEventKey key = orderKey(TradingEventType.EXECUTION_REPORT);
        ExecutionReportEvent event = ExecutionReportEvent.newBuilder()
                .setEventId("evt-exec")
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(CLIENT_ORDER_ID)
                .setExchangeOrderId("123456")
                .setExecutionId("exec-001")
                .setTradeId("trade-001")
                .setSide("BUY")
                .setOrderType("LIMIT")
                .setOrderStatus("PARTIALLY_FILLED")
                .setExecutionType("TRADE")
                .setLastExecutedQuantity("0.001")
                .setLastExecutedPrice("50000.00")
                .setCumulativeFilledQuantity("0.001")
                .setCumulativeQuoteQuantity("50.00")
                .setCommissionAsset("USDT")
                .setCommissionAmount("0.02")
                .setMaker(true)
                .setEventTimeMicros(TIMESTAMP)
                .setTransactionTimeMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.EXECUTION_REPORT, key, event);
    }

    private static TradingEventEnvelope<InterventionAcknowledgementEvent> interventionAcknowledgement() {
        TradingEventKey key = orderKey(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        InterventionAcknowledgementEvent event = InterventionAcknowledgementEvent.newBuilder()
                .setEventId("evt-intervention-ack")
                .setSchemaVersion(1)
                .setAcknowledgementId("ack-001")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(CLIENT_ORDER_ID)
                .setInterventionReason("external_order_observed")
                .setAcknowledgedBy("operator")
                .setAcknowledgementReason("manual review accepted")
                .setAcknowledgedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT, key, event);
    }

    private static TradingEventEnvelope<RemediationDecisionEvent> remediationDecision() {
        TradingEventKey key = orderKey(TradingEventType.REMEDIATION_DECISION);
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId("evt-remediation")
                .setSchemaVersion(1)
                .setRemediationId("remediation-001")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setScope("ORDER")
                .setAction("OPERATOR_REVIEW")
                .setClientOrderId(CLIENT_ORDER_ID)
                .setPositionSide(null)
                .setInterventionReason("external_order_observed")
                .setReasons(List.of("intervention:external_order_observed"))
                .setDecidedBy("operator")
                .setDecisionReason("reviewed current projection")
                .setDecidedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.REMEDIATION_DECISION, key, event);
    }

    private static TradingEventEnvelope<BalanceUpdateEvent> balanceUpdate() {
        TradingEventKey key = TradingEventKeys.account(TradingEventType.BALANCE_UPDATE, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET);
        BalanceUpdateEvent event = BalanceUpdateEvent.newBuilder()
                .setEventId("evt-balance")
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setAsset("USDT")
                .setWalletBalance("1000.00")
                .setCrossWalletBalance("1000.00")
                .setAvailableBalance("990.00")
                .setBalanceDelta("10.00")
                .setUpdateReason("ORDER")
                .setEventTimeMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.BALANCE_UPDATE, key, event);
    }

    private static TradingEventEnvelope<PositionUpdateEvent> positionUpdate() {
        TradingEventKey key = TradingEventKeys.symbol(
                TradingEventType.POSITION_UPDATE,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL
        );
        PositionUpdateEvent event = PositionUpdateEvent.newBuilder()
                .setEventId("evt-position")
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setPositionSide("LONG")
                .setPositionAmount("0.001")
                .setEntryPrice("50000.00")
                .setMarkPrice("50010.00")
                .setLiquidationPrice("42000.00")
                .setUnrealizedPnl("0.01")
                .setLeverage("5")
                .setMarginType("cross")
                .setIsolatedMargin(null)
                .setEventTimeMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.POSITION_UPDATE, key, event);
    }

    private static TradingEventEnvelope<RiskDecisionEvent> riskDecision() {
        TradingEventKey key = orderKey(TradingEventType.RISK_DECISION);
        RiskDecisionEvent event = RiskDecisionEvent.newBuilder()
                .setEventId("evt-risk")
                .setSchemaVersion(1)
                .setDecisionId("risk-001")
                .setCommandId("cmd-001")
                .setSignalId("sig-001")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setDecision(RiskDecision.APPROVED)
                .setReasons(List.of("within limits"))
                .setMaxQuantity("0.002")
                .setMaxNotional("100.00")
                .setDecidedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.RISK_DECISION, key, event);
    }

    private static TradingEventEnvelope<RiskUpdateEvent> riskUpdate() {
        TradingEventKey key = TradingEventKeys.symbol(
                TradingEventType.RISK_UPDATE,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL
        );
        RiskUpdateEvent event = RiskUpdateEvent.newBuilder()
                .setEventId("evt-risk-update")
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setRiskScope("UNDERLYING")
                .setSymbol(SYMBOL)
                .setUnderlying(SYMBOL)
                .setRiskLevel(null)
                .setDelta("-0.01304097")
                .setGamma("-0.00000124")
                .setTheta("16.11648100")
                .setVega("-3.83444011")
                .setMarginBalance(null)
                .setMaintenanceMargin(null)
                .setEventTimeMicros(TIMESTAMP)
                .setTransactionTimeMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.RISK_UPDATE, key, event);
    }

    private static TradingEventEnvelope<StrategySignalEvent> strategySignal() {
        TradingEventKey key = TradingEventKeys.strategy(TradingEventType.STRATEGY_SIGNAL, "lfa");
        StrategySignalEvent event = StrategySignalEvent.newBuilder()
                .setEventId("evt-signal")
                .setSchemaVersion(1)
                .setSignalId("sig-001")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setSignalType(StrategySignalType.ENTER_LONG)
                .setConfidence(0.82)
                .setTargetQuantity("0.001")
                .setTargetNotional("50.00")
                .setLimitPrice("50000.00")
                .setStopPrice(null)
                .setEmittedAtMicros(TIMESTAMP)
                .setFeatures(Map.of("fragility", "0.82"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.STRATEGY_SIGNAL, key, event);
    }

    private static TradingEventEnvelope<StrategyLifecycleEvent> strategyLifecycle() {
        String lifecycleId = "lfa/" + PROVIDER + "/" + ENVIRONMENT + "/" + ACCOUNT + "/" + MARKET;
        TradingEventKey key = TradingEventKeys.strategy(TradingEventType.STRATEGY_LIFECYCLE, lifecycleId);
        StrategyLifecycleEvent event = StrategyLifecycleEvent.newBuilder()
                .setEventId("evt-strategy-lifecycle")
                .setSchemaVersion(1)
                .setLifecycleId(lifecycleId)
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setPreviousLifecycleState("PAUSED")
                .setLifecycleState("ACTIVE")
                .setChangedBy("operator")
                .setReason("fixture transition")
                .setChangedAtMicros(TIMESTAMP)
                .setAttributes(Map.of("source", "fixture"))
                .build();
        return TradingEventEnvelope.of(TradingEventType.STRATEGY_LIFECYCLE, key, event);
    }

    private static TradingEventKey orderKey(TradingEventType eventType) {
        return TradingEventKeys.order(
                eventType,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL,
                CLIENT_ORDER_ID
        );
    }
}
