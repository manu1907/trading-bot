package io.github.manu.reconciliation;

import io.github.manu.events.TradingEventType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class ReconciliationConfidenceTracker {

    private final ConcurrentMap<Key, ReconciliationConfidenceState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReconciliationConfidenceTracker() {
        this(Clock.systemUTC());
    }

    public ReconciliationConfidenceTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReconciliationConfidenceState record(ReconciliationObservation observation) {
        Objects.requireNonNull(observation, "observation");
        ReconciliationConfidenceState state = new ReconciliationConfidenceState(
                observation.provider(),
                observation.environment(),
                observation.account(),
                observation.market(),
                observation.eventType(),
                observation.entityKey(),
                observation.status(),
                observation.differences(),
                Instant.now(clock)
        );
        states.put(key(state.provider(), state.environment(), state.account(), state.market(), state.eventType(), state.entityKey()), state);
        return state;
    }

    public List<ReconciliationConfidenceState> recordAll(Collection<ReconciliationObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        return observations.stream()
                .map(this::record)
                .toList();
    }

    public Optional<ReconciliationConfidenceState> latest(
            String provider,
            String environment,
            String account,
            String market,
            TradingEventType eventType,
            String entityKey
    ) {
        return Optional.ofNullable(states.get(key(provider, environment, account, market, eventType, entityKey)));
    }

    public ReconciliationTargetConfidence targetConfidence(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String normalizedProvider = requireText(provider, "provider");
        String normalizedEnvironment = requireText(environment, "environment");
        String normalizedAccount = requireText(account, "account");
        String normalizedMarket = requireText(market, "market");
        List<ReconciliationConfidenceState> targetStates = states.values().stream()
                .filter(state -> sameTarget(state, normalizedProvider, normalizedEnvironment, normalizedAccount, normalizedMarket))
                .toList();
        int degradedStates = (int) targetStates.stream()
                .filter(state -> !state.confident())
                .count();
        Instant observedAt = targetStates.stream()
                .map(ReconciliationConfidenceState::observedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        ReconciliationTargetConfidence.Status status;
        if (targetStates.isEmpty()) {
            status = ReconciliationTargetConfidence.Status.NO_OBSERVATIONS;
        } else if (degradedStates > 0) {
            status = ReconciliationTargetConfidence.Status.DEGRADED;
        } else {
            status = ReconciliationTargetConfidence.Status.CONFIDENT;
        }
        return new ReconciliationTargetConfidence(
                normalizedProvider,
                normalizedEnvironment,
                normalizedAccount,
                normalizedMarket,
                status,
                targetStates.size(),
                degradedStates,
                observedAt
        );
    }

    public List<ReconciliationConfidenceState> degradedStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        String normalizedProvider = requireText(provider, "provider");
        String normalizedEnvironment = requireText(environment, "environment");
        String normalizedAccount = requireText(account, "account");
        String normalizedMarket = requireText(market, "market");
        return states.values().stream()
                .filter(state -> sameTarget(state, normalizedProvider, normalizedEnvironment, normalizedAccount, normalizedMarket))
                .filter(state -> !state.confident())
                .sorted(Comparator.comparing(ReconciliationConfidenceState::eventType)
                        .thenComparing(ReconciliationConfidenceState::entityKey))
                .toList();
    }

    private boolean sameTarget(
            ReconciliationConfidenceState state,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return provider.equals(state.provider())
                && environment.equals(state.environment())
                && account.equals(state.account())
                && market.equals(state.market());
    }

    private Key key(
            String provider,
            String environment,
            String account,
            String market,
            TradingEventType eventType,
            String entityKey
    ) {
        return new Key(
                requireText(provider, "provider"),
                requireText(environment, "environment"),
                requireText(account, "account"),
                requireText(market, "market"),
                Objects.requireNonNull(eventType, "eventType"),
                requireText(entityKey, "entityKey")
        );
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record Key(
            String provider,
            String environment,
            String account,
            String market,
            TradingEventType eventType,
            String entityKey
    ) {
    }
}
