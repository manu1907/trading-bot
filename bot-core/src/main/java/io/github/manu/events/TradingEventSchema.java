package io.github.manu.events;

import org.apache.avro.Schema;

public enum TradingEventSchema {
    MARKET_DATA("MarketDataEvent.avsc"),
    ORDER_COMMAND("OrderCommandEvent.avsc"),
    ORDER_RESULT("OrderResultEvent.avsc"),
    EXECUTION_REPORT("ExecutionReportEvent.avsc"),
    BALANCE_UPDATE("BalanceUpdateEvent.avsc"),
    POSITION_UPDATE("PositionUpdateEvent.avsc"),
    RISK_UPDATE("RiskUpdateEvent.avsc"),
    RISK_DECISION("RiskDecisionEvent.avsc"),
    INTERVENTION_ACKNOWLEDGEMENT("InterventionAcknowledgementEvent.avsc"),
    REMEDIATION_DECISION("RemediationDecisionEvent.avsc"),
    STRATEGY_SIGNAL("StrategySignalEvent.avsc"),
    CONFIG_CHANGE("ConfigChangeEvent.avsc");

    private final String fileName;

    TradingEventSchema(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    public Schema load() {
        return TradingEventSchemas.load(fileName);
    }
}
