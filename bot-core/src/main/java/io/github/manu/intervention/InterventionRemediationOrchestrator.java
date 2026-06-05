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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class InterventionRemediationOrchestrator implements TradingEventHandler {

    private static final String SCOPE_ORDER = "ORDER";
    private static final String SCOPE_POSITION = "POSITION";
    private static final String SCOPE_MANUAL_REVIEW = "MANUAL_REVIEW";
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
            case SCOPE_MANUAL_REVIEW -> acknowledgeManualReview(event);
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

    private CompletableFuture<Void> acknowledgeManualReview(RemediationDecisionEvent event) {
        if (hasReason(event, "intervention:external_order")) {
            return acknowledgeManualReviewOrder(event);
        }
        if (hasReason(event, "intervention:external_position")) {
            return acknowledgeManualReviewPosition(event);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> acknowledgeManualReviewOrder(RemediationDecisionEvent event) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        TradingStateProjection.OrderState order = manualReviewOrder(event, provider, environment, account, market, symbol);
        if (!order.externalIntervention()) {
            return CompletableFuture.completedFuture(null);
        }
        String remediationId = requireText(event.getRemediationId(), "remediationId");
        if (!admit(remediationId)) {
            return CompletableFuture.completedFuture(null);
        }
        String interventionReason = requireText(order.interventionReason(), "interventionReason");
        InterventionAcknowledgementEvent acknowledgement = acknowledgementBuilder(event)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setClientOrderId(order.clientOrderId())
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
                                order.clientOrderId()
                        ),
                        acknowledgement
                ))
                .whenComplete((ignored, failure) -> forgetOnFailure(remediationId, failure))
                .thenApply(ignored -> null);
    }

    private TradingStateProjection.OrderState manualReviewOrder(
            RemediationDecisionEvent event,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        String clientOrderId = firstText(
                attribute(event, "affected_order_client_order_id"),
                singleAttribute(event, "external_order_client_order_ids")
        );
        if (clientOrderId != null) {
            return projection.order(provider, environment, account, market, symbol, clientOrderId)
                    .orElseThrow(() -> new IllegalStateException("No projected order exists for remediation"));
        }
        String exchangeOrderId = firstText(
                attribute(event, "affected_exchange_order_id"),
                singleAttribute(event, "external_order_exchange_order_ids")
        );
        if (exchangeOrderId == null) {
            throw new IllegalArgumentException("manual review external order identity is required");
        }
        return projection.orderByExchangeOrderId(provider, environment, account, market, symbol, exchangeOrderId)
                .orElseThrow(() -> new IllegalStateException("No projected order exists for remediation"));
    }

    private CompletableFuture<Void> acknowledgeManualReviewPosition(RemediationDecisionEvent event) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        String positionSide = firstText(
                attribute(event, "affected_position_side"),
                singleAttribute(event, "external_position_sides")
        );
        if (positionSide == null) {
            throw new IllegalArgumentException("manual review external position side is required");
        }
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
        String remediationId = requireText(event.getRemediationId(), "remediationId");
        if (!admit(remediationId)) {
            return CompletableFuture.completedFuture(null);
        }
        String interventionReason = requireText(position.interventionReason(), "interventionReason");
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

    private boolean hasReason(RemediationDecisionEvent event, String reason) {
        if (event.getReasons() == null) {
            return false;
        }
        for (CharSequence value : event.getReasons()) {
            if (reason.equals(value(value))) {
                return true;
            }
        }
        return false;
    }

    private String attribute(RemediationDecisionEvent event, String key) {
        if (event.getAttributes() == null || event.getAttributes().isEmpty()) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private String singleAttribute(RemediationDecisionEvent event, String key) {
        String value = attribute(event, key);
        if (value == null) {
            return null;
        }
        List<String> values = List.of(value.split(",")).stream()
                .map(this::value)
                .filter(Objects::nonNull)
                .toList();
        if (values.size() > 1) {
            throw new IllegalArgumentException(key + " must identify exactly one intervention target");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private String firstText(String first, String second) {
        return first == null ? second : first;
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
