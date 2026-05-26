package io.github.manu.projection;

import java.util.List;

public record TradingStateSnapshot(
        List<TradingStateProjection.BalanceState> balances,
        List<TradingStateProjection.PositionState> positions,
        List<TradingStateProjection.OrderState> orders,
        List<TradingStateProjection.RiskState> risks,
        List<String> appliedEventIds
) {

    public TradingStateSnapshot {
        balances = balances == null ? List.of() : List.copyOf(balances);
        positions = positions == null ? List.of() : List.copyOf(positions);
        orders = orders == null ? List.of() : List.copyOf(orders);
        risks = risks == null ? List.of() : List.copyOf(risks);
        appliedEventIds = appliedEventIds == null ? List.of() : List.copyOf(appliedEventIds);
    }

    public static TradingStateSnapshot empty() {
        return new TradingStateSnapshot(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
