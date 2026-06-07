package io.github.manu.audit;

import io.github.manu.config.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcPauseGovernanceAuditStoreTest {

    private static final Instant NOW = Instant.parse("2026-06-07T16:00:00Z");

    @Test
    void appends_and_queries_recent_matching_events_newest_first() {
        JdbcPauseGovernanceAuditStore store = store();
        store.initializeSchema();

        store.record(event("old", "main", NOW));
        store.record(event("ignored", "other", NOW.plusSeconds(1)));
        store.record(event("new", "main", NOW.plusSeconds(2)));

        assertThat(store.recent("binance", "demo", "main", "usd_m_futures", 1))
                .singleElement()
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .isEqualTo("new");
        assertThat(store.recent("binance", "demo", "main", "usd_m_futures", 10))
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .containsExactly("new", "old");
    }

    @Test
    void persists_events_across_store_instances() {
        String url = url();
        JdbcPauseGovernanceAuditStore firstStore = store(url);
        firstStore.initializeSchema();
        firstStore.record(event("persisted", "main", NOW));

        JdbcPauseGovernanceAuditStore restartedStore = store(url);

        assertThat(restartedStore.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("pause_governance_activated");
                    assertThat(event.eventId()).isEqualTo("persisted");
                    assertThat(event.reason()).isEqualTo("policy selected pause governance");
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void rejects_unsafe_table_prefixes() {
        assertThatThrownBy(() -> new JdbcPauseGovernanceAuditStore(
                url(),
                "",
                "",
                "audit;drop table",
                JsonMapperFactory.create()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tablePrefix");
    }

    private JdbcPauseGovernanceAuditStore store() {
        return store(url());
    }

    private JdbcPauseGovernanceAuditStore store(String url) {
        return new JdbcPauseGovernanceAuditStore(
                url,
                "",
                "",
                "trading_audit_pause_governance_",
                JsonMapperFactory.create()
        );
    }

    private String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    }

    private PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event(
            String eventId,
            String account,
            Instant occurredAt
    ) {
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
                occurredAt
        );
    }
}
