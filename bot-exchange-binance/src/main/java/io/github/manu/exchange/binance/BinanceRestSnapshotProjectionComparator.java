package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceRestSnapshotProjectionComparator {

    private final TradingStateProjection projection;

    BinanceRestSnapshotProjectionComparator(TradingStateProjection projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    List<BinanceRestSnapshotProjectionComparison> compare(List<? extends TradingEventEnvelope<?>> envelopes) {
        List<BinanceRestSnapshotProjectionComparison> comparisons = new ArrayList<>();
        for (TradingEventEnvelope<?> envelope : Objects.requireNonNull(envelopes, "envelopes")) {
            comparisons.add(compare(envelope));
        }
        return List.copyOf(comparisons);
    }

    private BinanceRestSnapshotProjectionComparison compare(TradingEventEnvelope<?> envelope) {
        return switch (envelope.eventType()) {
            case BALANCE_UPDATE -> compareBalance(cast(envelope.value(), BalanceUpdateEvent.class));
            case POSITION_UPDATE -> comparePosition(cast(envelope.value(), PositionUpdateEvent.class));
            case ORDER_RESULT -> compareOrder(cast(envelope.value(), OrderResultEvent.class));
            case RISK_UPDATE -> compareRisk(cast(envelope.value(), RiskUpdateEvent.class));
            default -> throw new IllegalArgumentException("Unsupported reconciliation event type: " + envelope.eventType());
        };
    }

    private BinanceRestSnapshotProjectionComparison compareBalance(BalanceUpdateEvent event) {
        Target target = target(event);
        String entityKey = key(event.getProvider(), event.getEnvironment(), event.getAccount(), event.getMarket(), event.getAsset());
        return projection.balance(
                        target.provider(),
                        target.environment(),
                        target.account(),
                        target.market(),
                        value(event.getAsset())
                )
                .map(state -> comparison(
                        target,
                        TradingEventType.BALANCE_UPDATE,
                        entityKey,
                        differences(
                                difference("walletBalance", event.getWalletBalance(), state.walletBalance()),
                                difference("crossWalletBalance", event.getCrossWalletBalance(), state.crossWalletBalance()),
                                difference("availableBalance", event.getAvailableBalance(), state.availableBalance())
                        )
                ))
                .orElseGet(() -> missing(target, TradingEventType.BALANCE_UPDATE, entityKey));
    }

    private BinanceRestSnapshotProjectionComparison comparePosition(PositionUpdateEvent event) {
        Target target = target(event);
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getPositionSide()
        );
        return projection.position(
                        target.provider(),
                        target.environment(),
                        target.account(),
                        target.market(),
                        value(event.getSymbol()),
                        value(event.getPositionSide())
                )
                .map(state -> comparison(
                        target,
                        TradingEventType.POSITION_UPDATE,
                        entityKey,
                        differences(
                                difference("positionAmount", event.getPositionAmount(), state.positionAmount()),
                                difference("entryPrice", event.getEntryPrice(), state.entryPrice()),
                                difference("markPrice", event.getMarkPrice(), state.markPrice()),
                                difference("unrealizedPnl", event.getUnrealizedPnl(), state.unrealizedPnl())
                        )
                ))
                .orElseGet(() -> missing(target, TradingEventType.POSITION_UPDATE, entityKey));
    }

    private BinanceRestSnapshotProjectionComparison compareOrder(OrderResultEvent event) {
        Target target = target(event);
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getSymbol(),
                event.getClientOrderId()
        );
        return projection.order(
                        target.provider(),
                        target.environment(),
                        target.account(),
                        target.market(),
                        value(event.getSymbol()),
                        value(event.getClientOrderId())
                )
                .map(state -> comparison(
                        target,
                        TradingEventType.ORDER_RESULT,
                        entityKey,
                        differences(
                                difference("exchangeOrderId", event.getExchangeOrderId(), state.exchangeOrderId()),
                                difference("exchangeStatus", event.getExchangeStatus(), state.exchangeStatus()),
                                difference("price", event.getPrice(), state.price()),
                                difference("executedQuantity", event.getExecutedQuantity(), state.executedQuantity()),
                                difference("cumulativeQuote", event.getCumulativeQuote(), state.cumulativeQuote())
                        )
                ))
                .orElseGet(() -> missing(target, TradingEventType.ORDER_RESULT, entityKey));
    }

    private BinanceRestSnapshotProjectionComparison compareRisk(RiskUpdateEvent event) {
        Target target = target(event);
        String entityId = value(event.getSymbol());
        if (entityId == null) {
            entityId = value(event.getUnderlying());
        }
        if (entityId == null) {
            entityId = value(event.getAccount());
        }
        String entityKey = key(
                event.getProvider(),
                event.getEnvironment(),
                event.getAccount(),
                event.getMarket(),
                event.getRiskScope(),
                entityId
        );
        return projection.risk(
                        target.provider(),
                        target.environment(),
                        target.account(),
                        target.market(),
                        value(event.getRiskScope()),
                        entityId
                )
                .map(state -> comparison(
                        target,
                        TradingEventType.RISK_UPDATE,
                        entityKey,
                        differences(
                                difference("delta", event.getDelta(), state.delta()),
                                difference("gamma", event.getGamma(), state.gamma()),
                                difference("theta", event.getTheta(), state.theta()),
                                difference("vega", event.getVega(), state.vega()),
                                difference("marginBalance", event.getMarginBalance(), state.marginBalance()),
                                difference("maintenanceMargin", event.getMaintenanceMargin(), state.maintenanceMargin())
                        )
                ))
                .orElseGet(() -> missing(target, TradingEventType.RISK_UPDATE, entityKey));
    }

    private BinanceRestSnapshotProjectionComparison comparison(
            Target target,
            TradingEventType eventType,
            String entityKey,
            List<BinanceRestSnapshotProjectionComparison.Difference> differences
    ) {
        if (differences.isEmpty()) {
            return new BinanceRestSnapshotProjectionComparison(
                    target.provider(),
                    target.environment(),
                    target.account(),
                    target.market(),
                    eventType,
                    entityKey,
                    BinanceRestSnapshotProjectionComparison.Status.MATCHED,
                    List.of()
            );
        }
        return new BinanceRestSnapshotProjectionComparison(
                target.provider(),
                target.environment(),
                target.account(),
                target.market(),
                eventType,
                entityKey,
                BinanceRestSnapshotProjectionComparison.Status.MISMATCH,
                differences
        );
    }

    private BinanceRestSnapshotProjectionComparison missing(
            Target target,
            TradingEventType eventType,
            String entityKey
    ) {
        return new BinanceRestSnapshotProjectionComparison(
                target.provider(),
                target.environment(),
                target.account(),
                target.market(),
                eventType,
                entityKey,
                BinanceRestSnapshotProjectionComparison.Status.MISSING_PROJECTION,
                List.of()
        );
    }

    private Target target(BalanceUpdateEvent event) {
        return new Target(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket())
        );
    }

    private Target target(PositionUpdateEvent event) {
        return new Target(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket())
        );
    }

    private Target target(OrderResultEvent event) {
        return new Target(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket())
        );
    }

    private Target target(RiskUpdateEvent event) {
        return new Target(
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket())
        );
    }

    @SafeVarargs
    private final List<BinanceRestSnapshotProjectionComparison.Difference> differences(
            BinanceRestSnapshotProjectionComparison.Difference... differences
    ) {
        List<BinanceRestSnapshotProjectionComparison.Difference> present = new ArrayList<>();
        for (BinanceRestSnapshotProjectionComparison.Difference difference : differences) {
            if (difference != null) {
                present.add(difference);
            }
        }
        return List.copyOf(present);
    }

    private BinanceRestSnapshotProjectionComparison.Difference difference(
            String field,
            CharSequence snapshotValue,
            String projectionValue
    ) {
        String snapshot = value(snapshotValue);
        if (sameValue(snapshot, projectionValue)) {
            return null;
        }
        return new BinanceRestSnapshotProjectionComparison.Difference(field, snapshot, projectionValue);
    }

    private boolean sameValue(String snapshotValue, String projectionValue) {
        if (snapshotValue == null || projectionValue == null) {
            return snapshotValue == null && projectionValue == null;
        }
        try {
            return new BigDecimal(snapshotValue).compareTo(new BigDecimal(projectionValue)) == 0;
        } catch (NumberFormatException ignored) {
            return snapshotValue.equals(projectionValue);
        }
    }

    private <T> T cast(Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private String key(CharSequence... parts) {
        StringBuilder builder = new StringBuilder();
        for (CharSequence part : parts) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(part == null || part.toString().isBlank() ? "-" : part.toString().trim());
        }
        return builder.toString();
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private record Target(
            String provider,
            String environment,
            String account,
            String market
    ) {
    }
}
