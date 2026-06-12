package io.github.manu.strategy.lfa;

import java.util.Arrays;
import java.util.Locale;

enum LfaLifecycleState {
    STARTING("lfa_lifecycle:starting"),
    ACTIVE(null),
    PAUSED("lfa_lifecycle:paused"),
    DRAINING("lfa_lifecycle:draining"),
    STOPPED("lfa_lifecycle:stopped"),
    EMERGENCY_STOP("lfa_lifecycle:emergency_stop");

    private final String blockerReason;

    LfaLifecycleState(String blockerReason) {
        this.blockerReason = blockerReason;
    }

    static LfaLifecycleState parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " must be a known LFA lifecycle state");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(state -> state.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(field + " must be a known LFA lifecycle state"));
    }

    boolean canPublishNewSignals() {
        return this == ACTIVE;
    }

    String blockerReason() {
        return blockerReason == null ? "lfa_lifecycle:not_allowed" : blockerReason;
    }
}
