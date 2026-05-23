package io.github.manu.journal;

public record JournalRecoveryReport(int replayedEvents, long lastIndex) {

    public JournalRecoveryReport {
        if (replayedEvents < 0) {
            throw new IllegalArgumentException("replayedEvents must not be negative");
        }
        if (lastIndex < -1) {
            throw new IllegalArgumentException("lastIndex must not be less than -1");
        }
    }
}
