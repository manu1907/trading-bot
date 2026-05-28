package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class InterventionAcknowledgementService {

    private final TradingEventBus eventBus;
    private final TradingStateProjection projection;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    public InterventionAcknowledgementService(TradingEventBus eventBus, TradingStateProjection projection) {
        this(eventBus, projection, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    InterventionAcknowledgementService(
            TradingEventBus eventBus,
            TradingStateProjection projection,
            Clock clock,
            Supplier<String> idSupplier
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CompletableFuture<PublishedTradingEvent> acknowledgeOrder(
            OrderInterventionAcknowledgementRequest request
    ) {
        Objects.requireNonNull(request, "request");
        String provider = requireText(request.provider(), "provider");
        String environment = requireText(request.environment(), "environment");
        String account = requireText(request.account(), "account");
        String market = requireText(request.market(), "market");
        String symbol = requireText(request.symbol(), "symbol");
        String clientOrderId = requireText(request.clientOrderId(), "clientOrderId");
        String interventionReason = requireText(request.interventionReason(), "interventionReason");
        requireMatchingProjectedIntervention(provider, environment, account, market, symbol, clientOrderId, interventionReason);

        String acknowledgementId = "intervention-ack:" + requireText(idSupplier.get(), "generated acknowledgement id");
        InterventionAcknowledgementEvent event = InterventionAcknowledgementEvent.newBuilder()
                .setEventId("evt:" + acknowledgementId)
                .setSchemaVersion(1)
                .setAcknowledgementId(acknowledgementId)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setClientOrderId(clientOrderId)
                .setInterventionReason(interventionReason)
                .setAcknowledgedBy(requireText(request.acknowledgedBy(), "acknowledgedBy"))
                .setAcknowledgementReason(requireText(request.acknowledgementReason(), "acknowledgementReason"))
                .setAcknowledgedAtMicros(Instant.now(clock))
                .setAttributes(request.attributes())
                .build();
        return eventBus.publish(envelope(event));
    }

    private void requireMatchingProjectedIntervention(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String interventionReason
    ) {
        TradingStateProjection.OrderState order = projection.order(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        clientOrderId
                )
                .orElseThrow(() -> new IllegalStateException("No projected order exists for acknowledgement"));
        if (!order.externalIntervention()) {
            throw new IllegalStateException("Projected order has no unresolved intervention");
        }
        if (!interventionReason.equals(order.interventionReason())) {
            throw new IllegalStateException("Projected order intervention reason does not match acknowledgement");
        }
    }

    private TradingEventEnvelope<InterventionAcknowledgementEvent> envelope(
            InterventionAcknowledgementEvent event
    ) {
        return TradingEventEnvelope.of(
                TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                TradingEventKeys.order(
                        TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                        event.getProvider().toString(),
                        event.getEnvironment().toString(),
                        event.getAccount().toString(),
                        event.getMarket().toString(),
                        event.getSymbol().toString(),
                        event.getClientOrderId().toString()
                ),
                event
        );
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

    public record OrderInterventionAcknowledgementRequest(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String interventionReason,
            String acknowledgedBy,
            String acknowledgementReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        public OrderInterventionAcknowledgementRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
