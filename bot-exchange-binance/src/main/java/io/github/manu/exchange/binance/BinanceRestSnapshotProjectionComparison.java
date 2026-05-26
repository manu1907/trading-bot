package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventType;

import java.util.List;

record BinanceRestSnapshotProjectionComparison(
        TradingEventType eventType,
        String entityKey,
        Status status,
        List<Difference> differences
) {

    BinanceRestSnapshotProjectionComparison {
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    boolean aligned() {
        return status == Status.MATCHED;
    }

    enum Status {
        MATCHED,
        MISSING_PROJECTION,
        MISMATCH
    }

    record Difference(String field, String snapshotValue, String projectionValue) {
    }
}
