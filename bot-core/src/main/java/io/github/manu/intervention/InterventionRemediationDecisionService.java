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
        RemediationTargetIdentity targetIdentity = targetIdentity(
                scope,
                text(request.symbol()),
                text(request.clientOrderId()),
                text(request.positionSide()),
                request.attributes()
        );
        InterventionRemediationAdvisor.RemediationRecommendation recommendation = matchingRecommendation(
                provider,
                environment,
                account,
                market,
                scope,
                action,
                targetIdentity
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
            RemediationTargetIdentity targetIdentity
    ) {
        return remediationAdvisor.recommendations(provider, environment, account, market)
                .stream()
                .filter(recommendation -> scope.equals(recommendation.scope()))
                .filter(recommendation -> action.equals(recommendation.action()))
                .filter(targetIdentity::matches)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No matching remediation recommendation exists"));
    }

    private RemediationTargetIdentity targetIdentity(
            String scope,
            String symbol,
            String clientOrderId,
            String positionSide,
            Map<CharSequence, CharSequence> attributes
    ) {
        return switch (scope) {
            case "ORDER" -> new RemediationTargetIdentity(
                    requireText(symbol, "symbol"),
                    requireText(clientOrderId, "clientOrderId"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            case "POSITION" -> new RemediationTargetIdentity(
                    requireText(symbol, "symbol"),
                    null,
                    requireText(positionSide, "positionSide"),
                    null,
                    null,
                    null,
                    null,
                    null
            );
            case "MANUAL_REVIEW" -> manualReviewIdentity(symbol, clientOrderId, attributes);
            default -> new RemediationTargetIdentity(symbol, clientOrderId, positionSide, null, null, null, null, null);
        };
    }

    private RemediationTargetIdentity manualReviewIdentity(
            String symbol,
            String clientOrderId,
            Map<CharSequence, CharSequence> attributes
    ) {
        String commandId = attribute(attributes, "command_id");
        String decisionId = attribute(attributes, "decision_id");
        String affectedCommandId = attribute(attributes, "affected_order_command_id");
        String affectedExchangeOrderId = attribute(attributes, "affected_exchange_order_id");
        if (commandId == null
                && decisionId == null
                && clientOrderId == null
                && affectedCommandId == null
                && affectedExchangeOrderId == null) {
            throw new IllegalArgumentException(
                    "manual review remediation requires command_id, decision_id, clientOrderId, "
                            + "affected_order_command_id, or affected_exchange_order_id"
            );
        }
        return new RemediationTargetIdentity(
                symbol,
                null,
                null,
                commandId,
                decisionId,
                affectedCommandId,
                clientOrderId,
                affectedExchangeOrderId
        );
    }

    private String attribute(Map<CharSequence, CharSequence> attributes, String key) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (Map.Entry<CharSequence, CharSequence> entry : attributes.entrySet()) {
            if (key.equals(text(entry.getKey()))) {
                return text(entry.getValue());
            }
        }
        return null;
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

    private String text(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private record RemediationTargetIdentity(
            String symbol,
            String clientOrderId,
            String positionSide,
            String commandId,
            String decisionId,
            String affectedCommandId,
            String affectedClientOrderId,
            String affectedExchangeOrderId
    ) {

        private boolean matches(InterventionRemediationAdvisor.RemediationRecommendation recommendation) {
            return matches(symbol, recommendation.symbol())
                    && matches(clientOrderId, recommendation.clientOrderId())
                    && matches(positionSide, recommendation.positionSide())
                    && matches(commandId, recommendation.attributes().get("command_id"))
                    && matches(decisionId, recommendation.attributes().get("decision_id"))
                    && matchesAny(
                            affectedCommandId,
                            recommendation.attributes(),
                            "unknown_order_command_ids",
                            "unresolved_order_command_ids"
                    )
                    && matchesAny(
                            affectedClientOrderId,
                            recommendation.attributes(),
                            "unknown_order_client_order_ids",
                            "unresolved_order_client_order_ids",
                            "target_client_order_id"
                    )
                    && matchesAny(
                            affectedExchangeOrderId,
                            recommendation.attributes(),
                            "unknown_order_exchange_order_ids",
                            "unresolved_order_exchange_order_ids",
                            "target_exchange_order_id"
                    );
        }

        private boolean matches(String requested, String projected) {
            return requested == null || requested.equals(projected);
        }

        private boolean matchesAny(
                String requested,
                Map<String, String> attributes,
                String... keys
        ) {
            if (requested == null) {
                return true;
            }
            for (String key : keys) {
                if (contains(attributes.get(key), requested)) {
                    return true;
                }
            }
            return false;
        }

        private boolean contains(String projected, String requested) {
            if (projected == null) {
                return false;
            }
            for (String value : projected.split(",")) {
                if (requested.equals(value.trim())) {
                    return true;
                }
            }
            return false;
        }
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
