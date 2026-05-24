package io.github.manu.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JournalRecoveryLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(JournalRecoveryLifecycle.class);

    private final JournalRecoveryService recoveryService;
    private final AtomicBoolean recovered = new AtomicBoolean();

    public JournalRecoveryLifecycle(JournalRecoveryService recoveryService) {
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
    }

    @Override
    public void start() {
        if (recovered.compareAndSet(false, true)) {
            try {
                JournalRecoveryReport report = recoveryService.replayAll();
                LOGGER.info(
                        "Replayed {} journal events through index {}",
                        report.replayedEvents(),
                        report.lastIndex()
                );
            } catch (RuntimeException ex) {
                recovered.set(false);
                throw ex;
            }
        }
    }

    @Override
    public void stop() {
        recovered.set(false);
    }

    @Override
    public boolean isRunning() {
        return recovered.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
