package io.github.manu.events;

import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.events.v1.StrategyLifecycleEvent;
import io.github.manu.events.v1.StrategySignalEvent;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import java.util.Arrays;
import java.util.Optional;

public enum TradingEventType {
    MARKET_DATA(TradingEventSchema.MARKET_DATA, MarketDataEvent.class, "market-data"),
    ORDER_COMMAND(TradingEventSchema.ORDER_COMMAND, OrderCommandEvent.class, "order-command"),
    ORDER_RESULT(TradingEventSchema.ORDER_RESULT, OrderResultEvent.class, "order-result"),
    EXECUTION_REPORT(TradingEventSchema.EXECUTION_REPORT, ExecutionReportEvent.class, "execution-report"),
    BALANCE_UPDATE(TradingEventSchema.BALANCE_UPDATE, BalanceUpdateEvent.class, "balance-update"),
    POSITION_UPDATE(TradingEventSchema.POSITION_UPDATE, PositionUpdateEvent.class, "position-update"),
    RISK_UPDATE(TradingEventSchema.RISK_UPDATE, RiskUpdateEvent.class, "risk-update"),
    RISK_DECISION(TradingEventSchema.RISK_DECISION, RiskDecisionEvent.class, "risk-decision"),
    INTERVENTION_ACKNOWLEDGEMENT(
            TradingEventSchema.INTERVENTION_ACKNOWLEDGEMENT,
            InterventionAcknowledgementEvent.class,
            "intervention-acknowledgement"
    ),
    REMEDIATION_DECISION(
            TradingEventSchema.REMEDIATION_DECISION,
            RemediationDecisionEvent.class,
            "remediation-decision"
    ),
    STRATEGY_SIGNAL(TradingEventSchema.STRATEGY_SIGNAL, StrategySignalEvent.class, "strategy-signal"),
    STRATEGY_LIFECYCLE(
            TradingEventSchema.STRATEGY_LIFECYCLE,
            StrategyLifecycleEvent.class,
            "strategy-lifecycle"
    ),
    CONFIG_CHANGE(TradingEventSchema.CONFIG_CHANGE, ConfigChangeEvent.class, "config-change");

    private static final String TOPIC_PREFIX = "trading.v1.";
    private static final String DEAD_LETTER_SUFFIX = ".dlq";

    private final TradingEventSchema schema;
    private final Class<? extends SpecificRecord> recordClass;
    private final TradingEventRoute route;

    TradingEventType(
            TradingEventSchema schema,
            Class<? extends SpecificRecord> recordClass,
            String topicName
    ) {
        this.schema = schema;
        this.recordClass = recordClass;
        String topic = TOPIC_PREFIX + topicName;
        this.route = new TradingEventRoute(topic, topic + "-key", topic + "-value", topic + DEAD_LETTER_SUFFIX);
    }

    public TradingEventSchema schema() {
        return schema;
    }

    public Schema avroSchema() {
        return schema.load();
    }

    public Schema keySchema() {
        return TradingEventSchemas.load(TradingEventSchemas.KEY_SCHEMA_FILE);
    }

    public Class<? extends SpecificRecord> recordClass() {
        return recordClass;
    }

    public TradingEventRoute route() {
        return route;
    }

    public static Optional<TradingEventType> fromRecordClass(Class<? extends SpecificRecord> recordClass) {
        return Arrays.stream(values())
                .filter(type -> type.recordClass.equals(recordClass))
                .findFirst();
    }
}
