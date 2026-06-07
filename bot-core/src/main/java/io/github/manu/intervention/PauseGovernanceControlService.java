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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class PauseGovernanceControlService {

    static final String RELEASE_ACCOUNT_PAUSE = "RELEASE_ACCOUNT_PAUSE";
    static final String RELEASE_SYMBOL_PAUSE = "RELEASE_SYMBOL_PAUSE";

    private final TradingEventBus eventBus;
    private final TradingStateProjection projection;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    public PauseGovernanceControlService(TradingEventBus eventBus, TradingStateProjection projection) {
        this(eventBus, projection, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    PauseGovernanceControlService(
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

    public CompletableFuture<PublishedTradingEvent> release(PauseReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        String provider = requireText(request.provider(), "provider");
        String environment = requireText(request.environment(), "environment");
        String account = requireText(request.account(), "account");
        String market = requireText(request.market(), "market");
        String pauseScope = pauseScope(request.pauseScope());
        String pauseTarget = requireText(request.pauseTarget(), "pauseTarget");
        TradingStateProjection.PauseGovernanceState activePause = activePause(
                provider,
                environment,
                account,
                market,
                pauseScope,
                pauseTarget
        );

        String releaseId = "pause-release:" + requireText(idSupplier.get(), "generated pause release id");
        RemediationDecisionEvent event = RemediationDecisionEvent.newBuilder()
                .setEventId("evt:" + releaseId)
                .setSchemaVersion(1)
                .setRemediationId(releaseId)
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol("SYMBOL".equals(pauseScope) ? activePause.symbol() : null)
                .setScope("PAUSE_GOVERNANCE")
                .setAction("ACCOUNT".equals(pauseScope) ? RELEASE_ACCOUNT_PAUSE : RELEASE_SYMBOL_PAUSE)
                .setClientOrderId(null)
                .setPositionSide(null)
                .setInterventionReason(activePause.interventionReason())
                .setReasons(java.util.List.of("pause_governance:release"))
                .setDecidedBy(requireText(request.releasedBy(), "releasedBy"))
                .setDecisionReason(requireText(request.releaseReason(), "releaseReason"))
                .setDecidedAtMicros(Instant.now(clock))
                .setAttributes(attributes(activePause, request))
                .build();
        return eventBus.publish(envelope(event, pauseScope, pauseTarget));
    }

    private TradingStateProjection.PauseGovernanceState activePause(
            String provider,
            String environment,
            String account,
            String market,
            String pauseScope,
            String pauseTarget
    ) {
        return projection.pauseGovernanceStates(provider, environment, account, market)
                .stream()
                .filter(pause -> pause.effectiveActive(Instant.now(clock)))
                .filter(pause -> pauseScope.equals(pause.pauseScope()))
                .filter(pause -> pauseTarget.equals(pause.pauseTarget()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No active pause governance state matches the release target"));
    }

    private Map<CharSequence, CharSequence> attributes(
            TradingStateProjection.PauseGovernanceState activePause,
            PauseReleaseRequest request
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("pause_governance_control", "release");
        attributes.put("pause_scope", activePause.pauseScope());
        attributes.put("pause_target", activePause.pauseTarget());
        attributes.put("source_pause_remediation_id", activePause.remediationId());
        attributes.put("source_pause_event_id", activePause.eventId());
        if (activePause.updatedAt() != null) {
            attributes.put("source_pause_updated_at", activePause.updatedAt().toString());
        }
        if (request.attributes() != null) {
            attributes.putAll(request.attributes());
        }
        return Map.copyOf(attributes);
    }

    private TradingEventEnvelope<RemediationDecisionEvent> envelope(
            RemediationDecisionEvent event,
            String pauseScope,
            String pauseTarget
    ) {
        if ("SYMBOL".equals(pauseScope)) {
            return TradingEventEnvelope.of(
                    TradingEventType.REMEDIATION_DECISION,
                    TradingEventKeys.symbol(
                            TradingEventType.REMEDIATION_DECISION,
                            event.getProvider().toString(),
                            event.getEnvironment().toString(),
                            event.getAccount().toString(),
                            event.getMarket().toString(),
                            pauseTarget
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

    private String pauseScope(String value) {
        String text = requireText(value, "pauseScope").toUpperCase(java.util.Locale.ROOT);
        if (!"ACCOUNT".equals(text) && !"SYMBOL".equals(text)) {
            throw new IllegalArgumentException("pauseScope must be ACCOUNT or SYMBOL");
        }
        return text;
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

    public record PauseReleaseRequest(
            String provider,
            String environment,
            String account,
            String market,
            String pauseScope,
            String pauseTarget,
            String releasedBy,
            String releaseReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        public PauseReleaseRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
