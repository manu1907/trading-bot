package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class InterventionAcknowledgementService {

    private final TradingEventBus eventBus;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    public InterventionAcknowledgementService(TradingEventBus eventBus) {
        this(eventBus, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    InterventionAcknowledgementService(TradingEventBus eventBus, Clock clock, Supplier<String> idSupplier) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CompletableFuture<PublishedTradingEvent> acknowledgeOrder(
            OrderInterventionAcknowledgementRequest request
    ) {
        Objects.requireNonNull(request, "request");
        String acknowledgementId = "intervention-ack:" + requireText(idSupplier.get(), "generated acknowledgement id");
        InterventionAcknowledgementEvent event = InterventionAcknowledgementEvent.newBuilder()
                .setEventId("evt:" + acknowledgementId)
                .setSchemaVersion(1)
                .setAcknowledgementId(acknowledgementId)
                .setProvider(requireText(request.provider(), "provider"))
                .setEnvironment(requireText(request.environment(), "environment"))
                .setAccount(requireText(request.account(), "account"))
                .setMarket(requireText(request.market(), "market"))
                .setSymbol(requireText(request.symbol(), "symbol"))
                .setClientOrderId(requireText(request.clientOrderId(), "clientOrderId"))
                .setInterventionReason(text(request.interventionReason()))
                .setAcknowledgedBy(requireText(request.acknowledgedBy(), "acknowledgedBy"))
                .setAcknowledgementReason(requireText(request.acknowledgementReason(), "acknowledgementReason"))
                .setAcknowledgedAtMicros(Instant.now(clock))
                .setAttributes(request.attributes())
                .build();
        return eventBus.publish(envelope(event));
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
