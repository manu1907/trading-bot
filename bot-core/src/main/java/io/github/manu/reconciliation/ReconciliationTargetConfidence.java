package io.github.manu.reconciliation;

import java.time.Instant;

public record ReconciliationTargetConfidence(
        String provider,
        String environment,
        String account,
        String market,
        Status status,
        int observedStates,
        int degradedStates,
        Instant observedAt
) {

    public boolean confident() {
        return status == Status.CONFIDENT;
    }

    public enum Status {
        NO_OBSERVATIONS,
        CONFIDENT,
        DEGRADED
    }
}
