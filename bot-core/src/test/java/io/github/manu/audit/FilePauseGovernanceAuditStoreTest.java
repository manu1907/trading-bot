package io.github.manu.audit;

import io.github.manu.config.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FilePauseGovernanceAuditStoreTest {

    private static final Instant NOW = Instant.parse("2026-06-07T16:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void returns_empty_when_audit_file_does_not_exist() {
        FilePauseGovernanceAuditStore store = store();

        assertThat(store.recent("binance", "demo", "main", "usd_m_futures", 10)).isEmpty();
    }

    @Test
    void appends_and_queries_recent_matching_events_newest_first() {
        FilePauseGovernanceAuditStore store = store();

        store.record(event("old", "main"));
        store.record(event("ignored", "other"));
        store.record(event("new", "main"));

        assertThat(store.recent("binance", "demo", "main", "usd_m_futures", 1))
                .singleElement()
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .isEqualTo("new");
        assertThat(store.recent("binance", "demo", "main", "usd_m_futures", 10))
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .containsExactly("new", "old");
        assertThat(Files.isRegularFile(auditPath())).isTrue();
    }

    private FilePauseGovernanceAuditStore store() {
        return new FilePauseGovernanceAuditStore(auditPath(), JsonMapperFactory.create());
    }

    private Path auditPath() {
        return temporaryDirectory.resolve("audit").resolve("pause-governance.jsonl");
    }

    private PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event(String eventId, String account) {
        return new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_governance_activated",
                "binance",
                "demo",
                account,
                "usd_m_futures",
                "BTCUSDT",
                "SYMBOL",
                "BTCUSDT",
                "remediation-" + eventId,
                eventId,
                null,
                null,
                null,
                null,
                null,
                "activated",
                "automated_remediation_policy",
                "policy selected pause governance",
                NOW.plusSeconds(900).toString(),
                null,
                NOW
        );
    }
}
