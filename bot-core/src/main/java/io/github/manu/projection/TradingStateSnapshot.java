package io.github.manu.projection;

import java.util.List;

public record TradingStateSnapshot(
        List<TradingStateProjection.BalanceState> balances,
        List<TradingStateProjection.PositionState> positions,
        List<TradingStateProjection.OrderState> orders,
        List<TradingStateProjection.RiskState> risks,
        List<TradingStateProjection.MarketDataState> marketData,
        List<TradingStateProjection.DailyRealizedPnlState> dailyRealizedPnl,
        List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
        List<TradingStateProjection.RemediationDecisionState> remediationDecisions,
        List<TradingStateProjection.PauseGovernanceState> pauseGovernance,
        List<String> appliedEventIds
) {

    public TradingStateSnapshot {
        balances = balances == null ? List.of() : List.copyOf(balances);
        positions = positions == null ? List.of() : List.copyOf(positions);
        orders = orders == null ? List.of() : List.copyOf(orders);
        risks = risks == null ? List.of() : List.copyOf(risks);
        marketData = marketData == null ? List.of() : List.copyOf(marketData);
        dailyRealizedPnl = dailyRealizedPnl == null ? List.of() : List.copyOf(dailyRealizedPnl);
        manualReviewDecisions = manualReviewDecisions == null ? List.of() : List.copyOf(manualReviewDecisions);
        remediationDecisions = remediationDecisions == null ? List.of() : List.copyOf(remediationDecisions);
        pauseGovernance = pauseGovernance == null ? List.of() : List.copyOf(pauseGovernance);
        appliedEventIds = appliedEventIds == null ? List.of() : List.copyOf(appliedEventIds);
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.DailyRealizedPnlState> dailyRealizedPnl,
            List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
            List<TradingStateProjection.RemediationDecisionState> remediationDecisions,
            List<TradingStateProjection.PauseGovernanceState> pauseGovernance,
            List<String> appliedEventIds
    ) {
        this(
                balances,
                positions,
                orders,
                risks,
                List.of(),
                dailyRealizedPnl,
                manualReviewDecisions,
                remediationDecisions,
                pauseGovernance,
                appliedEventIds
        );
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
            List<TradingStateProjection.RemediationDecisionState> remediationDecisions,
            List<TradingStateProjection.PauseGovernanceState> pauseGovernance,
            List<String> appliedEventIds
    ) {
        this(
                balances,
                positions,
                orders,
                risks,
                List.of(),
                List.of(),
                manualReviewDecisions,
                remediationDecisions,
                pauseGovernance,
                appliedEventIds
        );
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
            List<TradingStateProjection.RemediationDecisionState> remediationDecisions,
            List<String> appliedEventIds
    ) {
        this(
                balances,
                positions,
                orders,
                risks,
                List.of(),
                List.of(),
                manualReviewDecisions,
                remediationDecisions,
                List.of(),
                appliedEventIds
        );
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<String> appliedEventIds
    ) {
        this(balances, positions, orders, risks, List.of(), List.of(), List.of(), List.of(), List.of(), appliedEventIds);
    }

    public TradingStateSnapshot(
            List<TradingStateProjection.BalanceState> balances,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RiskState> risks,
            List<TradingStateProjection.ManualReviewDecisionState> manualReviewDecisions,
            List<String> appliedEventIds
    ) {
        this(
                balances,
                positions,
                orders,
                risks,
                List.of(),
                List.of(),
                manualReviewDecisions,
                List.of(),
                List.of(),
                appliedEventIds
        );
    }

    public static TradingStateSnapshot empty() {
        return new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
