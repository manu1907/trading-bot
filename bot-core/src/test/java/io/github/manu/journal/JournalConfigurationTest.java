package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.messaging.TradingEventHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JournalConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JournalConfiguration.class);

    @TempDir
    private Path journalDirectory;

    @Test
    void keeps_chronicle_journal_disabled_by_default() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(JournalProperties.class)
                .doesNotHaveBean(TradingEventJournal.class));
    }

    @Test
    void creates_chronicle_journal_when_enabled() {
        contextRunner
                .withPropertyValues(
                        "trading.journal.enabled=true",
                        "trading.journal.directory=" + journalDirectory
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(JournalProperties.class)
                        .hasSingleBean(TradingEventJournal.class)
                        .hasSingleBean(ChronicleTradingEventJournal.class));
    }

    @Test
    void binds_explicit_journal_properties() {
        contextRunner
                .withPropertyValues(
                        "trading.journal.enabled=true",
                        "trading.journal.directory=" + journalDirectory
                )
                .run(context -> {
                    JournalProperties properties = context.getBean(JournalProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.directory()).isEqualTo(journalDirectory);
                    assertThat(properties.recovery().enabled()).isFalse();
                });
    }

    @Test
    void creates_recovery_service_when_enabled_and_dependencies_exist() {
        contextRunner
                .withBean(TradingEventJournal.class, () -> new InMemoryTradingEventJournal(List.of()))
                .withBean(TradingEventHandlerRegistry.class, () -> new TradingEventHandlerRegistry(List.of()))
                .withPropertyValues("trading.journal.recovery.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(JournalRecoveryService.class));
    }

    private record InMemoryTradingEventJournal(List<JournaledTradingEvent> events) implements TradingEventJournal {

        private InMemoryTradingEventJournal {
            events = List.copyOf(events);
        }

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new UnsupportedOperationException("append is not used by configuration tests");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            return events;
        }

        @Override
        public void close() {
        }
    }
}
