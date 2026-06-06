package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class InterventionAutomatedDecisionService {

    private static final String ACTION_OPERATOR_REVIEW = "OPERATOR_REVIEW";
    private static final String ATTRIBUTE_RECOMMENDATION_EVENT_ID = "recommendation_event_id";

    private final TradingEventBus eventBus;
    private final InterventionRemediationAdvisor remediationAdvisor;
    private final TradingStateProjection projection;
    private final InterventionProperties.AutomatedDecisionService properties;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    public InterventionAutomatedDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        this(
                eventBus,
                remediationAdvisor,
                projection,
                properties.automatedDecisionService(),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString()
        );
    }

    InterventionAutomatedDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor,
            TradingStateProjection projection,
            InterventionProperties.AutomatedDecisionService properties,
            Clock clock,
            Supplier<String> idSupplier
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.remediationAdvisor = Objects.requireNonNull(remediationAdvisor, "remediationAdvisor");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CompletableFuture<AutomatedDecisionBatch> decide(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String targetProvider = requireText(provider, "provider");
        String targetEnvironment = requireText(environment, "environment");
        String targetAccount = requireText(account, "account");
        String targetMarket = requireText(market, "market");
        if (!properties.enabled()) {
            return CompletableFuture.completedFuture(new AutomatedDecisionBatch(false, List.of()));
        }
        List<InterventionRemediationAdvisor.RemediationRecommendation> recommendations =
                remediationAdvisor.recommendations(targetProvider, targetEnvironment, targetAccount, targetMarket);
        List<AutomatedDecisionOutcome> outcomes = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        int publishedCandidates = 0;
        for (InterventionRemediationAdvisor.RemediationRecommendation recommendation : recommendations) {
            AutomatedDecisionOutcome skip = skipOutcome(recommendation, publishedCandidates);
            if (skip != null) {
                outcomes.add(skip);
                continue;
            }
            publishedCandidates++;
            RemediationDecisionEvent event = event(recommendation);
            outcomes.add(outcome(
                    recommendation,
                    event.getRemediationId().toString(),
                    AutomatedDecisionStatus.PUBLISHED,
                    "automated_decision:published"
            ));
            chain = chain.thenCompose(ignored -> eventBus.publish(envelope(event)).thenApply(this::ignore));
        }
        return chain.thenApply(ignored -> new AutomatedDecisionBatch(true, outcomes));
    }

    private AutomatedDecisionOutcome skipOutcome(
            InterventionRemediationAdvisor.RemediationRecommendation recommendation,
            int publishedCandidates
    ) {
        if (ACTION_OPERATOR_REVIEW.equals(recommendation.action()) && !properties.includeOperatorReviewActions()) {
            return outcome(
                    recommendation,
                    null,
                    AutomatedDecisionStatus.SKIPPED,
                    "automated_decision:operator_review_disabled"
            );
        }
        if (publishedCandidates >= properties.maxDecisionsPerRun()) {
            return outcome(
                    recommendation,
                    null,
                    AutomatedDecisionStatus.SKIPPED,
                    "automated_decision:max_decisions_per_run_reached"
            );
        }
        if (alreadyDecided(recommendation)) {
            return outcome(
                    recommendation,
                    null,
                    AutomatedDecisionStatus.SKIPPED,
                    "automated_decision:duplicate_recommendation"
            );
        }
        return null;
    }

    private boolean alreadyDecided(InterventionRemediationAdvisor.RemediationRecommendation recommendation) {
        String recommendationEventId = text(recommendation.eventId());
        if (recommendationEventId == null) {
            return false;
        }
        return projection.remediationDecisionStates(
                        recommendation.provider(),
                        recommendation.environment(),
                        recommendation.account(),
                        recommendation.market()
                )
                .stream()
                .anyMatch(decision -> recommendationEventId.equals(decision.attributes().get(ATTRIBUTE_RECOMMENDATION_EVENT_ID)));
    }

    private RemediationDecisionEvent event(InterventionRemediationAdvisor.RemediationRecommendation recommendation) {
        String remediationId = "remediation:auto:" + requireText(idSupplier.get(), "generated remediation id");
        return RemediationDecisionEvent.newBuilder()
                .setEventId("evt:" + remediationId)
                .setSchemaVersion(1)
                .setRemediationId(remediationId)
                .setProvider(recommendation.provider())
                .setEnvironment(recommendation.environment())
                .setAccount(recommendation.account())
                .setMarket(recommendation.market())
                .setSymbol(recommendation.symbol())
                .setScope(recommendation.scope())
                .setAction(recommendation.action())
                .setClientOrderId(recommendation.clientOrderId())
                .setPositionSide(recommendation.positionSide())
                .setInterventionReason(recommendation.interventionReason())
                .setReasons(List.copyOf(recommendation.reasons()))
                .setDecidedBy(properties.decidedBy())
                .setDecisionReason(properties.decisionReason())
                .setDecidedAtMicros(Instant.now(clock))
                .setAttributes(attributes(recommendation))
                .build();
    }

    private Map<CharSequence, CharSequence> attributes(
            InterventionRemediationAdvisor.RemediationRecommendation recommendation
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        recommendation.attributes().forEach(attributes::put);
        put(attributes, ATTRIBUTE_RECOMMENDATION_EVENT_ID, recommendation.eventId());
        if (recommendation.updatedAt() != null) {
            put(attributes, "recommendation_updated_at", recommendation.updatedAt().toString());
        }
        put(attributes, "automated_decision_service", "true");
        return Map.copyOf(attributes);
    }

    private AutomatedDecisionOutcome outcome(
            InterventionRemediationAdvisor.RemediationRecommendation recommendation,
            String remediationId,
            AutomatedDecisionStatus status,
            String reason
    ) {
        return new AutomatedDecisionOutcome(
                remediationId,
                recommendation.scope(),
                recommendation.action(),
                recommendation.provider(),
                recommendation.environment(),
                recommendation.account(),
                recommendation.market(),
                recommendation.symbol(),
                recommendation.clientOrderId(),
                recommendation.positionSide(),
                status,
                reason,
                recommendation.eventId()
        );
    }

    private TradingEventEnvelope<RemediationDecisionEvent> envelope(RemediationDecisionEvent event) {
        if (event.getClientOrderId() != null) {
            return TradingEventEnvelope.of(
                    TradingEventType.REMEDIATION_DECISION,
                    TradingEventKeys.order(
                            TradingEventType.REMEDIATION_DECISION,
                            event.getProvider().toString(),
                            event.getEnvironment().toString(),
                            event.getAccount().toString(),
                            event.getMarket().toString(),
                            event.getSymbol() == null ? null : event.getSymbol().toString(),
                            event.getClientOrderId().toString()
                    ),
                    event
            );
        }
        if (event.getSymbol() != null) {
            return TradingEventEnvelope.of(
                    TradingEventType.REMEDIATION_DECISION,
                    TradingEventKeys.symbol(
                            TradingEventType.REMEDIATION_DECISION,
                            event.getProvider().toString(),
                            event.getEnvironment().toString(),
                            event.getAccount().toString(),
                            event.getMarket().toString(),
                            event.getSymbol().toString()
                    ),
                    event
            );
        }
        return TradingEventEnvelope.of(
                TradingEventType.REMEDIATION_DECISION,
                TradingEventKeys.account(
                        TradingEventType.REMEDIATION_DECISION,
                        event.getProvider().toString(),
                        event.getEnvironment().toString(),
                        event.getAccount().toString(),
                        event.getMarket().toString()
                ),
                event
        );
    }

    private Void ignore(PublishedTradingEvent ignored) {
        return null;
    }

    private void put(Map<CharSequence, CharSequence> attributes, String key, String value) {
        String text = text(value);
        if (text != null) {
            attributes.put(key, text);
        }
    }

    private String requireText(String value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public enum AutomatedDecisionStatus {
        PUBLISHED,
        SKIPPED
    }

    public record AutomatedDecisionBatch(
            boolean enabled,
            List<AutomatedDecisionOutcome> outcomes
    ) {

        public AutomatedDecisionBatch {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        public long publishedCount() {
            return outcomes.stream().filter(outcome -> outcome.status() == AutomatedDecisionStatus.PUBLISHED).count();
        }

        public long skippedCount() {
            return outcomes.stream().filter(outcome -> outcome.status() == AutomatedDecisionStatus.SKIPPED).count();
        }
    }

    public record AutomatedDecisionOutcome(
            String remediationId,
            String scope,
            String action,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String positionSide,
            AutomatedDecisionStatus status,
            String reason,
            String recommendationEventId
    ) {
    }
}
