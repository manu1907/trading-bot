package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventType;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationDifference;
import io.github.manu.reconciliation.ReconciliationObservation;

import java.util.List;

record BinanceRestSnapshotProjectionComparison(
        String provider,
        String environment,
        String account,
        String market,
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

    ReconciliationObservation toObservation() {
        return new ReconciliationObservation(
                provider,
                environment,
                account,
                market,
                eventType,
                entityKey,
                switch (status) {
                    case MATCHED -> ReconciliationConfidenceStatus.CONFIDENT;
                    case MISSING_PROJECTION -> ReconciliationConfidenceStatus.MISSING_PROJECTION;
                    case MISMATCH -> ReconciliationConfidenceStatus.MISMATCH;
                },
                differences.stream()
                        .map(difference -> new ReconciliationDifference(
                                difference.field(),
                                difference.snapshotValue(),
                                difference.projectionValue()
                        ))
                        .toList()
        );
    }

    enum Status {
        MATCHED,
        MISSING_PROJECTION,
        MISMATCH
    }

    record Difference(String field, String snapshotValue, String projectionValue) {
    }
}
