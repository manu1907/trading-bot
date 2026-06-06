package io.github.manu.intervention;

import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class InterventionRemediationAdvisorTest {

    private static final Instant NOW = Instant.parse("2026-06-02T20:00:00Z");

    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationAdvisor advisor = new InterventionRemediationAdvisor(projection);

    @Test
    void recommends_operator_review_for_unknown_external_order() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(order("external_order_observed", false)),
                List.of(),
                List.of()
        ));

        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(recommendation -> {
                    assertThat(recommendation.scope()).isEqualTo("ORDER");
                    assertThat(recommendation.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(recommendation.clientOrderId()).isEqualTo("client-1");
                    assertThat(recommendation.reasons()).containsExactly("intervention:external_order_observed");
                    assertThat(recommendation.attributes()).containsEntry("managed_by_bot", "false");
                });
    }

    @Test
    void recommends_replan_for_unplanned_managed_order_change() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(order("unplanned_managed_order_change", true)),
                List.of(),
                List.of()
        ));

        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(recommendation -> assertThat(recommendation.action()).isEqualTo("REPLAN_FROM_PROJECTION"));
    }

    @Test
    void recommends_replan_for_manual_position_close_and_hedge_or_replan_for_position_size_change() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(
                        position("BTCUSDT", "0"),
                        position("ETHUSDT", "0.25")
                ),
                List.of(),
                List.of(),
                List.of()
        ));

        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .extracting(
                        InterventionRemediationAdvisor.RemediationRecommendation::symbol,
                        InterventionRemediationAdvisor.RemediationRecommendation::action
                )
                .containsExactly(
                        tuple("BTCUSDT", "REPLAN_FROM_PROJECTION"),
                        tuple("ETHUSDT", "HEDGE_OR_REPLAN")
                );
        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .extracting(
                        InterventionRemediationAdvisor.RemediationRecommendation::symbol,
                        recommendation -> recommendation.attributes().get("position_state"),
                        recommendation -> recommendation.attributes().get("position_remediation_policy"),
                        recommendation -> recommendation.attributes().get("position_remediation_policy_reason")
                )
                .containsExactly(
                        tuple("BTCUSDT", "CLOSED", "STAND_DOWN_AND_REPLAN", "position_amount_zero"),
                        tuple("ETHUSDT", "OPEN", "REHEDGE_OR_REPLAN", "position_amount_nonzero")
                );
    }

    @Test
    void requires_operator_review_when_external_position_amount_is_not_parseable() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(position("BTCUSDT", "not-a-number")),
                List.of(),
                List.of(),
                List.of()
        ));

        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(recommendation -> {
                    assertThat(recommendation.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(recommendation.attributes())
                            .containsEntry("position_state", "UNKNOWN")
                            .containsEntry("position_remediation_policy", "OPERATOR_REVIEW_REQUIRED")
                            .containsEntry("position_remediation_policy_reason", "position_amount_invalid");
                });
    }

    @Test
    void applies_configured_automated_policy_actions_to_external_state_recommendations() {
        InterventionRemediationAdvisor automatedAdvisor = new InterventionRemediationAdvisor(
                projection,
                new InterventionProperties.AutomatedPolicy(
                        InterventionProperties.RemediationAction.ADOPT,
                        InterventionProperties.RemediationAction.AMEND,
                        InterventionProperties.RemediationAction.IGNORE,
                        InterventionProperties.RemediationAction.HEDGE,
                        InterventionProperties.RemediationAction.PAUSE_SYMBOL
                )
        );
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(
                        position("BTCUSDT", "0"),
                        position("ETHUSDT", "0.25"),
                        position("BNBUSDT", "invalid")
                ),
                List.of(
                        order("external_order_observed", false, "client-1"),
                        order("unplanned_managed_order_change", true, "client-2")
                ),
                List.of(),
                List.of()
        ));

        assertThat(automatedAdvisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .extracting(
                        InterventionRemediationAdvisor.RemediationRecommendation::scope,
                        InterventionRemediationAdvisor.RemediationRecommendation::symbol,
                        InterventionRemediationAdvisor.RemediationRecommendation::action
                )
                .containsExactly(
                        tuple("ORDER", "BTCUSDT", "ADOPT"),
                        tuple("ORDER", "BTCUSDT", "AMEND"),
                        tuple("POSITION", "BNBUSDT", "PAUSE_SYMBOL"),
                        tuple("POSITION", "BTCUSDT", "IGNORE"),
                        tuple("POSITION", "ETHUSDT", "HEDGE")
                );
        assertThat(automatedAdvisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .extracting(recommendation -> recommendation.attributes().get("automated_policy_action"))
                .containsExactly("ADOPT", "AMEND", "PAUSE_SYMBOL", "IGNORE", "HEDGE");
    }

    @Test
    void includes_manual_review_decision_as_operator_review_recommendation() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-1",
                        "client-1",
                        "12345",
                        "UNKNOWN",
                        null,
                        "50000.00",
                        "0.001",
                        null,
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        true,
                        false,
                        null,
                        NOW,
                        "evt-unknown-order"
                )),
                List.of(),
                List.of(new TradingStateProjection.ManualReviewDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-1",
                        "sig-1",
                        "lfa",
                        "risk-decision:cmd-1",
                        List.of("order_status:unknown"),
                        Map.of("unknown_order_status_action", "MANUAL_REVIEW"),
                        NOW,
                        "evt-review"
                )),
                List.of()
        ));

        assertThat(advisor.recommendations("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(recommendation -> {
                    assertThat(recommendation.scope()).isEqualTo("MANUAL_REVIEW");
                    assertThat(recommendation.action()).isEqualTo("OPERATOR_REVIEW");
                    assertThat(recommendation.reasons()).containsExactly("order_status:unknown");
                    assertThat(recommendation.attributes()).containsEntry("decision_id", "risk-decision:cmd-1");
                });
    }

    private TradingStateProjection.OrderState order(String interventionReason, boolean managedByBot) {
        return order(interventionReason, managedByBot, "client-1");
    }

    private TradingStateProjection.OrderState order(
            String interventionReason,
            boolean managedByBot,
            String clientOrderId
    ) {
        return new TradingStateProjection.OrderState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                managedByBot ? "cmd-1" : null,
                clientOrderId,
                "12345",
                "CANCELED",
                "CANCELED",
                "50000.00",
                "0.001",
                "0",
                null,
                null,
                "USER_DATA",
                "CANCELED",
                managedByBot,
                true,
                interventionReason,
                NOW,
                "evt-order"
        );
    }

    private TradingStateProjection.PositionState position(String symbol, String amount) {
        return new TradingStateProjection.PositionState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                symbol,
                "BOTH",
                amount,
                "50000.00",
                "50010.00",
                "0",
                "USER_DATA",
                true,
                "external_position_change",
                NOW,
                "evt-position-" + symbol
        );
    }
}
