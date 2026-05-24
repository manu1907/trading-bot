package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.messaging.TradingEventHandlerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalRecoveryLifecycleTest {

    @Test
    void replays_journal_on_startup() {
        CountingTradingEventJournal journal = new CountingTradingEventJournal();
        JournalRecoveryService recoveryService = new JournalRecoveryService(
                journal,
                new TradingEventHandlerRegistry(List.of())
        );
        JournalRecoveryLifecycle lifecycle = new JournalRecoveryLifecycle(recoveryService);

        lifecycle.start();

        assertThat(journal.reads()).isEqualTo(1);
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void starts_before_default_lifecycle_beans() {
        JournalRecoveryService recoveryService = new JournalRecoveryService(
                new CountingTradingEventJournal(),
                new TradingEventHandlerRegistry(List.of())
        );
        JournalRecoveryLifecycle lifecycle = new JournalRecoveryLifecycle(recoveryService);

        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void fails_startup_when_recovery_fails() {
        JournalRecoveryService recoveryService = new JournalRecoveryService(
                new FailingTradingEventJournal(),
                new TradingEventHandlerRegistry(List.of())
        );
        JournalRecoveryLifecycle lifecycle = new JournalRecoveryLifecycle(recoveryService);

        assertThatThrownBy(lifecycle::start)
                .isInstanceOf(JournalException.class)
                .hasMessage("Journal is unavailable");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    private static final class CountingTradingEventJournal implements TradingEventJournal {

        private int reads;

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new UnsupportedOperationException("append is not used by recovery lifecycle tests");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            reads++;
            return List.of();
        }

        @Override
        public void close() {
        }

        private int reads() {
            return reads;
        }
    }

    private static final class FailingTradingEventJournal implements TradingEventJournal {

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new UnsupportedOperationException("append is not used by recovery lifecycle tests");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            throw new JournalException("Journal is unavailable");
        }

        @Override
        public void close() {
        }
    }
}
