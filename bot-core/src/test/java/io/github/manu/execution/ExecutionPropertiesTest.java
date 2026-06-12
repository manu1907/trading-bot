package io.github.manu.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionPropertiesTest {

    @Test
    void defaults_manual_intervention_to_manual_review_actions() {
        ExecutionProperties properties = new ExecutionProperties(null);
        ExecutionProperties.ManualIntervention manualIntervention = properties.riskGate().manualIntervention();

        assertThat(manualIntervention.rejectExternalOrderInterventions()).isTrue();
        assertThat(manualIntervention.rejectExternalPositionInterventions()).isTrue();
        assertThat(manualIntervention.externalOrderAction())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(manualIntervention.externalOrderApplyToTargetCommands()).isFalse();
        assertThat(manualIntervention.externalPositionAction())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(manualIntervention.externalPositionApplyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().unknownOrderStatus().rejectUnknownOrderStatus()).isTrue();
        assertThat(properties.riskGate().unknownOrderStatus().action())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(properties.riskGate().unknownOrderStatus().applyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().pendingOrderCommand().rejectUnresolvedOrderCommands()).isTrue();
        assertThat(properties.riskGate().pendingOrderCommand().action())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(properties.riskGate().pendingOrderCommand().applyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().orderLimit().enabled()).isTrue();
        assertThat(properties.riskGate().orderLimit().rejectInvalidNumericFields()).isTrue();
        assertThat(properties.riskGate().orderLimit().rejectUnboundedNotional()).isTrue();
        assertThat(properties.riskGate().orderLimit().maxOpenOrders()).isNull();
        assertThat(properties.riskGate().orderLimit().action())
                .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
        assertThat(properties.riskGate().orderLimit().targetLimits()).isEmpty();
        assertThat(properties.riskGate().targetOrder().allowExternalRemediationCancel()).isTrue();
        assertThat(properties.riskGate().targetOrder().allowAdoptedTargetOrders()).isFalse();
        assertThat(properties.idempotency().rejectProjectedDuplicates()).isTrue();
        assertThat(properties.signalPlanner().instrumentUniverse().enabled()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().includedSymbols()).isEmpty();
        assertThat(properties.signalPlanner().instrumentUniverse().excludedSymbols()).isEmpty();
        assertThat(properties.signalPlanner().instrumentUniverse().refreshExchangeMetadataBeforePlanning()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().requireExchangeMetadata()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().requireIncludedSymbol()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().requireSymbolEnabled()).isTrue();
        assertThat(properties.signalPlanner().instrumentUniverse().requirePromotionReady()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().requiredStatus()).isEqualTo("TRADING");
        assertThat(properties.signalPlanner().instrumentUniverse().requiredOrderType()).isNull();
        assertThat(properties.signalPlanner().instrumentUniverse().allowedQuoteAssets()).isEmpty();
        assertThat(properties.signalPlanner().instrumentUniverse().allowedContractTypes()).isEmpty();
        assertThat(properties.signalPlanner().instrumentUniverse().maxEligibleSymbols()).isNull();
        assertThat(properties.signalPlanner().instrumentUniverse().requireMarketData()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().requireTopOfBook()).isFalse();
        assertThat(properties.signalPlanner().instrumentUniverse().maxMarketDataAgeMillis()).isEqualTo(60000L);
        assertThat(properties.signalPlanner().instrumentUniverse().maxSpreadBps()).isNull();
        assertThat(properties.signalPlanner().instrumentUniverse().symbolPolicies()).isEmpty();
    }

    @Test
    void derives_allow_action_from_legacy_false_reject_flag() {
        ExecutionProperties.ManualIntervention manualIntervention =
                new ExecutionProperties.ManualIntervention(false, false);

        assertThat(manualIntervention.rejectExternalOrderInterventions()).isFalse();
        assertThat(manualIntervention.rejectExternalPositionInterventions()).isFalse();
        assertThat(manualIntervention.externalOrderAction())
                .isEqualTo(ExecutionProperties.InterventionAction.ALLOW_NEW_COMMANDS);
        assertThat(manualIntervention.externalPositionAction())
                .isEqualTo(ExecutionProperties.InterventionAction.ALLOW_NEW_COMMANDS);
    }

    @Test
    void rejects_conflicting_legacy_flag_and_remediation_action() {
        assertThatThrownBy(() -> new ExecutionProperties.ManualIntervention(
                        false,
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manual intervention reject flag conflicts with remediation action");
    }

    @Test
    void rejects_conflicting_unknown_order_status_flag_and_action() {
        assertThatThrownBy(() -> new ExecutionProperties.UnknownOrderStatus(
                        false,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown order status reject flag conflicts with remediation action");
    }

    @Test
    void rejects_conflicting_pending_order_command_flag_and_action() {
        assertThatThrownBy(() -> new ExecutionProperties.PendingOrderCommand(
                        false,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pending order command reject flag conflicts with remediation action");
    }

    @Test
    void rejects_non_positive_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit(
                        true,
                        true,
                        "0",
                        null,
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxQuantity must be positive when configured");
    }

    @Test
    void rejects_non_decimal_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit(
                        true,
                        true,
                        null,
                        "not-a-decimal",
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxNotional must be a decimal number");
    }

    @Test
    void rejects_non_positive_max_open_orders_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit(
                        true,
                        true,
                        null,
                        null,
                        true,
                        0,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxOpenOrders must be positive when configured");
    }

    @Test
    void rejects_non_positive_target_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit.TargetLimit(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "0",
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetLimits.maxNotional must be positive when configured");
    }

    @Test
    void rejects_non_positive_target_max_open_orders_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit.TargetLimit(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        null,
                        true,
                        0,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetLimits.maxOpenOrders must be positive when configured");
    }

    @Test
    void rejects_blank_instrument_universe_symbols() {
        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        java.util.List.of(" "),
                        java.util.List.of(),
                        true,
                        true,
                        false,
                        java.util.List.of()
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instrument universe symbols must be non-blank");
    }

    @Test
    void rejects_instrument_symbol_policy_without_symbol() {
        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.SymbolPolicy(
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures",
                        null,
                        true,
                        true,
                        null,
                        null,
                        null
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("symbolPolicies.symbol is required");
    }

    @Test
    void rejects_non_positive_instrument_symbol_policy_numeric_limits() {
        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.SymbolPolicy(
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures",
                        "BTCUSDT",
                        true,
                        true,
                        "0",
                        null,
                        null
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("symbolPolicies.minDailyQuoteVolume must be positive when configured");
    }

    @Test
    void rejects_non_positive_instrument_universe_max_eligible_symbols() {
        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        java.util.List.of("BTCUSDT"),
                        java.util.List.of(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        java.util.List.of("USDT"),
                        java.util.List.of("PERPETUAL"),
                        0,
                        false,
                        false,
                        30000L,
                        null,
                        java.util.List.of()
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instrument universe maxEligibleSymbols must be positive when configured");
    }

    @Test
    void rejects_non_positive_instrument_universe_market_data_limits() {
        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        java.util.List.of("BTCUSDT"),
                        java.util.List.of(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        java.util.List.of("USDT"),
                        java.util.List.of("PERPETUAL"),
                        null,
                        true,
                        true,
                        0L,
                        "5",
                        java.util.List.of()
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instrument universe maxMarketDataAgeMillis must be positive when configured");

        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        java.util.List.of("BTCUSDT"),
                        java.util.List.of(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        java.util.List.of("USDT"),
                        java.util.List.of("PERPETUAL"),
                        null,
                        true,
                        true,
                        1000L,
                        "0",
                        null,
                        java.util.List.of()
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instrument universe maxSpreadBps must be positive when configured");

        assertThatThrownBy(() -> new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        java.util.List.of("BTCUSDT"),
                        java.util.List.of(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        java.util.List.of("USDT"),
                        java.util.List.of("PERPETUAL"),
                        null,
                        true,
                        true,
                        1000L,
                        "5",
                        "0",
                        java.util.List.of()
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instrument universe minTopOfBookQuoteNotional must be positive when configured");
    }
}
