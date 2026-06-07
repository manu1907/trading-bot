package io.github.manu.intervention;

import io.github.manu.audit.PauseGovernanceAuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;

class PauseGovernanceAuditControllerTest {

    private static final Instant NOW = Instant.parse("2026-06-07T14:45:00Z");

    private final PauseGovernanceAuditTrail auditTrail = new PauseGovernanceAuditTrail();
    private final PauseGovernanceAuditController controller = new PauseGovernanceAuditController(
            auditTrail,
            new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
    );
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    @Test
    void lists_recent_pause_governance_audit_events_when_token_matches() {
        auditTrail.record(event("old", "binance", "demo", "main", "usd_m_futures"));
        auditTrail.record(event("ignored", "binance", "demo", "other", "usd_m_futures"));
        auditTrail.record(event("new", "binance", "demo", "main", "usd_m_futures"));

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/pauses/audit-events")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .queryParam("limit", 2)
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(2)
                .jsonPath("$.events[0].eventId")
                .isEqualTo("new")
                .jsonPath("$.events[1].eventId")
                .isEqualTo("old");
    }

    @Test
    void rejects_pause_governance_audit_query_when_token_is_invalid() {
        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/pauses/audit-events")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    private PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event(
            String eventId,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_governance_released",
                provider,
                environment,
                account,
                market,
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
                "released",
                "operator",
                "risk cleared",
                null,
                null,
                NOW
        );
    }
}
