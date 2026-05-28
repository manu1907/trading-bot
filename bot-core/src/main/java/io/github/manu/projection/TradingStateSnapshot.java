package io.github.manu.projection;

import java.util.List;

public record TradingStateSnapshot(
        List<TradingStateProjection.BalanceState> balances,
        List<TradingStateProjection.PositionState> positions,
        List<TradingStateProjection.OrderState> orders,
        List<TradingStateProjection.RiskState> risks,
        List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
        List<String> appliedEventIds
) {

    public TradingStateSnapshot {
        balances = balances == null ? List.of() : List.copyOf(balances);
        positions = positions == null ? List.of() : List.copyOf(positions);
        orders = orders == null ? List.of() : List.copyOf(orders);
        risks = risks == null ? List.of() : List.copyOf(risks);
        manualReviewDecisions = manualReviewDecisions == null ? List.of() : List.copyOf(manualReviewDecisions);
        appliedEventIds = appliedEventIds == null ? List.of() : List.copyOf(appliedEventIds);
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<String> appliedEventIds
    ) {
        this(balances, positions, orders, risks, List.of(), appliedEventIds);
    }

    public static TradingStateSnapshot empty() {
        return new TradingStateSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
