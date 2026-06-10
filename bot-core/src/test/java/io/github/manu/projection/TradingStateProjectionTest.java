package io.github.manu.projection;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.OrderCommandAction;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
        ProjectionUpdate positionUpdate = projection.apply(position(
                "evt-position",
                "-0.10",
                timestamp(11),
                Map.of("dualSidePosition", "true")
        ));
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
                    assertThat(position.positionMode()).isEqualTo("HEDGE");
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
                    assertThat(risk.maxMarginBalance()).isNull();
                    assertThat(risk.updatedAt()).isEqualTo(timestamp(12));
                });
        assertThat(projection.hasOpenPositions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void tracks_risk_margin_balance_high_watermark() {
        projection.apply(risk("evt-risk-1", "-0.01", timestamp(10), "1000"));
        projection.apply(risk("evt-risk-2", "-0.02", timestamp(11), "900"));
        projection.apply(risk("evt-risk-3", "-0.03", timestamp(12), "1100"));

        assertThat(projection.risk(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "UNDERLYING", "BTCUSDT"))
                .get()
                .satisfies(risk -> {
                    assertThat(risk.marginBalance()).isEqualTo("1100");
                    assertThat(risk.maxMarginBalance()).isEqualTo("1100");
                });
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
    void marks_position_amount_change_when_managed_order_has_no_fill_evidence() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(20), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(21)));

        ProjectionUpdate update = projection.apply(position(
                "evt-position-change",
                "-0.15",
                timestamp(22),
                Map.of("rawEventType", "ACCOUNT_UPDATE")
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("-0.15");
                    assertThat(position.externalIntervention()).isTrue();
                    assertThat(position.interventionReason()).isEqualTo("external_position_change");
                });
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void does_not_mark_position_amount_change_when_recent_managed_fill_observed() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(20), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(21)));
        projection.apply(executionReport("evt-fill", "PARTIALLY_FILLED", "0.05", timestamp(22)));

        ProjectionUpdate update = projection.apply(position(
                "evt-position-fill",
                "-0.15",
                timestamp(23),
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
    void marks_position_amount_change_when_only_old_managed_fill_exists() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(20), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(21)));
        projection.apply(executionReport("evt-fill", "PARTIALLY_FILLED", "0.05", timestamp(22)));
        projection.apply(position("evt-position-fill", "-0.15", timestamp(23), Map.of("rawEventType", "ACCOUNT_UPDATE")));

        ProjectionUpdate update = projection.apply(position(
                "evt-position-manual-close",
                "0",
                timestamp(24),
                Map.of("rawEventType", "ACCOUNT_UPDATE")
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.positionAmount()).isEqualTo("0");
                    assertThat(position.externalIntervention()).isTrue();
                    assertThat(position.interventionReason()).isEqualTo("external_position_change");
                });
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
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
    void accumulates_daily_realized_pnl_from_execution_report_attributes() {
        ProjectionUpdate firstFill = projection.apply(executionReport(
                "evt-realized-1",
                "PARTIALLY_FILLED",
                "TRADE",
                "0.05",
                timestamp(31),
                Map.of("realizedProfit", "-2.50")
        ));
        ProjectionUpdate duplicateFill = projection.apply(executionReport(
                "evt-realized-1",
                "PARTIALLY_FILLED",
                "TRADE",
                "0.05",
                timestamp(31),
                Map.of("realizedProfit", "-2.50")
        ));
        ProjectionUpdate secondFill = projection.apply(executionReport(
                "evt-realized-2",
                "FILLED",
                "TRADE",
                "0.10",
                timestamp(32),
                Map.of("realizedPnl", "1.25")
        ));

        assertThat(firstFill.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(duplicateFill.status()).isEqualTo(ProjectionUpdateStatus.DUPLICATE);
        assertThat(secondFill.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.dailyRealizedPnl(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "2026-05-26"))
                .get()
                .satisfies(pnl -> {
                    assertThat(pnl.realizedPnl()).isEqualTo("-1.25");
                    assertThat(pnl.updatedAt()).isEqualTo(timestamp(32));
                    assertThat(pnl.eventId()).isEqualTo("evt-realized-2");
                });
    }

    @Test
    void projects_replayed_order_command_as_pending_order_identity() {
        ProjectionUpdate update = projection.apply(orderCommand("evt-command", timestamp(33)));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-1");
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.status()).isEqualTo("COMMAND_RECEIVED");
                    assertThat(order.updateSource()).isEqualTo("ORDER_COMMAND");
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.updatedAt()).isEqualTo(timestamp(33));
                });
        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.unresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEqualTo(1);
    }

    @Test
    void tracks_unresolved_order_command_until_newer_order_state_resolves_it() {
        projection.apply(orderCommand("evt-command", timestamp(33)));

        assertThat(projection.unresolvedOrderCommandStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.unresolvedCommand()).isTrue();
                });

        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.unresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isZero();
    }

    @Test
    void projects_replayed_target_order_command_against_target_order_identity() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        ProjectionUpdate commandUpdate = projection.apply(cancelOrderCommand(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                "client-1",
                null,
                timestamp(35)
        ));

        assertThat(commandUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "cancel-client-1")).isEmpty();
        assertThat(projection.unresolvedOrderCommandStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.executionType()).isEqualTo("CANCEL");
                    assertThat(order.unresolvedCommand()).isTrue();
                });

        projection.apply(orderResult(
                "evt-cancel-result",
                "cmd-cancel",
                "client-1",
                "12345",
                OrderResultStatus.CANCELED,
                "CANCELED",
                timestamp(36)
        ));

        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.status()).isEqualTo("CANCELED");
                });
    }

    @Test
    void projects_replayed_target_order_command_from_target_identity_attributes() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        ProjectionUpdate commandUpdate = projection.apply(cancelOrderCommandWithTargetAttributes(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                "client-1",
                null,
                timestamp(35)
        ));

        assertThat(commandUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "cancel-client-1")).isEmpty();
        assertThat(projection.unresolvedOrderCommandStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.executionType()).isEqualTo("CANCEL");
                    assertThat(order.unresolvedCommand()).isTrue();
                });
    }

    @Test
    void projects_replayed_target_order_command_by_exchange_order_id_when_known() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        projection.apply(cancelOrderCommand(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                null,
                "12345",
                timestamp(35)
        ));

        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "cancel-client-1")).isEmpty();
        assertThat(projection.unresolvedOrderCommandStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                });
    }

    @Test
    void projects_replayed_target_order_command_from_target_exchange_id_attribute_when_known() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        projection.apply(cancelOrderCommandWithTargetAttributes(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                null,
                "12345",
                timestamp(35)
        ));

        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "cancel-client-1")).isEmpty();
        assertThat(projection.unresolvedOrderCommandStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                });
    }

    @Test
    void resolves_gateway_failure_result_to_target_order_by_exchange_order_id() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));
        projection.apply(cancelOrderCommand(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                null,
                "12345",
                timestamp(35)
        ));

        ProjectionUpdate failureUpdate = projection.apply(gatewayFailureOrderResult(
                "evt-gateway-failure",
                "cmd-cancel",
                "cancel-client-1",
                "12345",
                timestamp(36)
        ));

        assertThat(failureUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "cancel-client-1")).isEmpty();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.status()).isEqualTo("UNKNOWN");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                    assertThat(order.updateSource()).isEqualTo("ORDER_RESULT");
                });
        assertThat(projection.hasUnknownOrderStatuses(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void planned_cancel_user_data_does_not_create_order_intervention() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));
        projection.apply(cancelOrderCommand(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                "client-1",
                null,
                timestamp(35)
        ));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-cancel-report",
                "CANCELED",
                "CANCELED",
                "0",
                timestamp(36),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.status()).isEqualTo("CANCELED");
                    assertThat(order.executionType()).isEqualTo("CANCELED");
                    assertThat(order.externalIntervention()).isFalse();
                });
    }

    @Test
    void planned_modify_user_data_does_not_create_order_intervention() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));
        projection.apply(modifyOrderCommand(
                "evt-modify-command",
                "cmd-modify",
                "modify-client-1",
                "client-1",
                null,
                timestamp(35)
        ));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-amend-report",
                "NEW",
                "AMENDMENT",
                "0",
                timestamp(36),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-modify");
                    assertThat(order.status()).isEqualTo("NEW");
                    assertThat(order.executionType()).isEqualTo("AMENDMENT");
                    assertThat(order.externalIntervention()).isFalse();
                });
    }

    @Test
    void repeated_user_data_for_already_projected_planned_cancel_does_not_create_intervention() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));
        projection.apply(cancelOrderCommand(
                "evt-cancel-command",
                "cmd-cancel",
                "cancel-client-1",
                "client-1",
                null,
                timestamp(35)
        ));
        projection.apply(orderResult(
                "evt-cancel-result",
                "cmd-cancel",
                "client-1",
                "12345",
                OrderResultStatus.CANCELED,
                "CANCELED",
                timestamp(36)
        ));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-cancel-report",
                "CANCELED",
                "CANCELED",
                "0",
                timestamp(37),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-cancel");
                    assertThat(order.status()).isEqualTo("CANCELED");
                    assertThat(order.executionType()).isEqualTo("CANCELED");
                    assertThat(order.externalIntervention()).isFalse();
                });
    }

    @Test
    void finds_projected_orders_by_command_id_for_restart_idempotency() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        assertThat(projection.ordersByCommandId(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "cmd-1"))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.commandId()).isEqualTo("cmd-1");
                });
        assertThat(projection.ordersByCommandId(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "missing-command")).isEmpty();
    }

    @Test
    void finds_projected_order_by_exchange_order_id_for_target_commands() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(34)));

        assertThat(projection.orderByExchangeOrderId(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "12345"))
                .get()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.exchangeOrderId()).isEqualTo("12345");
                });
        assertThat(projection.orderByExchangeOrderId(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "missing-order"))
                .isEmpty();
    }

    @Test
    void marks_rest_snapshot_order_without_known_bot_command_as_external_intervention() {
        ProjectionUpdate update = projection.apply(restSnapshotOrderResult(
                "evt-rest-order",
                "reconciliation:manual-client-1",
                "manual-client-1",
                "98765",
                OrderResultStatus.ACCEPTED,
                "NEW",
                timestamp(34)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "manual-client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isFalse();
                    assertThat(order.externalIntervention()).isTrue();
                    assertThat(order.interventionReason()).isEqualTo("external_order_observed");
                    assertThat(order.updateSource()).isEqualTo("REST_SNAPSHOT");
                });
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void terminal_no_fill_rest_snapshot_resolves_external_order_intervention() {
        projection.apply(restSnapshotOrderResult(
                "evt-rest-open",
                "reconciliation:manual-client-1",
                "manual-client-1",
                "98765",
                OrderResultStatus.ACCEPTED,
                "NEW",
                timestamp(34)
        ));

        ProjectionUpdate update = projection.apply(restSnapshotOrderResult(
                "evt-rest-canceled",
                "reconciliation:manual-client-1",
                "manual-client-1",
                "98765",
                OrderResultStatus.CANCELED,
                "CANCELED",
                timestamp(35)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "manual-client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isFalse();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.interventionReason()).isNull();
                    assertThat(order.status()).isEqualTo("CANCELED");
                    assertThat(order.updateSource()).isEqualTo("REST_SNAPSHOT");
                });
    }

    @Test
    void rest_snapshot_preserves_known_bot_command_identity() {
        projection.apply(orderCommand("evt-command", timestamp(33)));

        projection.apply(restSnapshotOrderResult(
                "evt-rest-order",
                "reconciliation:client-1",
                "client-1",
                "12345",
                OrderResultStatus.ACCEPTED,
                "NEW",
                timestamp(34)
        ));

        assertThat(projection.hasUnresolvedOrderCommands(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.commandId()).isEqualTo("cmd-1");
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.updateSource()).isEqualTo("REST_SNAPSHOT");
                    assertThat(order.status()).isEqualTo("ACCEPTED");
                });
    }

    @Test
    void tracks_unknown_order_status_until_newer_order_state_resolves_it() {
        projection.apply(orderResult("evt-unknown", OrderResultStatus.UNKNOWN, null, timestamp(35)));

        assertThat(projection.hasUnknownOrderStatuses(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.unknownOrderStatuses(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEqualTo(1);
        assertThat(projection.unknownOrderStatusStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.clientOrderId()).isEqualTo("client-1");
                    assertThat(order.unknownStatus()).isTrue();
                });

        projection.apply(executionReport("evt-resolved", "NEW", "0", timestamp(36)));

        assertThat(projection.hasUnknownOrderStatuses(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.unknownOrderStatuses(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isZero();
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
    void terminal_no_fill_user_data_resolves_external_order_intervention() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-external-canceled",
                "CANCELED",
                "CANCELED",
                "0",
                timestamp(41),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isFalse();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.interventionReason()).isNull();
                    assertThat(order.status()).isEqualTo("CANCELED");
                    assertThat(order.executedQuantity()).isEqualTo("0");
                });
    }

    @Test
    void terminal_user_data_with_fill_keeps_external_order_intervention() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        ProjectionUpdate update = projection.apply(executionReport(
                "evt-external-filled",
                "FILLED",
                "TRADE",
                "0.05",
                timestamp(41),
                Map.of()
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.externalOrderInterventionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.externalIntervention()).isTrue();
                    assertThat(order.interventionReason()).isEqualTo("external_order_observed");
                    assertThat(order.status()).isEqualTo("FILLED");
                    assertThat(order.executedQuantity()).isEqualTo("0.05");
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
    void terminal_no_fill_rest_snapshot_preserves_unplanned_managed_order_intervention() {
        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(50)));
        projection.apply(executionReport(
                "evt-cancel",
                "CANCELED",
                "CANCELED",
                "0",
                timestamp(51),
                Map.of()
        ));

        ProjectionUpdate update = projection.apply(restSnapshotOrderResult(
                "evt-rest-canceled",
                "reconciliation:client-1",
                "client-1",
                "12345",
                OrderResultStatus.CANCELED,
                "CANCELED",
                timestamp(52)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isTrue();
                    assertThat(order.interventionReason()).isEqualTo("unplanned_managed_order_change");
                    assertThat(order.status()).isEqualTo("CANCELED");
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
    void adoption_acknowledgement_transfers_external_order_to_bot_management() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));

        ProjectionUpdate acknowledgementUpdate = projection.apply(interventionAcknowledgement(
                "evt-adopt",
                "external_order_observed",
                timestamp(41),
                Map.of(
                        "adoption", "true",
                        "adoption_operation", "ADOPT_ORDER"
                )
        ));

        assertThat(acknowledgementUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalOrderInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.order(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"))
                .get()
                .satisfies(order -> {
                    assertThat(order.managedByBot()).isTrue();
                    assertThat(order.externalIntervention()).isFalse();
                    assertThat(order.interventionReason()).isNull();
                    assertThat(order.updateSource()).isEqualTo("INTERVENTION_ADOPTION");
                    assertThat(order.eventId()).isEqualTo("evt-adopt");
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
    void acknowledgement_clears_matching_position_intervention() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(40), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(position("evt-position-change", "0", timestamp(41), Map.of("rawEventType", "ACCOUNT_UPDATE")));

        ProjectionUpdate acknowledgementUpdate = projection.apply(positionInterventionAcknowledgement(
                "evt-position-ack",
                "external_position_change",
                "SHORT",
                timestamp(42)
        ));

        assertThat(acknowledgementUpdate.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.position(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "SHORT"))
                .get()
                .satisfies(position -> {
                    assertThat(position.externalIntervention()).isFalse();
                    assertThat(position.interventionReason()).isNull();
                    assertThat(position.updateSource()).isEqualTo("INTERVENTION_ACKNOWLEDGEMENT");
                    assertThat(position.eventId()).isEqualTo("evt-position-ack");
                });
    }

    @Test
    void position_acknowledgement_with_wrong_reason_does_not_clear_intervention() {
        projection.apply(position("evt-position-open", "-0.10", timestamp(40), Map.of("rawEventType", "ACCOUNT_UPDATE")));
        projection.apply(position("evt-position-change", "0", timestamp(41), Map.of("rawEventType", "ACCOUNT_UPDATE")));

        ProjectionUpdate acknowledgementUpdate = projection.apply(positionInterventionAcknowledgement(
                "evt-position-ack",
                "manual_position_close",
                "SHORT",
                timestamp(42)
        ));

        assertThat(acknowledgementUpdate.status()).isEqualTo(ProjectionUpdateStatus.IGNORED);
        assertThat(projection.hasExternalPositionInterventions(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
    }

    @Test
    void projects_manual_review_risk_decisions_for_operator_review() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(42)));

        ProjectionUpdate update = projection.apply(riskDecision(
                "evt-risk-decision-review",
                RiskDecision.MANUAL_REVIEW,
                timestamp(43)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.commandId()).isEqualTo("cmd-1");
                    assertThat(decision.signalId()).isEqualTo("sig-1");
                    assertThat(decision.strategyId()).isEqualTo("lfa");
                    assertThat(decision.decisionId()).isEqualTo("risk-decision:cmd-1");
                    assertThat(decision.reasons()).containsExactly("intervention:external_order");
                    assertThat(decision.attributes()).containsEntry("external_order_intervention_action", "MANUAL_REVIEW");
                    assertThat(decision.updatedAt()).isEqualTo(timestamp(43));
                });
    }

    @Test
    void non_manual_risk_decision_clears_pending_manual_review_for_same_command() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(42)));
        projection.apply(riskDecision("evt-risk-decision-review", RiskDecision.MANUAL_REVIEW, timestamp(43)));

        ProjectionUpdate update = projection.apply(riskDecision(
                "evt-risk-decision-approved",
                RiskDecision.APPROVED,
                timestamp(44)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEmpty();
    }

    @Test
    void resolved_intervention_hides_intervention_driven_manual_review_decision() {
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(40)));
        projection.apply(riskDecision("evt-risk-decision-review", RiskDecision.MANUAL_REVIEW, timestamp(41)));

        projection.apply(interventionAcknowledgement("evt-ack", "external_order_observed", timestamp(42)));

        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEmpty();
    }

    @Test
    void resolved_unknown_order_status_hides_unknown_status_manual_review_decision() {
        projection.apply(orderResult("evt-unknown", OrderResultStatus.UNKNOWN, null, timestamp(40)));
        projection.apply(riskDecision(
                "evt-risk-decision-review",
                RiskDecision.MANUAL_REVIEW,
                timestamp(41),
                List.of("order_status:unknown"),
                Map.of("unknown_order_status_action", "MANUAL_REVIEW")
        ));

        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> assertThat(decision.reasons()).containsExactly("order_status:unknown"));

        projection.apply(executionReport("evt-resolved", "NEW", "0", timestamp(42)));

        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEmpty();
    }

    @Test
    void resolved_order_command_hides_unresolved_command_manual_review_decision() {
        projection.apply(orderCommand("evt-command", timestamp(40)));
        projection.apply(riskDecision(
                "evt-risk-decision-review",
                RiskDecision.MANUAL_REVIEW,
                timestamp(41),
                List.of("order_command:unresolved"),
                Map.of("pending_order_command_action", "MANUAL_REVIEW")
        ));

        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> assertThat(decision.reasons()).containsExactly("order_command:unresolved"));

        projection.apply(orderResult("evt-order", OrderResultStatus.ACCEPTED, "NEW", timestamp(42)));

        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEmpty();
    }

    @Test
    void projects_remediation_decisions_for_operator_audit() {
        ProjectionUpdate update = projection.apply(remediationDecision(
                "evt-remediation-decision",
                "remediation-1",
                timestamp(52)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.remediationDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.remediationId()).isEqualTo("remediation-1");
                    assertThat(decision.scope()).isEqualTo("ORDER");
                    assertThat(decision.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(decision.clientOrderId()).isEqualTo("client-1");
                    assertThat(decision.interventionReason()).isEqualTo("external_order_observed");
                    assertThat(decision.reasons()).containsExactly("intervention:external_order_observed");
                    assertThat(decision.decidedBy()).isEqualTo("operator");
                    assertThat(decision.decisionReason()).isEqualTo("reviewed current projection");
                    assertThat(decision.attributes()).containsEntry("ticket", "ops-789");
                    assertThat(decision.updatedAt()).isEqualTo(timestamp(52));
                });
    }

    @Test
    void projects_pause_governance_from_pause_remediation_decisions() {
        projection.apply(pauseRemediationDecision(
                "evt-pause-symbol",
                "remediation-pause-symbol",
                "POSITION",
                "PAUSE_SYMBOL",
                SYMBOL,
                timestamp(52)
        ));
        projection.apply(pauseRemediationDecision(
                "evt-pause-account",
                "remediation-pause-account",
                "POSITION",
                "PAUSE_ACCOUNT",
                null,
                timestamp(53)
        ));

        assertThat(projection.pauseGovernanceStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .hasSize(2)
                .anySatisfy(pause -> {
                    assertThat(pause.pauseScope()).isEqualTo("ACCOUNT");
                    assertThat(pause.pauseTarget()).isEqualTo(ACCOUNT);
                    assertThat(pause.remediationId()).isEqualTo("remediation-pause-account");
                    assertThat(pause.active()).isTrue();
                })
                .anySatisfy(pause -> {
                    assertThat(pause.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(pause.pauseTarget()).isEqualTo(SYMBOL);
                    assertThat(pause.remediationId()).isEqualTo("remediation-pause-symbol");
                    assertThat(pause.reasons()).containsExactly("intervention:external_position_change");
                });
        assertThat(projection.accountPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isTrue();
        assertThat(projection.symbolPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL)).isTrue();
    }

    @Test
    void release_pause_decision_deactivates_matching_pause_governance() {
        projection.apply(pauseRemediationDecision(
                "evt-pause-symbol",
                "remediation-pause-symbol",
                "POSITION",
                "PAUSE_SYMBOL",
                SYMBOL,
                timestamp(52)
        ));

        projection.apply(pauseRemediationDecision(
                "evt-release-symbol-pause",
                "pause-release-symbol",
                "PAUSE_GOVERNANCE",
                "RELEASE_SYMBOL_PAUSE",
                SYMBOL,
                timestamp(53)
        ));

        assertThat(projection.pauseGovernanceStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(pause -> {
                    assertThat(pause.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(pause.pauseTarget()).isEqualTo(SYMBOL);
                    assertThat(pause.action()).isEqualTo("RELEASE_SYMBOL_PAUSE");
                    assertThat(pause.active()).isFalse();
                });
        assertThat(projection.symbolPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL)).isFalse();
    }

    @Test
    void expired_pause_governance_is_inactive_for_time_aware_lookup_helpers() {
        projection.apply(pauseRemediationDecision(
                "evt-pause-symbol",
                "remediation-pause-symbol",
                "POSITION",
                "PAUSE_SYMBOL",
                SYMBOL,
                timestamp(52),
                Map.of(
                        "source_recommendation",
                        "pause-governance-test",
                        "pause_expires_at",
                        timestamp(53).toString()
                )
        ));

        assertThat(projection.symbolPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, timestamp(52))).isTrue();
        assertThat(projection.symbolPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, timestamp(53))).isFalse();
        assertThat(projection.pauseGovernanceStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(pause -> {
                    assertThat(pause.expiresAt()).isEqualTo(timestamp(53));
                    assertThat(pause.expired(timestamp(52))).isFalse();
                    assertThat(pause.expired(timestamp(53))).isTrue();
                    assertThat(pause.effectiveActive(timestamp(53))).isFalse();
                });
    }

    @Test
    void ignores_inactive_pause_governance_for_pause_lookup_helpers() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.PauseGovernanceState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        "SYMBOL",
                        SYMBOL,
                        SYMBOL,
                        "remediation-pause-symbol",
                        "POSITION",
                        "PAUSE_SYMBOL",
                        "external_position_change",
                        List.of("intervention:external_position_change"),
                        "automated_policy",
                        "inactive release test",
                        Map.of(),
                        false,
                        timestamp(54),
                        "evt-pause-inactive"
                )),
                List.of()
        ));

        assertThat(projection.pauseGovernanceStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(pause -> assertThat(pause.active()).isFalse());
        assertThat(projection.accountPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isFalse();
        assertThat(projection.symbolPaused(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL)).isFalse();
    }

    @Test
    void remediation_decision_clears_matching_manual_review_decision() {
        projection.apply(orderResult("evt-unknown", OrderResultStatus.UNKNOWN, null, timestamp(40)));
        projection.apply(riskDecision(
                "evt-risk-decision-review",
                RiskDecision.MANUAL_REVIEW,
                timestamp(43),
                List.of("order_status:unknown"),
                Map.of("unknown_order_status_action", "MANUAL_REVIEW")
        ));

        ProjectionUpdate update = projection.apply(manualReviewRemediationDecision(
                "evt-remediation-decision",
                "remediation-1",
                timestamp(44)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET)).isEmpty();
        assertThat(projection.remediationDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.scope()).isEqualTo("MANUAL_REVIEW");
                    assertThat(decision.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(decision.attributes()).containsEntry("command_id", "cmd-1");
                });
    }

    @Test
    void stale_remediation_decision_does_not_clear_newer_manual_review_decision() {
        projection.apply(orderResult("evt-unknown", OrderResultStatus.UNKNOWN, null, timestamp(40)));
        projection.apply(riskDecision(
                "evt-risk-decision-review",
                RiskDecision.MANUAL_REVIEW,
                timestamp(45),
                List.of("order_status:unknown"),
                Map.of("unknown_order_status_action", "MANUAL_REVIEW")
        ));

        ProjectionUpdate update = projection.apply(manualReviewRemediationDecision(
                "evt-remediation-decision",
                "remediation-1",
                timestamp(44)
        ));

        assertThat(update.status()).isEqualTo(ProjectionUpdateStatus.APPLIED);
        assertThat(projection.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> assertThat(decision.commandId()).isEqualTo("cmd-1"));
        assertThat(projection.remediationDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> assertThat(decision.remediationId()).isEqualTo("remediation-1"));
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
        projection.apply(executionReport("evt-external", "NEW", "0", timestamp(12)));
        projection.apply(risk("evt-risk", "-0.01304097", timestamp(13)));
        projection.apply(riskDecision("evt-risk-decision-review", RiskDecision.MANUAL_REVIEW, timestamp(14)));
        projection.apply(remediationDecision("evt-remediation-decision", "remediation-1", timestamp(15)));
        projection.apply(pauseRemediationDecision(
                "evt-pause-symbol",
                "remediation-pause-symbol",
                "POSITION",
                "PAUSE_SYMBOL",
                SYMBOL,
                timestamp(16)
        ));

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
        assertThat(restored.manualReviewDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(decision -> assertThat(decision.commandId()).isEqualTo("cmd-1"));
        assertThat(restored.remediationDecisionStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .extracting(TradingStateProjection.RemediationDecisionState::remediationId)
                .containsExactly("remediation-1", "remediation-pause-symbol");
        assertThat(restored.pauseGovernanceStates(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET))
                .singleElement()
                .satisfies(pause -> {
                    assertThat(pause.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(pause.pauseTarget()).isEqualTo(SYMBOL);
                    assertThat(pause.remediationId()).isEqualTo("remediation-pause-symbol");
                });
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
        return risk(eventId, delta, eventTime, null);
    }

    private TradingEventEnvelope<RiskUpdateEvent> risk(
            String eventId,
            String delta,
            Instant eventTime,
            String marginBalance
    ) {
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
                .setMarginBalance(marginBalance)
                .setEventTimeMicros(eventTime)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.RISK_UPDATE,
                TradingEventKeys.symbol(TradingEventType.RISK_UPDATE, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, "BTCUSDT"),
                event
        );
    }

    private TradingEventEnvelope<RiskDecisionEvent> riskDecision(
            String eventId,
            RiskDecision decision,
            Instant decidedAt
    ) {
        return riskDecision(
                eventId,
                decision,
                decidedAt,
                decision == RiskDecision.MANUAL_REVIEW
                        ? List.of("intervention:external_order")
                        : List.of("risk_gate:approved"),
                Map.of("external_order_intervention_action", "MANUAL_REVIEW")
        );
    }

    private TradingEventEnvelope<RiskDecisionEvent> riskDecision(
            String eventId,
            RiskDecision decision,
            Instant decidedAt,
            List<CharSequence> reasons,
            Map<CharSequence, CharSequence> attributes
    ) {
        RiskDecisionEvent event = RiskDecisionEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setDecisionId("risk-decision:cmd-1")
                .setCommandId("cmd-1")
                .setSignalId("sig-1")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setDecision(decision)
                .setReasons(reasons)
                .setMaxQuantity(null)
                .setMaxNotional(null)
                .setDecidedAtMicros(decidedAt)
                .setAttributes(attributes)
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.RISK_DECISION,
                TradingEventKeys.symbol(TradingEventType.RISK_DECISION, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL),
                event
        );
    }

    private TradingEventEnvelope<RemediationDecisionEvent> remediationDecision(
            String eventId,
            String remediationId,
            Instant decidedAt
    ) {
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setRemediationId(remediationId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setScope("ORDER")
                .setAction("OPERATOR_REVIEW")
                .setClientOrderId("client-1")
                .setPositionSide(null)
                .setInterventionReason("external_order_observed")
                .setReasons(List.of("intervention:external_order_observed"))
                .setDecidedBy("operator")
                .setDecisionReason("reviewed current projection")
                .setDecidedAtMicros(decidedAt)
                .setAttributes(Map.of("ticket", "ops-789"))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.REMEDIATION_DECISION,
                TradingEventKeys.order(
                        TradingEventType.REMEDIATION_DECISION,
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

    private TradingEventEnvelope<RemediationDecisionEvent> pauseRemediationDecision(
            String eventId,
            String remediationId,
            String scope,
            String action,
            String symbol,
            Instant decidedAt
    ) {
        return pauseRemediationDecision(
                eventId,
                remediationId,
                scope,
                action,
                symbol,
                decidedAt,
                Map.of("source_recommendation", "pause-governance-test")
        );
    }

    private TradingEventEnvelope<RemediationDecisionEvent> pauseRemediationDecision(
            String eventId,
            String remediationId,
            String scope,
            String action,
            String symbol,
            Instant decidedAt,
            Map<String, String> attributes
    ) {
        Map<CharSequence, CharSequence> avroAttributes = new LinkedHashMap<>();
        avroAttributes.putAll(attributes);
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setRemediationId(remediationId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(symbol)
                .setScope(scope)
                .setAction(action)
                .setClientOrderId(null)
                .setPositionSide("BOTH")
                .setInterventionReason("external_position_change")
                .setReasons(List.of("intervention:external_position_change"))
                .setDecidedBy("automated_remediation_policy")
                .setDecisionReason("policy selected pause governance")
                .setDecidedAtMicros(decidedAt)
                .setAttributes(avroAttributes)
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.REMEDIATION_DECISION,
                TradingEventKeys.symbol(
                        TradingEventType.REMEDIATION_DECISION,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        symbol == null ? ACCOUNT : symbol
                ),
                event
        );
    }

    private TradingEventEnvelope<RemediationDecisionEvent> manualReviewRemediationDecision(
            String eventId,
            String remediationId,
            Instant decidedAt
    ) {
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setRemediationId(remediationId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setScope("MANUAL_REVIEW")
                .setAction("OPERATOR_REVIEW")
                .setClientOrderId(null)
                .setPositionSide(null)
                .setInterventionReason(null)
                .setReasons(List.of("order_status:unknown"))
                .setDecidedBy("operator")
                .setDecisionReason("reviewed current projection")
                .setDecidedAtMicros(decidedAt)
                .setAttributes(Map.of(
                        "command_id",
                        "cmd-1",
                        "decision_id",
                        "risk-decision:cmd-1"
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.REMEDIATION_DECISION,
                TradingEventKeys.symbol(
                        TradingEventType.REMEDIATION_DECISION,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL
                ),
                event
        );
    }

    private TradingEventEnvelope<OrderCommandEvent> orderCommand(String eventId, Instant requestedAt) {
        OrderCommandEvent event = OrderCommandEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setCommandId("cmd-1")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.10")
                .setPrice("100")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("client-1")
                .setIdempotencyKey("idem-1")
                .setRequestedAtMicros(requestedAt)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(TradingEventType.ORDER_COMMAND, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, "client-1"),
                event
        );
    }

    private TradingEventEnvelope<OrderCommandEvent> cancelOrderCommand(
            String eventId,
            String commandId,
            String clientOrderId,
            String targetClientOrderId,
            String targetExchangeOrderId,
            Instant requestedAt
    ) {
        OrderCommandEvent event = OrderCommandEvent.newBuilder(orderCommand(eventId, requestedAt).value())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId(commandId)
                .setClientOrderId(clientOrderId)
                .setTargetClientOrderId(targetClientOrderId)
                .setTargetExchangeOrderId(targetExchangeOrderId)
                .setIdempotencyKey(commandId + ":idem")
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(TradingEventType.ORDER_COMMAND, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
                event
        );
    }

    private TradingEventEnvelope<OrderCommandEvent> cancelOrderCommandWithTargetAttributes(
            String eventId,
            String commandId,
            String clientOrderId,
            String targetClientOrderId,
            String targetExchangeOrderId,
            Instant requestedAt
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (targetClientOrderId != null) {
            attributes.put("target_client_order_id", targetClientOrderId);
        }
        if (targetExchangeOrderId != null) {
            attributes.put("target_exchange_order_id", targetExchangeOrderId);
        }
        OrderCommandEvent event = OrderCommandEvent.newBuilder(orderCommand(eventId, requestedAt).value())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId(commandId)
                .setClientOrderId(clientOrderId)
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(null)
                .setIdempotencyKey(commandId + ":idem")
                .setAttributes(Map.copyOf(attributes))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(TradingEventType.ORDER_COMMAND, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
                event
        );
    }

    private TradingEventEnvelope<OrderCommandEvent> modifyOrderCommand(
            String eventId,
            String commandId,
            String clientOrderId,
            String targetClientOrderId,
            String targetExchangeOrderId,
            Instant requestedAt
    ) {
        OrderCommandEvent event = OrderCommandEvent.newBuilder(orderCommand(eventId, requestedAt).value())
                .setAction(OrderCommandAction.MODIFY)
                .setCommandId(commandId)
                .setClientOrderId(clientOrderId)
                .setTargetClientOrderId(targetClientOrderId)
                .setTargetExchangeOrderId(targetExchangeOrderId)
                .setQuantity("0.20")
                .setPrice("101")
                .setIdempotencyKey(commandId + ":idem")
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(TradingEventType.ORDER_COMMAND, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
                event
        );
    }

    private TradingEventEnvelope<OrderResultEvent> orderResult(
            String eventId,
            OrderResultStatus status,
            String exchangeStatus,
            Instant observedAt
    ) {
        return orderResult(eventId, "cmd-1", "client-1", "12345", status, exchangeStatus, observedAt);
    }

    private TradingEventEnvelope<OrderResultEvent> orderResult(
            String eventId,
            String commandId,
            String clientOrderId,
            String exchangeOrderId,
            OrderResultStatus status,
            String exchangeStatus,
            Instant observedAt
    ) {
        OrderResultEvent event = OrderResultEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(exchangeOrderId)
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
                TradingEventKeys.order(TradingEventType.ORDER_RESULT, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
                event
        );
    }

    private TradingEventEnvelope<OrderResultEvent> gatewayFailureOrderResult(
            String eventId,
            String commandId,
            String clientOrderId,
            String targetExchangeOrderId,
            Instant observedAt
    ) {
        OrderResultEvent event = OrderResultEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(targetExchangeOrderId)
                .setStatus(OrderResultStatus.UNKNOWN)
                .setExchangeStatus(null)
                .setPrice("100")
                .setOriginalQuantity("0.10")
                .setExecutedQuantity(null)
                .setObservedAtMicros(observedAt)
                .setRejectCode("GATEWAY_FAILURE")
                .setRejectMessage("gateway failed")
                .setAttributes(Map.of(
                        "source", "order_execution_pipeline",
                        "gateway_failure", "true",
                        "command_client_order_id", clientOrderId,
                        "target_exchange_order_id", targetExchangeOrderId
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(TradingEventType.ORDER_RESULT, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
                event
        );
    }

    private TradingEventEnvelope<OrderResultEvent> restSnapshotOrderResult(
            String eventId,
            String commandId,
            String clientOrderId,
            String exchangeOrderId,
            OrderResultStatus status,
            String exchangeStatus,
            Instant observedAt
    ) {
        OrderResultEvent event = OrderResultEvent.newBuilder()
                .setEventId(eventId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(exchangeOrderId)
                .setStatus(status)
                .setExchangeStatus(exchangeStatus)
                .setPrice("100")
                .setOriginalQuantity("0.10")
                .setExecutedQuantity("0")
                .setObservedAtMicros(observedAt)
                .setAttributes(Map.of(
                        "source", "rest_snapshot",
                        "snapshotType", "open_orders"
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(TradingEventType.ORDER_RESULT, PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, SYMBOL, clientOrderId),
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
        return interventionAcknowledgement(eventId, interventionReason, acknowledgedAt, Map.of());
    }

    private TradingEventEnvelope<InterventionAcknowledgementEvent> interventionAcknowledgement(
            String eventId,
            String interventionReason,
            Instant acknowledgedAt,
            Map<CharSequence, CharSequence> attributes
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
                .setAttributes(attributes)
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

    private TradingEventEnvelope<InterventionAcknowledgementEvent> positionInterventionAcknowledgement(
            String eventId,
            String interventionReason,
            String positionSide,
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
                .setClientOrderId(null)
                .setInterventionReason(interventionReason)
                .setAcknowledgedBy("operator")
                .setAcknowledgementReason("reviewed")
                .setAcknowledgedAtMicros(acknowledgedAt)
                .setAttributes(Map.of("position_side", positionSide))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                TradingEventKeys.symbol(
                        TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL
                ),
                event
        );
    }

    private Instant timestamp(long second) {
        return Instant.parse("2026-05-26T00:00:" + second + "Z");
    }
}
