package io.github.manu.projection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTradingStateProjectionStoreTest {

    @Test
    void returns_empty_when_tables_have_no_projection_state() {
        JdbcTradingStateProjectionStore store = store();
        store.initializeSchema();

        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isEmpty();
    }

    @Test
    void saves_and_loads_full_projection_snapshot_transactionally() {
        JdbcTradingStateProjectionStore store = store();
        store.initializeSchema();
        TradingStateSnapshot snapshot = snapshot();

        store.save(snapshot);
        Optional<TradingStateSnapshot> loaded = store.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().balances()).singleElement().satisfies(balance -> {
            assertThat(balance.provider()).isEqualTo("binance");
            assertThat(balance.environment()).isEqualTo("demo");
            assertThat(balance.walletBalance()).isEqualTo("1000");
        });
        assertThat(loaded.get().positions()).singleElement()
                .satisfies(position -> assertThat(position.positionAmount()).isEqualTo("-0.10"));
        assertThat(loaded.get().orders()).singleElement()
                .satisfies(order -> assertThat(order.exchangeStatus()).isEqualTo("NEW"));
        assertThat(loaded.get().risks()).singleElement()
                .satisfies(risk -> assertThat(risk.delta()).isEqualTo("-0.01304097"));
        assertThat(loaded.get().manualReviewDecisions()).singleElement()
                .satisfies(decision -> {
                    assertThat(decision.commandId()).isEqualTo("cmd-1");
                    assertThat(decision.reasons()).containsExactly("intervention:external_order");
                    assertThat(decision.attributes()).containsEntry("external_order_intervention_action", "MANUAL_REVIEW");
                });
        assertThat(loaded.get().remediationDecisions()).singleElement()
                .satisfies(decision -> {
                    assertThat(decision.remediationId()).isEqualTo("remediation-1");
                    assertThat(decision.scope()).isEqualTo("ORDER");
                    assertThat(decision.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(decision.clientOrderId()).isEqualTo("client-1");
                    assertThat(decision.reasons()).containsExactly("intervention:external_order_observed");
                    assertThat(decision.attributes()).containsEntry("ticket", "ops-789");
                });
        assertThat(loaded.get().pauseGovernance()).singleElement()
                .satisfies(pause -> {
                    assertThat(pause.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(pause.pauseTarget()).isEqualTo("BTCUSDT");
                    assertThat(pause.remediationId()).isEqualTo("remediation-pause-1");
                    assertThat(pause.reasons()).containsExactly("intervention:external_position_change");
                    assertThat(pause.attributes()).containsEntry("source_recommendation", "advisor");
                    assertThat(pause.active()).isTrue();
                });
        assertThat(loaded.get().appliedEventIds())
                .containsExactly(
                        "evt-balance",
                        "evt-position",
                        "evt-order",
                        "evt-risk",
                        "evt-risk-decision",
                        "evt-remediation-decision",
                        "evt-pause-governance"
                );
    }

    @Test
    void rejects_unsafe_table_prefixes() {
        assertThatThrownBy(() -> new JdbcTradingStateProjectionStore(
                url(),
                "",
                "",
                "projection;drop table"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tablePrefix");
    }

    private JdbcTradingStateProjectionStore store() {
        return new JdbcTradingStateProjectionStore(url(), "", "", "trading_projection_");
    }

    private String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    }

    private TradingStateSnapshot snapshot() {
        Instant now = Instant.parse("2026-05-26T20:15:00Z");
        return new TradingStateSnapshot(
                List.of(new TradingStateProjection.BalanceState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "USDT",
                        "1000",
                        null,
                        "950",
                        null,
                        "REST_SNAPSHOT",
                        now,
                        "evt-balance"
                )),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "SHORT",
                        "-0.10",
                        "1200",
                        "1210",
                        "16.10",
                        "REST_SNAPSHOT",
                        false,
                        null,
                        now.plusSeconds(1),
                        "evt-position"
                )),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "cmd-1",
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "100",
                        "0.10",
                        "0",
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        true,
                        false,
                        null,
                        now.plusSeconds(2),
                        "evt-order"
                )),
                List.of(new TradingStateProjection.RiskState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "UNDERLYING",
                        "BTCUSDT",
                        "BTCUSDT",
                        null,
                        "-0.01304097",
                        "-0.00000124",
                        "16.116481",
                        "-3.83444011",
                        null,
                        null,
                        now.plusSeconds(3),
                        "evt-risk"
                )),
                List.of(new TradingStateProjection.ManualReviewDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "cmd-1",
                        "sig-1",
                        "lfa",
                        "risk-decision:cmd-1",
                        List.of("intervention:external_order"),
                        Map.of("external_order_intervention_action", "MANUAL_REVIEW"),
                        now.plusSeconds(4),
                        "evt-risk-decision"
                )),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "BTC-251123-126000-C",
                        "remediation-1",
                        "ORDER",
                        "OPERATOR_REVIEW",
                        "client-1",
                        null,
                        "external_order_observed",
                        List.of("intervention:external_order_observed"),
                        "operator",
                        "reviewed current projection",
                        Map.of("ticket", "ops-789"),
                        now.plusSeconds(5),
                        "evt-remediation-decision"
                )),
                List.of(new TradingStateProjection.PauseGovernanceState(
                        "binance",
                        "demo",
                        "main",
                        "options",
                        "SYMBOL",
                        "BTCUSDT",
                        "BTCUSDT",
                        "remediation-pause-1",
                        "POSITION",
                        "PAUSE_SYMBOL",
                        "external_position_change",
                        List.of("intervention:external_position_change"),
                        "automated_remediation_policy",
                        "policy selected pause governance",
                        Map.of("source_recommendation", "advisor"),
                        true,
                        now.plusSeconds(6),
                        "evt-pause-governance"
                )),
                List.of(
                        "evt-balance",
                        "evt-position",
                        "evt-order",
                        "evt-risk",
                        "evt-risk-decision",
                        "evt-remediation-decision",
                        "evt-pause-governance"
                )
        );
    }
}
