package io.github.manu.intervention;

import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InterventionRemediationAdvisor {

    static final String ACTION_OPERATOR_REVIEW = "OPERATOR_REVIEW";
    static final String ACTION_REPLAN_FROM_PROJECTION = "REPLAN_FROM_PROJECTION";
    static final String ACTION_HEDGE_OR_REPLAN = "HEDGE_OR_REPLAN";

    private final TradingStateProjection projection;

    public InterventionRemediationAdvisor(TradingStateProjection projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public List<RemediationRecommendation> recommendations(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String targetProvider = requireText(provider, "provider");
        String targetEnvironment = requireText(environment, "environment");
        String targetAccount = requireText(account, "account");
        String targetMarket = requireText(market, "market");
        List<RemediationRecommendation> recommendations = new ArrayList<>();
        projection.externalOrderInterventionStates(targetProvider, targetEnvironment, targetAccount, targetMarket)
                .stream()
                .map(this::orderRecommendation)
                .forEach(recommendations::add);
        projection.externalPositionInterventionStates(targetProvider, targetEnvironment, targetAccount, targetMarket)
                .stream()
                .map(this::positionRecommendation)
                .forEach(recommendations::add);
        projection.manualReviewDecisionStates(targetProvider, targetEnvironment, targetAccount, targetMarket)
                .stream()
                .map(this::manualReviewRecommendation)
                .forEach(recommendations::add);
        return List.copyOf(recommendations);
    }

    private RemediationRecommendation orderRecommendation(TradingStateProjection.OrderState order) {
        String reason = text(order.interventionReason());
        String action = "unplanned_managed_order_change".equals(reason)
                ? ACTION_REPLAN_FROM_PROJECTION
                : ACTION_OPERATOR_REVIEW;
        return new RemediationRecommendation(
                "ORDER",
                action,
                order.provider(),
                order.environment(),
                order.account(),
                order.market(),
                order.symbol(),
                order.clientOrderId(),
                null,
                order.interventionReason(),
                List.of("intervention:" + textOrUnknown(order.interventionReason())),
                orderAttributes(order),
                order.updatedAt(),
                order.eventId()
        );
    }

    private RemediationRecommendation positionRecommendation(TradingStateProjection.PositionState position) {
        PositionRemediationPolicy policy = positionRemediationPolicy(position.positionAmount());
        return new RemediationRecommendation(
                "POSITION",
                policy.action(),
                position.provider(),
                position.environment(),
                position.account(),
                position.market(),
                position.symbol(),
                null,
                position.positionSide(),
                position.interventionReason(),
                List.of("intervention:" + textOrUnknown(position.interventionReason())),
                positionAttributes(position),
                position.updatedAt(),
                position.eventId()
        );
    }

    private RemediationRecommendation manualReviewRecommendation(
            TradingStateProjection.ManualReviewDecisionState decision
    ) {
        return new RemediationRecommendation(
                "MANUAL_REVIEW",
                ACTION_OPERATOR_REVIEW,
                decision.provider(),
                decision.environment(),
                decision.account(),
                decision.market(),
                decision.symbol(),
                null,
                null,
                null,
                decision.reasons(),
                manualReviewAttributes(decision),
                decision.updatedAt(),
                decision.eventId()
        );
    }

    private Map<String, String> orderAttributes(TradingStateProjection.OrderState order) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "command_id", order.commandId());
        put(attributes, "exchange_order_id", order.exchangeOrderId());
        put(attributes, "status", order.status());
        put(attributes, "exchange_status", order.exchangeStatus());
        put(attributes, "update_source", order.updateSource());
        put(attributes, "execution_type", order.executionType());
        attributes.put("managed_by_bot", Boolean.toString(order.managedByBot()));
        return Map.copyOf(attributes);
    }

    private Map<String, String> positionAttributes(TradingStateProjection.PositionState position) {
        Map<String, String> attributes = new LinkedHashMap<>();
        PositionRemediationPolicy policy = positionRemediationPolicy(position.positionAmount());
        put(attributes, "position_amount", position.positionAmount());
        put(attributes, "entry_price", position.entryPrice());
        put(attributes, "mark_price", position.markPrice());
        put(attributes, "unrealized_pnl", position.unrealizedPnl());
        put(attributes, "update_source", position.updateSource());
        attributes.put("position_state", policy.positionState());
        attributes.put("position_remediation_policy", policy.policy());
        attributes.put("position_remediation_policy_reason", policy.reason());
        return Map.copyOf(attributes);
    }

    private Map<String, String> manualReviewAttributes(TradingStateProjection.ManualReviewDecisionState decision) {
        Map<String, String> attributes = new LinkedHashMap<>(decision.attributes());
        put(attributes, "command_id", decision.commandId());
        put(attributes, "signal_id", decision.signalId());
        put(attributes, "strategy_id", decision.strategyId());
        put(attributes, "decision_id", decision.decisionId());
        return Map.copyOf(attributes);
    }

    private void put(Map<String, String> attributes, String key, String value) {
        String text = text(value);
        if (text != null) {
            attributes.put(key, text);
        }
    }

    private PositionRemediationPolicy positionRemediationPolicy(String value) {
        String text = text(value);
        if (text == null) {
            return new PositionRemediationPolicy(
                    "UNKNOWN",
                    "OPERATOR_REVIEW_REQUIRED",
                    "position_amount_missing",
                    ACTION_OPERATOR_REVIEW
            );
        }
        try {
            if (new BigDecimal(text).compareTo(BigDecimal.ZERO) == 0) {
                return new PositionRemediationPolicy(
                        "CLOSED",
                        "STAND_DOWN_AND_REPLAN",
                        "position_amount_zero",
                        ACTION_REPLAN_FROM_PROJECTION
                );
            }
            return new PositionRemediationPolicy(
                    "OPEN",
                    "REHEDGE_OR_REPLAN",
                    "position_amount_nonzero",
                    ACTION_HEDGE_OR_REPLAN
            );
        } catch (NumberFormatException ignored) {
            return new PositionRemediationPolicy(
                    "UNKNOWN",
                    "OPERATOR_REVIEW_REQUIRED",
                    "position_amount_invalid",
                    ACTION_OPERATOR_REVIEW
            );
        }
    }

    private String requireText(String value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String textOrUnknown(String value) {
        String text = text(value);
        return text == null ? "unknown" : text;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record RemediationRecommendation(
            String scope,
            String action,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String positionSide,
            String interventionReason,
            List<String> reasons,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) {

        public RemediationRecommendation {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private record PositionRemediationPolicy(
            String positionState,
            String policy,
            String reason,
            String action
    ) {
    }
}
