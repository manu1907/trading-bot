package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionOperatorControllerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionAcknowledgementService service = new InterventionAcknowledgementService(
            eventBus,
            projection,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "ack-001"
    );
    private final InterventionRemediationAdvisor remediationAdvisor = new InterventionRemediationAdvisor(projection);
    private final InterventionRemediationDecisionService remediationDecisionService =
            new InterventionRemediationDecisionService(
                    eventBus,
                    remediationAdvisor,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> "remediation-001"
            );
    private final InterventionAutomatedDecisionService automatedDecisionService = automatedDecisionService(
            remediationAdvisor,
            new InterventionProperties.AutomatedDecisionService(false, false, 100, null, null)
    );
    private final InterventionRemediationCommandPlanner remediationCommandPlanner =
            new InterventionRemediationCommandPlanner(projection);
    private final InterventionRemediationExecutorService remediationExecutorService = remediationExecutorService(
            remediationCommandPlanner,
            new InterventionProperties.RemediationExecutorPolicy(
                    false,
                    false,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    25,
                    List.of()
            )
    );
    private final InterventionOperatorController controller = new InterventionOperatorController(
            service,
            remediationDecisionService,
            automatedDecisionService,
            remediationAdvisor,
            remediationExecutorService,
            projection,
            new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
    );
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    @Test
    void accepts_order_acknowledgement_when_token_matches_projection() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("accepted")
                .jsonPath("$.eventType")
                .isEqualTo("INTERVENTION_ACKNOWLEDGEMENT");

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
    }

    @Test
    void rejects_order_acknowledgement_when_token_is_invalid() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void lists_unresolved_order_interventions_when_token_matches() {
        restoreOrderIntervention("external_order_observed", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/orders")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.interventions[0].symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.interventions[0].clientOrderId")
                .isEqualTo("client-1")
                .jsonPath("$.interventions[0].interventionReason")
                .isEqualTo("external_order_observed");
    }

    @Test
    void rejects_order_intervention_listing_when_token_is_invalid() {
        restoreOrderIntervention("external_order_observed", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/orders")
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

    @Test
    void lists_unresolved_position_interventions_when_token_matches() {
        restorePositionIntervention("external_position_change", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/positions")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.interventions[0].symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.interventions[0].positionSide")
                .isEqualTo("BOTH")
                .jsonPath("$.interventions[0].positionAmount")
                .isEqualTo("0")
                .jsonPath("$.interventions[0].interventionReason")
                .isEqualTo("external_position_change");
    }

    @Test
    void rejects_position_intervention_listing_when_token_is_invalid() {
        restorePositionIntervention("external_position_change", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/positions")
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

    @Test
    void lists_pending_manual_review_decisions_when_token_matches() {
        restoreManualReviewDecision();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/manual-reviews")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.decisions[0].commandId")
                .isEqualTo("cmd-1")
                .jsonPath("$.decisions[0].decisionId")
                .isEqualTo("risk-decision:cmd-1")
                .jsonPath("$.decisions[0].reasons[0]")
                .isEqualTo("intervention:external_order")
                .jsonPath("$.decisions[0].attributes.external_order_intervention_action")
                .isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void rejects_manual_review_listing_when_token_is_invalid() {
        restoreManualReviewDecision();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/manual-reviews")
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

    @Test
    void lists_remediation_recommendations_when_token_matches() {
        restoreManualReviewDecision();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(2)
                .jsonPath("$.recommendations[0].scope")
                .isEqualTo("ORDER")
                .jsonPath("$.recommendations[0].action")
                .isEqualTo("OPERATOR_REVIEW")
                .jsonPath("$.recommendations[0].clientOrderId")
                .isEqualTo("client-1")
                .jsonPath("$.recommendations[0].reasons[0]")
                .isEqualTo("intervention:external_order_observed")
                .jsonPath("$.recommendations[1].scope")
                .isEqualTo("MANUAL_REVIEW")
                .jsonPath("$.recommendations[1].action")
                .isEqualTo("OPERATOR_REVIEW")
                .jsonPath("$.recommendations[1].attributes.decision_id")
                .isEqualTo("risk-decision:cmd-1");
    }

    @Test
    void rejects_remediation_recommendation_listing_when_token_is_invalid() {
        restoreManualReviewDecision();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation")
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

    @Test
    void lists_remediation_decisions_when_token_matches() {
        restoreRemediationDecision();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation/decisions")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.decisions[0].remediationId")
                .isEqualTo("remediation-1")
                .jsonPath("$.decisions[0].scope")
                .isEqualTo("ORDER")
                .jsonPath("$.decisions[0].action")
                .isEqualTo("OPERATOR_REVIEW")
                .jsonPath("$.decisions[0].clientOrderId")
                .isEqualTo("client-1")
                .jsonPath("$.decisions[0].decidedBy")
                .isEqualTo("operator")
                .jsonPath("$.decisions[0].attributes.ticket")
                .isEqualTo("ops-789");
    }

    @Test
    void lists_remediation_command_plans_when_token_matches() {
        restoreCloseRemediationDecisionWithOrder();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation/plans")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.plans[0].remediationId")
                .isEqualTo("remediation-1")
                .jsonPath("$.plans[0].status")
                .isEqualTo("READY")
                .jsonPath("$.plans[0].operation")
                .isEqualTo("CANCEL_ORDER")
                .jsonPath("$.plans[0].exchangeExecutable")
                .isEqualTo(false)
                .jsonPath("$.plans[0].reasons[0]")
                .isEqualTo("remediation:cancel_external_order")
                .jsonPath("$.plans[0].attributes.target_exchange_order_id")
                .isEqualTo("12345")
                .jsonPath("$.plans[0].attributes.exchange_executable")
                .isEqualTo("false");
    }

    @Test
    void rejects_remediation_command_plan_listing_when_token_is_invalid() {
        restoreCloseRemediationDecisionWithOrder();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation/plans")
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

    @Test
    void dry_runs_remediation_executor_reports_when_token_matches() {
        InterventionOperatorController enabledController = controller(
                remediationAdvisor,
                automatedDecisionService,
                remediationExecutorService(remediationCommandPlanner, enabledDryRunExecutorPolicy())
        );
        WebTestClient enabledClient = WebTestClient.bindToController(enabledController).build();
        restoreCloseRemediationDecisionWithOrder();

        enabledClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation/executor/dry-run")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.exchangeExecutionEnabled")
                .isEqualTo(false)
                .jsonPath("$.dryRunOnly")
                .isEqualTo(true)
                .jsonPath("$.evaluatedCount")
                .isEqualTo(1)
                .jsonPath("$.blockedCount")
                .isEqualTo(1)
                .jsonPath("$.reports[0].remediationId")
                .isEqualTo("remediation-1")
                .jsonPath("$.reports[0].planStatus")
                .isEqualTo("READY")
                .jsonPath("$.reports[0].operation")
                .isEqualTo("CANCEL_ORDER")
                .jsonPath("$.reports[0].status")
                .isEqualTo("BLOCKED")
                .jsonPath("$.reports[0].exchangeExecutable")
                .isEqualTo(false)
                .jsonPath("$.reports[0].reasons[1]")
                .isEqualTo("executor:plan_not_exchange_executable")
                .jsonPath("$.reports[0].attributes.executor_reason")
                .isEqualTo("executor:plan_not_exchange_executable")
                .jsonPath("$.reports[0].plan.operation")
                .isEqualTo("CANCEL_ORDER");
    }

    @Test
    void rejects_remediation_executor_dry_run_when_token_is_invalid() {
        restoreCloseRemediationDecisionWithOrder();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/remediation/executor/dry-run")
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

    @Test
    void accepts_remediation_decision_when_token_matches_current_recommendation() {
        restoreManualReviewDecision();

        client.post()
                .uri("/internal/interventions/remediation/decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(remediationDecisionRequest())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("accepted")
                .jsonPath("$.eventType")
                .isEqualTo("REMEDIATION_DECISION");

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
    }

    @Test
    void triggers_automated_remediation_decisions_when_token_matches() {
        InterventionRemediationAdvisor automatedAdvisor = new InterventionRemediationAdvisor(
                projection,
                new InterventionProperties.AutomatedPolicy(
                        InterventionProperties.RemediationAction.CLOSE,
                        null,
                        null,
                        null,
                        null
                )
        );
        InterventionOperatorController enabledController = controller(
                automatedAdvisor,
                automatedDecisionService(
                        automatedAdvisor,
                        new InterventionProperties.AutomatedDecisionService(true, false, 100, null, null)
                )
        );
        WebTestClient enabledClient = WebTestClient.bindToController(enabledController).build();
        restoreOrderIntervention("external_order_observed", true);

        enabledClient.post()
                .uri("/internal/interventions/remediation/automated-decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(automatedDecisionRequest())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.publishedCount")
                .isEqualTo(1)
                .jsonPath("$.skippedCount")
                .isEqualTo(0)
                .jsonPath("$.outcomes[0].status")
                .isEqualTo("PUBLISHED")
                .jsonPath("$.outcomes[0].action")
                .isEqualTo("CLOSE")
                .jsonPath("$.outcomes[0].reason")
                .isEqualTo("automated_decision:published");

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getAction()).hasToString("CLOSE");
        assertThat(event.getDecidedBy()).hasToString("automated_remediation_policy");
        assertThat(event.getAttributes())
                .containsEntry("recommendation_event_id", "evt-order-intervention")
                .containsEntry("automated_decision_service", "true");
    }

    @Test
    void returns_disabled_automated_decision_batch_when_service_is_disabled() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/remediation/automated-decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(automatedDecisionRequest())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.publishedCount")
                .isEqualTo(0)
                .jsonPath("$.skippedCount")
                .isEqualTo(0)
                .jsonPath("$.outcomes.length()")
                .isEqualTo(0);

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_automated_remediation_decision_trigger_when_token_is_invalid() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/remediation/automated-decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .bodyValue(automatedDecisionRequest())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void maps_stale_remediation_decision_to_conflict() {
        restoreManualReviewDecision();

        client.post()
                .uri("/internal/interventions/remediation/decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(Map.of(
                        "provider", "binance",
                        "environment", "demo",
                        "account", "main",
                        "market", "usd_m_futures",
                        "symbol", "BTCUSDT",
                        "scope", "ORDER",
                        "action", "HEDGE_OR_REPLAN",
                        "clientOrderId", "client-1",
                        "decidedBy", "operator",
                        "decisionReason", "wrong action"
                ))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("conflict")
                .jsonPath("$.message")
                .isEqualTo("No matching remediation recommendation exists");
    }

    @Test
    void rejects_remediation_decision_when_token_is_invalid() {
        restoreManualReviewDecision();

        client.post()
                .uri("/internal/interventions/remediation/decisions")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .bodyValue(remediationDecisionRequest())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    @Test
    void accepts_position_acknowledgement_when_token_matches_projection() {
        restorePositionIntervention("external_position_change", true);

        client.post()
                .uri("/internal/interventions/positions/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(positionAcknowledgementRequest())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("accepted")
                .jsonPath("$.eventType")
                .isEqualTo("INTERVENTION_ACKNOWLEDGEMENT");

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        InterventionAcknowledgementEvent event = (InterventionAcknowledgementEvent) eventBus.envelope.value();
        assertThat(event.getClientOrderId()).isNull();
    }

    @Test
    void rejects_position_acknowledgement_when_token_is_invalid() {
        restorePositionIntervention("external_position_change", true);

        client.post()
                .uri("/internal/interventions/positions/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .bodyValue(positionAcknowledgementRequest())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void maps_position_acknowledgement_projection_rejection_to_conflict() {
        client.post()
                .uri("/internal/interventions/positions/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(positionAcknowledgementRequest())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("conflict")
                .jsonPath("$.message")
                .isEqualTo("No projected position exists for acknowledgement");

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void maps_projection_rejection_to_conflict() {
        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("conflict")
                .jsonPath("$.message")
                .isEqualTo("No projected order exists for acknowledgement");

        assertThat(eventBus.envelope).isNull();
    }

    private Map<String, Object> request() {
        return Map.of(
                "provider", "binance",
                "environment", "demo",
                "account", "main",
                "market", "usd_m_futures",
                "symbol", "BTCUSDT",
                "clientOrderId", "client-1",
                "interventionReason", "external_order_observed",
                "acknowledgedBy", "operator",
                "acknowledgementReason", "reviewed in exchange console",
                "attributes", Map.of("ticket", "ops-123")
        );
    }

    private Map<String, Object> positionAcknowledgementRequest() {
        return Map.of(
                "provider", "binance",
                "environment", "demo",
                "account", "main",
                "market", "usd_m_futures",
                "symbol", "BTCUSDT",
                "positionSide", "BOTH",
                "interventionReason", "external_position_change",
                "acknowledgedBy", "operator",
                "acknowledgementReason", "reviewed in exchange console",
                "attributes", Map.of("ticket", "ops-456")
        );
    }

    private Map<String, Object> remediationDecisionRequest() {
        return Map.ofEntries(
                Map.entry("provider", "binance"),
                Map.entry("environment", "demo"),
                Map.entry("account", "main"),
                Map.entry("market", "usd_m_futures"),
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("scope", "ORDER"),
                Map.entry("action", "OPERATOR_REVIEW"),
                Map.entry("clientOrderId", "client-1"),
                Map.entry("decidedBy", "operator"),
                Map.entry("decisionReason", "reviewed current projection"),
                Map.entry("attributes", Map.of("ticket", "ops-789"))
        );
    }

    private Map<String, Object> automatedDecisionRequest() {
        return Map.of(
                "provider", "binance",
                "environment", "demo",
                "account", "main",
                "market", "usd_m_futures"
        );
    }

    private InterventionOperatorController controller(
            InterventionRemediationAdvisor advisor,
            InterventionAutomatedDecisionService automatedDecisionService
    ) {
        return controller(advisor, automatedDecisionService, remediationExecutorService);
    }

    private InterventionOperatorController controller(
            InterventionRemediationAdvisor advisor,
            InterventionAutomatedDecisionService automatedDecisionService,
            InterventionRemediationExecutorService remediationExecutorService
    ) {
        InterventionRemediationDecisionService decisionService = new InterventionRemediationDecisionService(
                eventBus,
                advisor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "remediation-001"
        );
        return new InterventionOperatorController(
                service,
                decisionService,
                automatedDecisionService,
                advisor,
                remediationExecutorService,
                projection,
                new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
        );
    }

    private InterventionProperties.RemediationExecutorPolicy enabledDryRunExecutorPolicy() {
        return new InterventionProperties.RemediationExecutorPolicy(
                true,
                false,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                25,
                List.of()
        );
    }

    private InterventionRemediationExecutorService remediationExecutorService(
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties.RemediationExecutorPolicy policy
    ) {
        return new InterventionRemediationExecutorService(
                projection,
                commandPlanner,
                new InterventionProperties(null, null, null, null, policy)
        );
    }

    private InterventionAutomatedDecisionService automatedDecisionService(
            InterventionRemediationAdvisor advisor,
            InterventionProperties.AutomatedDecisionService properties
    ) {
        return new InterventionAutomatedDecisionService(
                eventBus,
                advisor,
                projection,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "auto-001"
        );
    }

    private void restoreOrderIntervention(String interventionReason, boolean externalIntervention) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "USER_DATA",
                        "NEW",
                        false,
                        externalIntervention,
                        interventionReason,
                        NOW.minusSeconds(1),
                        "evt-order-intervention"
                )),
                List.of(),
                List.of()
        ));
    }

    private void restorePositionIntervention(String interventionReason, boolean externalIntervention) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "BOTH",
                        "0",
                        "50000.00",
                        "50010.00",
                        "0",
                        "USER_DATA",
                        externalIntervention,
                        interventionReason,
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private void restoreManualReviewDecision() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "USER_DATA",
                        "NEW",
                        false,
                        true,
                        "external_order_observed",
                        NOW.minusSeconds(1),
                        "evt-order-intervention"
                )),
                List.of(),
                List.of(new TradingStateProjection.ManualReviewDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-1",
                        "sig-1",
                        "lfa",
                        "risk-decision:cmd-1",
                        List.of("intervention:external_order"),
                        Map.of("external_order_intervention_action", "MANUAL_REVIEW"),
                        NOW.minusSeconds(1),
                        "evt-risk-decision-review"
                )),
                List.of()
        ));
    }

    private void restoreRemediationDecision() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "remediation-1",
                        "ORDER",
                        "OPERATOR_REVIEW",
                        "client-1",
                        null,
                        "external_order_observed",
                        List.of("intervention:external_order_observed"),
                        "operator",
                        "reviewed current projection",
                        Map.of("ticket", "ops-789"),
                        NOW.minusSeconds(1),
                        "evt-remediation-decision"
                )),
                List.of("evt-remediation-decision")
        ));
    }

    private void restoreCloseRemediationDecisionWithOrder() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "USER_DATA",
                        "NEW",
                        false,
                        true,
                        "external_order_observed",
                        NOW.minusSeconds(1),
                        "evt-order-intervention"
                )),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "remediation-1",
                        "ORDER",
                        "CLOSE",
                        "client-1",
                        null,
                        "external_order_observed",
                        List.of("intervention:external_order_observed"),
                        "automated_remediation_policy",
                        "policy action selected",
                        Map.of("ticket", "ops-790"),
                        NOW.minusSeconds(1),
                        "evt-remediation-decision"
                )),
                List.of("evt-remediation-decision")
        ));
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private TradingEventEnvelope<? extends SpecificRecord> envelope;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            this.envelope = envelope;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    1
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
