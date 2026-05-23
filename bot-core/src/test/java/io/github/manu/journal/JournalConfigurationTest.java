package io.github.manu.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

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
                });
    }
}
