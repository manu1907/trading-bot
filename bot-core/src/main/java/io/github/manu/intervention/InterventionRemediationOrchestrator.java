package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.messaging.TradingEventHandler;
import io.github.manu.projection.TradingStateProjection;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class InterventionRemediationOrchestrator implements TradingEventHandler {

    private static final String SCOPE_ORDER = "ORDER";
    private static final String SCOPE_POSITION = "POSITION";
    private static final String ACTION_OPERATOR_REVIEW = "OPERATOR_REVIEW";

    private final TradingEventBus eventBus;
    private final TradingStateProjection projection;
    private final InterventionProperties.RemediationOrchestrator properties;
    private final LinkedHashSet<String> orchestratedRemediationIds = new LinkedHashSet<>();
    private final Object lock = new Object();

    public InterventionRemediationOrchestrator(
            TradingEventBus eventBus,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.properties = Objects.requireNonNull(properties, "properties").remediationOrchestrator();
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.REMEDIATION_DECISION) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected REMEDIATION_DECISION envelope"));
        }
        return orchestrate(cast(envelope.value(), RemediationDecisionEvent.class));
    }

    public CompletableFuture<Void> orchestrate(RemediationDecisionEvent event) {
        Objects.requireNonNull(event, "event");
        if (!properties.enabled() || !properties.operatorReviewAcknowledgementEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        String action = requireText(event.getAction(), "action");
        if (!ACTION_OPERATOR_REVIEW.equals(action)) {
            return CompletableFuture.completedFuture(null);
        }
        String scope = requireText(event.getScope(), "scope");
        return switch (scope) {
            case SCOPE_ORDER -> acknowledgeOrder(event);
            case SCOPE_POSITION -> acknowledgePosition(event);
            default -> CompletableFuture.completedFuture(null);
        };
    }

    private CompletableFuture<Void> acknowledgeOrder(RemediationDecisionEvent event) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        String clientOrderId = requireText(event.getClientOrderId(), "clientOrderId");
        String interventionReason = requireText(event.getInterventionReason(), "interventionReason");
        TradingStateProjection.OrderState order = projection.order(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        clientOrderId
                )
                .orElseThrow(() -> new IllegalStateException("No projected order exists for remediation"));
        if (!order.externalIntervention()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!interventionReason.equals(order.interventionReason())) {
            throw new IllegalStateException("Projected order intervention reason does not match remediation");
        }
        String remediationId = requireText(event.getRemediationId(), "remediationId");
        if (!admit(remediationId)) {
            return CompletableFuture.completedFuture(null);
        }
        InterventionAcknowledgementEvent acknowledgement = acknowledgementBuilder(event)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setClientOrderId(clientOrderId)
                .setInterventionReason(interventionReason)
                .setAttributes(attributes(event, null))
                .build();
        return eventBus.publish(TradingEventEnvelope.of(
                        TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                        TradingEventKeys.order(
                                TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                                provider,
                                environment,
                                account,
                                market,
                                symbol,
                                clientOrderId
                        ),
                        acknowledgement
                ))
                .whenComplete((ignored, failure) -> forgetOnFailure(remediationId, failure))
                .thenApply(ignored -> null);
    }

    private CompletableFuture<Void> acknowledgePosition(RemediationDecisionEvent event) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        String positionSide = requireText(event.getPositionSide(), "positionSide");
        String interventionReason = requireText(event.getInterventionReason(), "interventionReason");
        TradingStateProjection.PositionState position = projection.position(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        positionSide
                )
                .orElseThrow(() -> new IllegalStateException("No projected position exists for remediation"));
        if (!position.externalIntervention()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!interventionReason.equals(position.interventionReason())) {
            throw new IllegalStateException("Projected position intervention reason does not match remediation");
        }
        String remediationId = requireText(event.getRemediationId(), "remediationId");
        if (!admit(remediationId)) {
            return CompletableFuture.completedFuture(null);
        }
        InterventionAcknowledgementEvent acknowledgement = acknowledgementBuilder(event)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setClientOrderId(null)
                .setInterventionReason(interventionReason)
                .setAttributes(attributes(event, positionSide))
                .build();
        return eventBus.publish(TradingEventEnvelope.of(
                        TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                        TradingEventKeys.symbol(
                                TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                                provider,
                                environment,
                                account,
                                market,
                                symbol
                        ),
                        acknowledgement
                ))
                .whenComplete((ignored, failure) -> forgetOnFailure(remediationId, failure))
                .thenApply(ignored -> null);
    }

    private boolean admit(String remediationId) {
        synchronized (lock) {
            if (!orchestratedRemediationIds.add(remediationId)) {
                return false;
            }
            while (orchestratedRemediationIds.size() > properties.maxTrackedDecisionIds()) {
                orchestratedRemediationIds.remove(orchestratedRemediationIds.getFirst());
            }
            return true;
        }
    }

    private void forgetOnFailure(String remediationId, Throwable failure) {
        if (failure == null) {
            return;
        }
        synchronized (lock) {
            orchestratedRemediationIds.remove(remediationId);
        }
    }

    private InterventionAcknowledgementEvent.Builder acknowledgementBuilder(RemediationDecisionEvent event) {
        String remediationId = requireText(event.getRemediationId(), "remediationId");
        return InterventionAcknowledgementEvent.newBuilder()
                .setEventId("evt:intervention-ack:" + remediationId)
                .setSchemaVersion(1)
                .setAcknowledgementId("intervention-ack:" + remediationId)
                .setAcknowledgedBy(requireText(event.getDecidedBy(), "decidedBy"))
                .setAcknowledgementReason(requireText(event.getDecisionReason(), "decisionReason"))
                .setAcknowledgedAtMicros(event.getDecidedAtMicros());
    }

    private Map<CharSequence, CharSequence> attributes(RemediationDecisionEvent event, String positionSide) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (event.getAttributes() != null) {
            attributes.putAll(event.getAttributes());
        }
        put(attributes, "remediation_id", value(event.getRemediationId()));
        put(attributes, "remediation_event_id", value(event.getEventId()));
        put(attributes, "remediation_scope", value(event.getScope()));
        put(attributes, "remediation_action", value(event.getAction()));
        put(attributes, "position_side", positionSide);
        return Map.copyOf(attributes);
    }

    private void put(Map<CharSequence, CharSequence> attributes, String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private <T> T cast(Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private String requireText(CharSequence value, String field) {
        String text = value(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
