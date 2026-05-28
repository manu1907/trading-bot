package io.github.manu.projection;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingStateProjectionTest {

    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "options";
    private static final String SYMBOL = "BTC-251123-126000-C";

    private final TradingStateProjection projection = new TradingStateProjection();

    @Test
    void projects_balance_position_and_risk_state() {
        ProjectionUpdate balanceUpdate = projection.apply(balance("evt-balance", "1000", "950", timestamp(10)));
        ProjectionUpdate positionUpdate = projection.apply(position("evt-position", "-0.10", timestamp(11)));
        ProjectionUpdate riskUpdate = projection.apply(risk("evt-risk", "-0.01304097", timestamp(12)));

        assertThat(balanceUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(positionUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(riskUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.balance(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "USDT"))
                .get()
                .satisfies(balance -> {
                    assertThat(balance.walletBalance()).isEqualTo("1000");
                    assertThat(balance.availableBalance()).isEqualTo("950");
                    assertThat(balance.updatedAt()).isEqualTo(timestamp(10));
                });
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("-0.10");
                    assertThat(position.open()).isTrue();
                    assertThat(position.updateSource()).isEqualTo("POSITION_UPDATE");
                    assertThat(position.externalIntervention()).isFalse();
                    assertThat(position.interventionReason()).isNull();
                    assertThat(position.updatedAt()).isEqualTo(timestamp(11));
                });
        assertThat(projection.risk(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "UNDERLYING", "BTCUSDT"))
                .get()
                .satisfies(risk -> {
                    assertThat(risk.delta()).isEqualTo("-0.01304097");
                    assertThat(risk.updatedAt()).isEqualTo(timestamp(12));
                });
        assertThat(projection.hasOpenPositions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void deduplicates_event_ids_and_rejects_stale_entity_updates() {
        projection.apply(position("evt-position-new", "-0.10", timestamp(20)));

        ProjectionUpdate duplicate = projection.apply(position("evt-position-new", "-0.20", timestamp(21)));
        ProjectionUpdate stale = projection.apply(position("evt-position-old", "-0.30", timestamp(19)));

        assertThat(duplicate.status()).isEqualTo(ProjectionUpdateStatus.DUPLICATE);
        assertThat(stale.status()).isEqualTo(ProjectionUpdateStatus.STALE);
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("-0.10");
                    assertThat(position.eventId()).isEqualTo("evt-position-new");
                    assertThat(position.updatedAt()).isEqualTo(timestamp(20));
                });
    }

    @Test
    void marks_position_amount_change_without_managed_order_for_review() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(20), Map.of("rawEventType", "ACCOUNT_UPDATE")));

        ProjectionUpdate update = projection.apply(position(
                "evt-position-change",
                "0",
                timestamp(21),
                Map.of("rawEventType", "ACCOUNT_UPDATE")
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("0");
                    assertThat(position.updateSource()).isEqualTo("USER_DATA");
                    assertThat(position.externalIntervention()).isTrue();
                    assertThat(position.interventionReason()).isEqualTo("external_position_change");
                });
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.externalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEqualTo(1);
        assertThat(projection.externalPositionInterventionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(position -> {
                    assertThat(position.symbol()).isEqualTo(SYMBOL);
                    assertThat(position.positionSide()).isEqualTo("SHORT");
                    assertThat(position.interventionReason()).isEqualTo("external_position_change");
                });
    }

    @Test
    void does_not_mark_position_amount_change_when_symbol_has_managed_order() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(20), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(21)));

        ProjectionUpdate update = projection.apply(position(
                "evt-position-fill",
                "-0.15",
                timestamp(22),
                Map.of("rawEventType", "ACCOUNT_UPDATE")
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("-0.15");
                    assertThat(position.externalIntervention()).isFalse();
                    assertThat(position.interventionReason()).isNull();
                });
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
    }

    @Test
    void projects_order_results_and_newer_execution_reports_by_order_key() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(30)));

        ProjectionUpdate executionUpdate = projection.apply(executionReport("evt-exec", "PARTIALLY_FILLED", "0.05", timestamp(31)));
        ProjectionUpdate staleOrder = projection.apply(orderResult("evt-order-stale", OrderResultStatus.CANCELED, "CANCELED", timestamp(29)));

        assertThat(executionUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(staleOrder.status()).isEqualTo(ProjectionUpdateStatus.STALE);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.updateSource()).isEqualTo("USER_DATA");
                    assertThat(order.status()).isEqualTo("PARTIALLY_FILLED");
                    assertThat(order.executedQuantity()).isEqualTo("0.05");
                    assertThat(order.eventId()).isEqualTo("evt-exec");
                    assertThat(order.updatedAt()).isEqualTo(timestamp(31));
                });
    }

    @Test
    void marks_user_data_order_without_known_bot_command_as_external_intervention() {
        ProjectionUpdate update = projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isFalse();
                    assertThat(order.externalIntervention()).isTrue();
                    assertThat(order.interventionReason()).isEqualTo("external_order_observed");
                    assertThat(order.updateSource()).isEqualTo("USER_DATA");
                    assertThat(order.executionType()).isEqualTo("TRADE");
        });
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.externalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEqualTo(1);
        assertThat(projection.externalOrderInterventionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.symbol()).isEqualTo(SYMBOL);
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.interventionReason()).isEqualTo("external_order_observed");
                });
    }

    @Test
    void marks_unplanned_cancel_or_modify_of_managed_order_for_review() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(50)));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-cancel",
                "CANCELED",
                "CANCELED",
                "0",
                timestamp(51),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isTrue();
                    assertThat(order.interventionReason()).isEqualTo("unplanned_managed_order_change");
                    assertThat(order.commandId()).isEqualTo("cmd-1");
                });
    }

    @Test
    void acknowledgement_clears_matching_order_intervention() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        ProjectionUpdate acknowledgementUpdate = projection.apply(interventionAcknowledgement(
                "evt-ack",
                "external_order_observed",
                timestamp(41)
        ));

        assertThat(acknowledgementUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.interventionReason()).isNull();
                    assertThat(order.updateSource()).isEqualTo("INTERVENTION_ACKNOWLEDGEMENT");
                    assertThat(order.eventId()).isEqualTo("evt-ack");
                });
    }

    @Test
    void acknowledgement_with_wrong_reason_does_not_clear_intervention() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        ProjectionUpdate acknowledgementUpdate = projection.apply(interventionAcknowledgement(
                "evt-ack",
                "unplanned_managed_order_change",
                timestamp(41)
        ));

        assertThat(acknowledgementUpdate.status()).isEqualTo(ProjectionUpdateStatus.IGNORED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void can_be_used_as_event_handler_for_journal_replay() {
        projection.handle(balance("evt-balance", "1000", "950", timestamp(10))).join();

        assertThat(projection.balance(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "USDT")).isPresent();
    }

    @Test
    void snapshots_and_restores_projection_state() {
        projection.apply(balance("evt-balance", "1000", "950", timestamp(10)));
        projection.apply(position("evt-position", "-0.10", timestamp(11)));
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(12)));
        projection.apply(risk("evt-risk", "-0.01304097", timestamp(13)));

        TradingStateSnapshot snapshot = projection.snapshot();
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(snapshot);

        assertThat(restored.balance(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "USDT"))
                .get()
                .satisfies(balance -> assertThat(balance.walletBalance()).isEqualTo("1000"));
        assertThat(restored.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> assertThat(position.positionAmount()).isEqualTo("-0.10"));
        assertThat(restored.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> assertThat(order.exchangeStatus()).isEqualTo("NEW"));
        assertThat(restored.risk(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "UNDERLYING", "BTCUSDT"))
                .get()
                .satisfies(risk -> assertThat(risk.delta()).isEqualTo("-0.01304097"));
        assertThat(restored.apply(balance("evt-balance", "1001", "951", timestamp(14))).status())
                .isEqualTo(ProjectionUpdateStatus.DUPLICATE);
    }

    @Test
    void bounds_event_id_deduplication_window() {
        TradingStateProjection bounded = new TradingStateProjection(1);

        bounded.apply(balance("evt-1", "1000", "950", timestamp(10)));
        bounded.apply(balance("evt-2", "1001", "951", timestamp(11)));
        ProjectionUpdate evictedDuplicate = bounded.apply(balance("evt-1", "1002", "952", timestamp(12)));

        assertThat(evictedDuplicate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(bounded.balance(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "USDT"))
                .get()
                .satisfies(balance -> {
                    assertThat(balance.walletBalance()).isEqualTo("1002");
                    assertThat(balance.eventId()).isEqualTo("evt-1");
                });
    }

    private TradingEventEnvelope<BalanceUpdateEvent> balance(
            String eventId,
            String walletBalance,
            String availableBalance,
            Instant eventTime
    ) {
        BalanceUpdateEvent event = BalanceUpdateEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setAsset("USDT")
                .setWalletBalance(walletBalance)
                .setAvailableBalance(availableBalance)
                .setUpdateReason("REST_SNAPSHOT")
                .setEventTimeMicros(eventTime)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.BALANCE_UPDATE,
                TradingEventKeys.account(TradingEventType.BALANCE_UPDATE, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET),
                event
        );
    }

    private TradingEventEnvelope<PositionUpdateEvent> position(String eventId, String quantity, Instant eventTime) {
        return position(eventId, quantity, eventTime, Map.of());
    }

    private TradingEventEnvelope<PositionUpdateEvent> position(
            String eventId,
            String quantity,
            Instant eventTime,
            Map<CharSequence, CharSequence> attributes
    ) {
        PositionUpdateEvent event = PositionUpdateEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setPositionSide("SHORT")
                .setPositionAmount(quantity)
                .setEntryPrice("1200")
                .setMarkPrice("1210")
                .setUnrealizedPnl("16.10")
                .setEventTimeMicros(eventTime)
                .setAttributes(attributes)
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.POSITION_UPDATE,
                TradingEventKeys.symbol(TradingEventType.POSITION_UPDATE, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL),
                event
        );
    }

    private TradingEventEnvelope<RiskUpdateEvent> risk(String eventId, String delta, Instant eventTime) {
        RiskUpdateEvent event = RiskUpdateEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setRiskScope("UNDERLYING")
                .setSymbol("BTCUSDT")
                .setUnderlying("BTCUSDT")
                .setDelta(delta)
                .setGamma("-0.00000124")
                .setTheta("16.116481")
                .setVega("-3.83444011")
                .setEventTimeMicros(eventTime)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.RISK_UPDATE,
                TradingEventKeys.symbol(TradingEventType.RISK_UPDATE, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "BTCUSDT"),
                event
        );
    }

    private TradingEventEnvelope<OrderResultEvent> orderResult(
            String eventId,
            OrderResultStatus status,
            String exchangeStatus,
            Instant observedAt
    ) {
        OrderResultEvent event = OrderResultEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setCommandId("cmd-1")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId("client-1")
                .setExchangeOrderId("12345")
                .setStatus(status)
                .setExchangeStatus(exchangeStatus)
                .setPrice("100")
                .setOriginalQuantity("0.10")
                .setExecutedQuantity("0")
                .setObservedAtMicros(observedAt)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(TradingEventType.ORDER_RESULT, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"),
                event
        );
    }

    private TradingEventEnvelope<ExecutionReportEvent> executionReport(
            String eventId,
            String orderStatus,
            String cumulativeFilled,
            Instant eventTime
    ) {
        return executionReport(eventId, orderStatus, "TRADE", cumulativeFilled, eventTime, Map.of());
    }

    private TradingEventEnvelope<ExecutionReportEvent> executionReport(
            String eventId,
            String orderStatus,
            String executionType,
            String cumulativeFilled,
            Instant eventTime,
            Map<CharSequence, CharSequence> attributes
    ) {
        ExecutionReportEvent event = ExecutionReportEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId("client-1")
                .setExchangeOrderId("12345")
                .setSide("BUY")
                .setOrderType("LIMIT")
                .setOrderStatus(orderStatus)
                .setExecutionType(executionType)
                .setLastExecutedQuantity("0.05")
                .setLastExecutedPrice("100")
                .setCumulativeFilledQuantity(cumulativeFilled)
                .setEventTimeMicros(eventTime)
                .setAttributes(attributes)
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.EXECUTION_REPORT,
                TradingEventKeys.order(
                        TradingEventType.EXECUTION_REPORT,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        "client-1"
                ),
                event
        );
    }

    private TradingEventEnvelope<InterventionAcknowledgementEvent> interventionAcknowledgement(
            String eventId,
            String interventionReason,
            Instant acknowledgedAt
    ) {
        InterventionAcknowledgementEvent event = InterventionAcknowledgementEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setAcknowledgementId("ack-" + eventId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId("client-1")
                .setInterventionReason(interventionReason)
                .setAcknowledgedBy("operator")
                .setAcknowledgementReason("reviewed")
                .setAcknowledgedAtMicros(acknowledgedAt)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                TradingEventKeys.order(
                        TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        "client-1"
                ),
                event
        );
    }

    private Instant timestamp(long second) {
        return Instant.parse("2026-05-26T00:00:" + second + "Z");
    }
}
