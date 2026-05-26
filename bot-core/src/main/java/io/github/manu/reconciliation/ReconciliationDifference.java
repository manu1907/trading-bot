package io.github.manu.reconciliation;

public record ReconciliationDifference(
        String field,
        String snapshotValue,
        String projectionValue
) {
}
