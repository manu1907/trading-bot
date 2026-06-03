package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class InterventionRemediationDecisionService {

    private final TradingEventBus eventBus;
    private final InterventionRemediationAdvisor remediationAdvisor;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    public InterventionRemediationDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor
    ) {
        this(eventBus, remediationAdvisor, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    InterventionRemediationDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor,
            Clock clock,
            Supplier<String> idSupplier
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.remediationAdvisor = Objects.requireNonNull(remediationAdvisor, "remediationAdvisor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CompletableFuture<PublishedTradingEvent> decide(RemediationDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        String provider = requireText(request.provider(), "provider");
        String environment = requireText(request.environment(), "environment");
        String account = requireText(request.account(), "account");
        String market = requireText(request.market(), "market");
        String scope = requireText(request.scope(), "scope");
        String action = requireText(request.action(), "action");
        InterventionRemediationAdvisor.RemediationRecommendation recommendation = matchingRecommendation(
                provider,
                environment,
                account,
                market,
                scope,
                action,
                text(request.symbol()),
                text(request.clientOrderId()),
                text(request.positionSide())
        );

        String remediationId = "remediation:" + requireText(idSupplier.get(), "generated remediation id");
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId("evt:" + remediationId)
                .setSchemaVersion(1)
                .setRemediationId(remediationId)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(recommendation.symbol())
                .setScope(recommendation.scope())
                .setAction(recommendation.action())
                .setClientOrderId(recommendation.clientOrderId())
                .setPositionSide(recommendation.positionSide())
                .setInterventionReason(recommendation.interventionReason())
                .setReasons(reasons(recommendation))
                .setDecidedBy(requireText(request.decidedBy(), "decidedBy"))
                .setDecisionReason(requireText(request.decisionReason(), "decisionReason"))
                .setDecidedAtMicros(Instant.now(clock))
                .setAttributes(decisionAttributes(recommendation, request.attributes()))
                .build();
        return eventBus.publish(envelope(event));
    }

    private InterventionRemediationAdvisor.RemediationRecommendation matchingRecommendation(
            String provider,
            String environment,
            String account,
            String market,
            String scope,
            String action,
            String symbol,
            String clientOrderId,
            String positionSide
    ) {
        return remediationAdvisor.recommendations(provider, environment, account, market)
                .stream()
                .filter(recommendation -> scope.equals(recommendation.scope()))
                .filter(recommendation -> action.equals(recommendation.action()))
                .filter(recommendation -> matches(symbol, recommendation.symbol()))
                .filter(recommendation -> matches(clientOrderId, recommendation.clientOrderId()))
                .filter(recommendation -> matches(positionSide, recommendation.positionSide()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No matching remediation recommendation exists"));
    }

    private boolean matches(String requested, String projected) {
        return requested == null || requested.equals(projected);
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

    private Map<CharSequence, CharSequence> decisionAttributes(
            InterventionRemediationAdvisor.RemediationRecommendation recommendation,
            Map<CharSequence, CharSequence> requestAttributes
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        recommendation.attributes().forEach(attributes::put);
        if (requestAttributes != null) {
            attributes.putAll(requestAttributes);
        }
        put(attributes, "recommendation_event_id", recommendation.eventId());
        if (recommendation.updatedAt() != null) {
            put(attributes, "recommendation_updated_at", recommendation.updatedAt().toString());
        }
        return Map.copyOf(attributes);
    }

    private java.util.List<CharSequence> reasons(
            InterventionRemediationAdvisor.RemediationRecommendation recommendation
    ) {
        return java.util.List.copyOf(recommendation.reasons());
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

    public record RemediationDecisionRequest(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String scope,
            String action,
            String clientOrderId,
            String positionSide,
            String decidedBy,
            String decisionReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        public RemediationDecisionRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
