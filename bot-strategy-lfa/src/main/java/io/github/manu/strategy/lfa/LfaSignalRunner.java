package io.github.manu.strategy.lfa;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.StrategyLifecycleEvent;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.execution.StrategyInstrumentUniverseResolver;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LfaSignalRunner {

    private static final Logger log = LoggerFactory.getLogger(LfaSignalRunner.class);

    private final LfaMarketSignalAnalyzer analyzer;
    private final LfaStrategyProperties.SignalRunner properties;
    private final TradingStateProjection projection;
    private final TradingEventBus eventBus;
    private final ExecutionProperties executionProperties;
    private final StrategyInstrumentUniverseResolver instrumentUniverseResolver;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<LfaLifecycleState> lifecycleState;
    private final AtomicReference<LfaLifecycleMetadata> lifecycleMetadata;

    public LfaSignalRunner(
            LfaMarketSignalAnalyzer analyzer,
            LfaStrategyProperties properties,
            TradingStateProjection projection,
            TradingEventBus eventBus,
            ExecutionProperties executionProperties
    ) {
        this(
                analyzer,
                properties.signalRunner(),
                projection,
                eventBus,
                executionProperties,
                null,
                Clock.systemUTC()
        );
    }

    public LfaSignalRunner(
            LfaMarketSignalAnalyzer analyzer,
            LfaStrategyProperties properties,
            TradingStateProjection projection,
            TradingEventBus eventBus,
            ExecutionProperties executionProperties,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver
    ) {
        this(
                analyzer,
                properties.signalRunner(),
                projection,
                eventBus,
                executionProperties,
                instrumentUniverseResolver,
                Clock.systemUTC()
        );
    }

    LfaSignalRunner(
            LfaMarketSignalAnalyzer analyzer,
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            TradingEventBus eventBus,
            ExecutionProperties executionProperties,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver,
            Clock clock
    ) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.executionProperties = Objects.requireNonNull(executionProperties, "executionProperties");
        this.instrumentUniverseResolver = instrumentUniverseResolver;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycleState = new AtomicReference<>(LfaLifecycleState.parse(properties.lifecycleState(), "lifecycleState"));
        this.lifecycleMetadata = new AtomicReference<>(new LfaLifecycleMetadata(null, null, null, Instant.EPOCH));
    }

    @Scheduled(
            fixedDelayString = "${trading.strategy.lfa.signal-runner.interval-millis:30000}",
            initialDelayString = "${trading.strategy.lfa.signal-runner.initial-delay-millis:30000}"
    )
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException exception) {
            log.warn("LFA signal runner failed: {}", exception.getMessage(), exception);
        }
    }

    public LfaSignalRunResult runOnce() {
        if (!properties.enabled()) {
            return LfaSignalRunResult.disabled("lfa_signal_runner:disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return LfaSignalRunResult.skipped("lfa_signal_runner:already_running");
        }
        try {
            if (properties.requireSignalPlannerEnabled() && !executionProperties.signalPlanner().enabled()) {
                return LfaSignalRunResult.blocked("lfa_signal_runner:signal_planner_disabled", target());
            }
            LfaRunnerTarget target = target();
            LfaSignalRequest request = properties.request(
                    target.provider(),
                    target.environment(),
                    target.account(),
                    target.market()
            );
            Instant now = Instant.now(clock);
            TradingStateSnapshot snapshot = projection.snapshot();
            refreshLifecycleFromProjection(snapshot);
            GateDecision lifecycle = lifecycleGate();
            if (lifecycle.blocked()) {
                return new LfaSignalRunResult(
                        true,
                        "lfa_signal_runner:lifecycle_blocked",
                        target,
                        0,
                        0,
                        List.of(),
                        lifecycle.reasons()
                );
            }
            GateDecision warmup = warmupGate(snapshot, target, now);
            if (warmup.blocked()) {
                return new LfaSignalRunResult(
                        true,
                        "lfa_signal_runner:warmup_incomplete",
                        target,
                        0,
                        0,
                        List.of(),
                        warmup.reasons()
                );
            }
            BudgetDecision accountBudget = accountBudget(snapshot, target, now);
            if (accountBudget.blocked()) {
                return new LfaSignalRunResult(
                        true,
                        "lfa_signal_runner:budget_blocked",
                        target,
                        0,
                        0,
                        List.of(),
                        accountBudget.reasons()
                );
            }
            List<StrategySignalEvent> signals = analyzer.analyze(
                    request,
                    candidateMarketData(snapshot, target, now),
                    now
            ).stream().limit(properties.maxSignalsPerRun()).toList();
            if (signals.isEmpty()) {
                return new LfaSignalRunResult(true, "lfa_signal_runner:no_signal", target, 0, 0, List.of(), List.of());
            }
            AllocationDecision allocation = allocateSignals(snapshot, target, signals);
            if (allocation.blocked()) {
                return new LfaSignalRunResult(
                        true,
                        "lfa_signal_runner:allocation_blocked",
                        target,
                        signals.size(),
                        0,
                        signals.stream().map(signal -> signal.getSignalId().toString()).toList(),
                        allocation.reasons()
                );
            }
            signals = allocation.signals();
            List<StrategySignalEvent> publishableSignals = new ArrayList<>();
            LinkedHashSet<String> blockedReasons = new LinkedHashSet<>();
            for (StrategySignalEvent signal : signals) {
                BudgetDecision budget = signalBudget(snapshot, target, signal, now);
                if (budget.blocked()) {
                    blockedReasons.addAll(budget.reasons());
                } else {
                    publishableSignals.add(signal);
                }
            }
            if (!blockedReasons.isEmpty()) {
                if (!publishableSignals.isEmpty()) {
                    List<PublishedTradingEvent> published = publish(publishableSignals).join();
                    return new LfaSignalRunResult(
                            true,
                            "lfa_signal_runner:published_with_budget_blocks",
                            target,
                            signals.size(),
                            published.size(),
                            publishableSignals.stream().map(signal -> signal.getSignalId().toString()).toList(),
                            blockedReasons.stream().toList()
                    );
                }
                return new LfaSignalRunResult(
                        true,
                        "lfa_signal_runner:budget_blocked",
                        target,
                        signals.size(),
                        0,
                        signals.stream().map(signal -> signal.getSignalId().toString()).toList(),
                        blockedReasons.stream().toList()
                );
            }
            List<PublishedTradingEvent> published = publish(signals).join();
            return new LfaSignalRunResult(
                    true,
                    "lfa_signal_runner:published",
                    target,
                    signals.size(),
                    published.size(),
                    signals.stream().map(signal -> signal.getSignalId().toString()).toList(),
                    List.of()
            );
        } finally {
            running.set(false);
        }
    }

    public LfaLifecycleStatus lifecycleStatus() {
        TradingStateSnapshot snapshot = projection.snapshot();
        refreshLifecycleFromProjection(snapshot);
        return lifecycleStatus(lifecycleState.get(), lifecycleMetadata.get(), snapshot);
    }

    public CompletableFuture<LfaLifecycleStatus> transitionLifecycle(
            String requestedState,
            String changedBy,
            String reason
    ) {
        TradingStateSnapshot snapshot = projection.snapshot();
        refreshLifecycleFromProjection(snapshot);
        LfaLifecycleState next = LfaLifecycleState.parse(requestedState, "lifecycleState");
        LfaLifecycleState previous = lifecycleState.get();
        validateLifecycleTransition(previous, next, snapshot);
        Instant changedAt = Instant.now(clock);
        StrategyLifecycleEvent event = lifecycleEvent(previous, next, changedBy, reason, changedAt, snapshot);
        TradingEventEnvelope<StrategyLifecycleEvent> envelope = TradingEventEnvelope.of(
                TradingEventType.STRATEGY_LIFECYCLE,
                TradingEventKeys.strategy(TradingEventType.STRATEGY_LIFECYCLE, event.getLifecycleId().toString()),
                event
        );
        return eventBus.publish(envelope).thenApply(published -> {
            lifecycleState.set(next);
            LfaLifecycleMetadata metadata = new LfaLifecycleMetadata(
                    text(changedBy),
                    text(reason),
                    event.getEventId().toString(),
                    changedAt
            );
            lifecycleMetadata.set(metadata);
            projection.apply(envelope);
            return lifecycleStatus(next, metadata, snapshot);
        });
    }

    private GateDecision lifecycleGate() {
        LfaLifecycleState current = lifecycleState.get();
        if (!current.canPublishNewSignals()) {
            return new GateDecision(List.of(current.blockerReason()));
        }
        if (properties.allowedLifecycleStates().contains(current.name())) {
            return GateDecision.allowed();
        }
        return new GateDecision(List.of(current.blockerReason()));
    }

    private LfaLifecycleStatus lifecycleStatus(
            LfaLifecycleState current,
            LfaLifecycleMetadata metadata,
            TradingStateSnapshot snapshot
    ) {
        List<String> blockers = lifecycleBlockers(current);
        DrainStatus drainStatus = drainStatus(snapshot, target());
        return new LfaLifecycleStatus(
                properties.enabled(),
                properties.lifecycleState(),
                current.name(),
                properties.allowedLifecycleStates(),
                allowedNextLifecycleStates(current),
                properties.allowEmergencyStopReactivation(),
                current.canPublishNewSignals() && properties.allowedLifecycleStates().contains(current.name()),
                blockers,
                metadata.changedBy(),
                metadata.reason(),
                metadata.changedAt(),
                metadata.eventId(),
                drainStatus.openOrders(),
                drainStatus.openPositions(),
                drainStatus.complete()
        );
    }

    private void validateLifecycleTransition(
            LfaLifecycleState previous,
            LfaLifecycleState next,
            TradingStateSnapshot snapshot
    ) {
        if (previous == next) {
            return;
        }
        if (previous == LfaLifecycleState.EMERGENCY_STOP && !properties.allowEmergencyStopReactivation()) {
            throw new IllegalArgumentException(
                    "lifecycle transition from EMERGENCY_STOP requires allowEmergencyStopReactivation=true"
            );
        }
        List<String> allowedNext = allowedNextLifecycleStates(previous);
        if (!allowedNext.contains(next.name())) {
            throw new IllegalArgumentException(
                    "lifecycle transition " + previous.name() + "->" + next.name() + " is not allowed"
            );
        }
        if (previous == LfaLifecycleState.DRAINING && next == LfaLifecycleState.STOPPED) {
            DrainStatus drainStatus = drainStatus(snapshot, target());
            if (!drainStatus.complete()) {
                throw new IllegalArgumentException(
                        "lifecycle transition DRAINING->STOPPED requires no open orders or positions"
                );
            }
        }
    }

    private List<String> allowedNextLifecycleStates(LfaLifecycleState current) {
        List<String> configured = properties.allowedLifecycleTransitions().get(current.name());
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        if (current == LfaLifecycleState.EMERGENCY_STOP && !properties.allowEmergencyStopReactivation()) {
            return List.of();
        }
        return configured;
    }

    private DrainStatus drainStatus(TradingStateSnapshot snapshot, LfaRunnerTarget target) {
        int openOrders = (int) snapshot.orders()
                .stream()
                .filter(order -> matchesTarget(order, target))
                .filter(TradingStateProjection.OrderState::open)
                .count();
        int openPositions = (int) snapshot.positions()
                .stream()
                .filter(position -> matchesTarget(position, target))
                .filter(TradingStateProjection.PositionState::open)
                .count();
        return new DrainStatus(openOrders, openPositions);
    }

    private void refreshLifecycleFromProjection(TradingStateSnapshot snapshot) {
        LfaRunnerTarget target = target();
        snapshot.strategyLifecycle()
                .stream()
                .filter(state -> "lfa".equalsIgnoreCase(text(state.strategyId())))
                .filter(state -> target.provider().equalsIgnoreCase(text(state.provider())))
                .filter(state -> target.environment().equalsIgnoreCase(text(state.environment())))
                .filter(state -> target.account().equalsIgnoreCase(text(state.account())))
                .filter(state -> target.market().equalsIgnoreCase(text(state.market())))
                .max(Comparator.comparing(TradingStateProjection.StrategyLifecycleState::updatedAt))
                .ifPresent(this::applyProjectedLifecycleState);
    }

    private void applyProjectedLifecycleState(TradingStateProjection.StrategyLifecycleState state) {
        Instant updatedAt = state.updatedAt() == null ? Instant.EPOCH : state.updatedAt();
        LfaLifecycleMetadata current = lifecycleMetadata.get();
        if (updatedAt.isBefore(current.changedAt())) {
            return;
        }
        LfaLifecycleState projected = LfaLifecycleState.parse(state.lifecycleState(), "lifecycleState");
        lifecycleState.set(projected);
        lifecycleMetadata.set(new LfaLifecycleMetadata(
                state.changedBy(),
                state.reason(),
                state.eventId(),
                updatedAt
        ));
    }

    private StrategyLifecycleEvent lifecycleEvent(
            LfaLifecycleState previous,
            LfaLifecycleState next,
            String changedBy,
            String reason,
            Instant changedAt,
            TradingStateSnapshot snapshot
    ) {
        LfaRunnerTarget target = target();
        String lifecycleId = lifecycleId(target);
        DrainStatus drainStatus = drainStatus(snapshot, target);
        return StrategyLifecycleEvent.newBuilder()
                .setEventId("evt-" + lifecycleId + "-" + UUID.randomUUID())
                .setSchemaVersion(1)
                .setLifecycleId(lifecycleId)
                .setStrategyId("lfa")
                .setProvider(target.provider())
                .setEnvironment(target.environment())
                .setAccount(target.account())
                .setMarket(target.market())
                .setPreviousLifecycleState(previous.name())
                .setLifecycleState(next.name())
                .setChangedBy(text(changedBy))
                .setReason(text(reason))
                .setChangedAtMicros(changedAt)
                .setAttributes(Map.of(
                        "configured_lifecycle_state", properties.lifecycleState(),
                        "allowed_lifecycle_states", String.join(",", properties.allowedLifecycleStates()),
                        "allowed_next_lifecycle_states", String.join(",", allowedNextLifecycleStates(previous)),
                        "open_order_count", Integer.toString(drainStatus.openOrders()),
                        "open_position_count", Integer.toString(drainStatus.openPositions()),
                        "drain_complete", Boolean.toString(drainStatus.complete()),
                        "emergency_stop_transition", Boolean.toString(next == LfaLifecycleState.EMERGENCY_STOP),
                        "emergency_stop_reactivation_allowed",
                        Boolean.toString(properties.allowEmergencyStopReactivation())
                ))
                .build();
    }

    private String lifecycleId(LfaRunnerTarget target) {
        return String.join(
                "/",
                "lfa",
                target.provider(),
                target.environment(),
                target.account(),
                target.market()
        );
    }

    private List<String> lifecycleBlockers(LfaLifecycleState current) {
        if (!current.canPublishNewSignals()) {
            return List.of(current.blockerReason());
        }
        if (!properties.allowedLifecycleStates().contains(current.name())) {
            return List.of(current.blockerReason());
        }
        return List.of();
    }

    private GateDecision warmupGate(TradingStateSnapshot snapshot, LfaRunnerTarget target, Instant now) {
        if (!properties.requireWarmupMarketData()) {
            return GateDecision.allowed();
        }
        LinkedHashSet<String> marketDataSymbols = new LinkedHashSet<>();
        LinkedHashSet<String> topOfBookSymbols = new LinkedHashSet<>();
        for (TradingStateProjection.MarketDataState state : snapshot.marketData()) {
            if (!matchesTarget(state, target) || !fresh(state.updatedAt(), now)) {
                continue;
            }
            if (state.symbol() != null && !state.symbol().isBlank()) {
                marketDataSymbols.add(state.symbol().trim().toUpperCase(java.util.Locale.ROOT));
            }
            if (state.hasTopOfBook() && fresh(topOfBookTimestamp(state), now)
                    && state.symbol() != null && !state.symbol().isBlank()) {
                topOfBookSymbols.add(state.symbol().trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (marketDataSymbols.size() < properties.minWarmupMarketDataSymbols().intValue()) {
            reasons.add("lfa_warmup:market_data_symbols_below_min");
        }
        if (topOfBookSymbols.size() < properties.minWarmupTopOfBookSymbols().intValue()) {
            reasons.add("lfa_warmup:top_of_book_symbols_below_min");
        }
        return new GateDecision(reasons.stream().toList());
    }

    private Instant topOfBookTimestamp(TradingStateProjection.MarketDataState state) {
        return state.topOfBookUpdatedAt() == null ? state.updatedAt() : state.topOfBookUpdatedAt();
    }

    private boolean fresh(Instant updatedAt, Instant now) {
        return updatedAt != null
                && !updatedAt.isAfter(now)
                && !updatedAt.plusMillis(properties.warmupMaxMarketDataAgeMillis()).isBefore(now);
    }

    private List<TradingStateProjection.MarketDataState> candidateMarketData(
            TradingStateSnapshot snapshot,
            LfaRunnerTarget target,
            Instant now
    ) {
        ExecutionProperties.SignalPlanner.InstrumentUniverse universe =
                executionProperties.signalPlanner().instrumentUniverse();
        Optional<Set<String>> allowedSymbols = allowedUniverseSymbols(universe, target);
        if (allowedSymbols.isPresent() && allowedSymbols.get().isEmpty()) {
            return List.of();
        }
        List<TradingStateProjection.MarketDataState> ranked = snapshot.marketData().stream()
                .filter(state -> matchesTarget(state, target))
                .filter(state -> allowedSymbols
                        .map(symbols -> symbols.contains(normalize(state.symbol())))
                        .orElse(true))
                .filter(state -> universePolicyAllows(universe, target, state.symbol()))
                .map(state -> rankedMarketData(state, now))
                .flatMap(Optional::stream)
                .filter(candidate -> universeMarketDataAllows(universe, target, candidate))
                .sorted(Comparator
                        .comparing(RankedMarketData::spreadBps)
                        .thenComparing(RankedMarketData::topOfBookQuoteNotional, Comparator.reverseOrder())
                        .thenComparing(RankedMarketData::ageMillis)
                        .thenComparing(candidate -> normalize(candidate.state().symbol())))
                .map(RankedMarketData::state)
                .toList();
        if (properties.maxCandidateMarketDataSymbols() == null
                || ranked.size() <= properties.maxCandidateMarketDataSymbols().intValue()) {
            return ranked;
        }
        return List.copyOf(ranked.subList(0, properties.maxCandidateMarketDataSymbols().intValue()));
    }

    private Optional<Set<String>> allowedUniverseSymbols(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            LfaRunnerTarget target
    ) {
        if (!properties.useSignalPlannerInstrumentUniverse() || !universe.enabled()) {
            return Optional.empty();
        }
        List<String> eligibleSymbols = List.of();
        if (instrumentUniverseResolver != null
                && (universe.refreshExchangeMetadataBeforePlanning() || universe.requireExchangeMetadata())) {
            eligibleSymbols = instrumentUniverseResolver.eligibleSymbols(
                    universe,
                    target.provider(),
                    target.environment(),
                    target.account(),
                    target.market(),
                    OrderCommandType.LIMIT
            );
        } else if (universe.requireExchangeMetadata()) {
            return Optional.of(Set.of());
        } else if (!universe.includedSymbols().isEmpty() || universe.requireIncludedSymbol()) {
            eligibleSymbols = universe.includedSymbols().stream()
                    .filter(symbol -> !universe.excludedSymbols().contains(symbol))
                    .toList();
        }
        if (eligibleSymbols.isEmpty()
                && (universe.requireExchangeMetadata()
                || universe.requireIncludedSymbol()
                || !universe.includedSymbols().isEmpty())) {
            return Optional.of(Set.of());
        }
        if (eligibleSymbols.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HashSet<>(eligibleSymbols));
    }

    private boolean universePolicyAllows(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            LfaRunnerTarget target,
            String symbol
    ) {
        if (!properties.useSignalPlannerInstrumentUniverse() || !universe.enabled()) {
            return true;
        }
        String normalizedSymbol = normalize(symbol);
        if (normalizedSymbol.isBlank()) {
            return false;
        }
        if (universe.excludedSymbols().contains(normalizedSymbol)) {
            return false;
        }
        if (universe.requireIncludedSymbol() && !universe.includedSymbols().contains(normalizedSymbol)) {
            return false;
        }
        ExecutionProperties.SignalPlanner.SymbolPolicy policy = selectedSymbolPolicy(universe, target, normalizedSymbol);
        if (policy == null) {
            return !universe.requirePromotionReady();
        }
        if (universe.requireSymbolEnabled() && !Boolean.TRUE.equals(policy.enabled())) {
            return false;
        }
        return !universe.requirePromotionReady() || Boolean.TRUE.equals(policy.promotionReady());
    }

    private boolean universeMarketDataAllows(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            LfaRunnerTarget target,
            RankedMarketData candidate
    ) {
        if (!properties.useSignalPlannerInstrumentUniverse() || !universe.enabled()) {
            return true;
        }
        if (universe.maxMarketDataAgeMillis() != null
                && candidate.ageMillis() > universe.maxMarketDataAgeMillis().longValue()) {
            return false;
        }
        ExecutionProperties.SignalPlanner.SymbolPolicy policy =
                selectedSymbolPolicy(universe, target, normalize(candidate.state().symbol()));
        BigDecimal maxSpreadBps = decimal(policy == null || policy.maxSpreadBps() == null
                ? universe.maxSpreadBps()
                : policy.maxSpreadBps());
        if (maxSpreadBps != null && candidate.spreadBps().compareTo(maxSpreadBps) > 0) {
            return false;
        }
        BigDecimal minTopOfBookQuoteNotional = decimal(policy == null || policy.minTopOfBookQuoteNotional() == null
                ? universe.minTopOfBookQuoteNotional()
                : policy.minTopOfBookQuoteNotional());
        return minTopOfBookQuoteNotional == null
                || candidate.topOfBookQuoteNotional().compareTo(minTopOfBookQuoteNotional) >= 0;
    }

    private Optional<RankedMarketData> rankedMarketData(TradingStateProjection.MarketDataState state, Instant now) {
        BigDecimal bidPrice = decimal(state.bestBidPrice());
        BigDecimal bidQuantity = decimal(state.bestBidQuantity());
        BigDecimal askPrice = decimal(state.bestAskPrice());
        BigDecimal askQuantity = decimal(state.bestAskQuantity());
        if (!positive(bidPrice) || !positive(bidQuantity) || !positive(askPrice) || !positive(askQuantity)) {
            return Optional.empty();
        }
        if (askPrice.compareTo(bidPrice) < 0) {
            return Optional.empty();
        }
        Instant observedAt = topOfBookTimestamp(state);
        if (observedAt == null || observedAt.isAfter(now)) {
            return Optional.empty();
        }
        long ageMillis = Duration.between(observedAt, now).toMillis();
        if (ageMillis > properties.maxMarketDataAgeMillis().longValue()) {
            return Optional.empty();
        }
        BigDecimal mid = bidPrice.add(askPrice).divide(new BigDecimal("2"), 18, RoundingMode.HALF_UP);
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal spreadBps = askPrice.subtract(bidPrice)
                .multiply(new BigDecimal("10000"))
                .divide(mid, 8, RoundingMode.HALF_UP);
        BigDecimal topOfBookQuoteNotional = bidPrice.multiply(bidQuantity).min(askPrice.multiply(askQuantity));
        return Optional.of(new RankedMarketData(state, spreadBps, topOfBookQuoteNotional, ageMillis));
    }

    private ExecutionProperties.SignalPlanner.SymbolPolicy selectedSymbolPolicy(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            LfaRunnerTarget target,
            String symbol
    ) {
        ExecutionProperties.SignalPlanner.SymbolPolicy selected = null;
        int selectedSpecificity = -1;
        for (ExecutionProperties.SignalPlanner.SymbolPolicy candidate : universe.symbolPolicies()) {
            int specificity = specificity(candidate, target, symbol);
            if (specificity > selectedSpecificity) {
                selected = candidate;
                selectedSpecificity = specificity;
            }
        }
        return selected;
    }

    private int specificity(
            ExecutionProperties.SignalPlanner.SymbolPolicy candidate,
            LfaRunnerTarget target,
            String symbol
    ) {
        int specificity = 0;
        specificity = match(candidate.provider(), target.provider(), specificity);
        specificity = match(candidate.environment(), target.environment(), specificity);
        specificity = match(candidate.account(), target.account(), specificity);
        specificity = match(candidate.market(), target.market(), specificity);
        return match(candidate.symbol(), symbol, specificity);
    }

    private int match(String configured, String actual, int currentSpecificity) {
        if (currentSpecificity < 0) {
            return -1;
        }
        if (configured == null || configured.isBlank()) {
            return currentSpecificity;
        }
        if (!same(configured, actual)) {
            return -1;
        }
        return currentSpecificity + 1;
    }

    private BudgetDecision accountBudget(TradingStateSnapshot snapshot, LfaRunnerTarget target, Instant now) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        CurrentExposure exposure = currentExposure(snapshot, target, null);
        if (properties.maxAccountOpenPositions() != null
                && exposure.accountOpenPositions() >= properties.maxAccountOpenPositions().intValue()) {
            reasons.add("lfa_budget:max_account_open_positions");
        }
        if (properties.maxAccountPositionNotional() != null) {
            if (!exposure.valid()) {
                addIfStrict(reasons, "lfa_budget:position_notional_metadata_missing");
            } else if (exposure.accountPositionNotional().compareTo(properties.maxAccountPositionNotional()) > 0) {
                reasons.add("lfa_budget:max_account_position_notional");
            }
        }
        UnrealizedLoss unrealizedLoss = unrealizedLoss(snapshot, target, null);
        if (properties.maxAccountUnrealizedLoss() != null) {
            if (!unrealizedLoss.valid()) {
                addIfStrict(reasons, "lfa_budget:unrealized_pnl_missing");
            } else if (unrealizedLoss.accountLoss().compareTo(properties.maxAccountUnrealizedLoss()) > 0) {
                reasons.add("lfa_budget:max_account_unrealized_loss");
            }
        }
        DailyLoss dailyLoss = dailyLoss(snapshot, target, null, now);
        if (properties.maxAccountDailyRealizedLoss() != null) {
            if (dailyLoss.accountLoss().isEmpty()) {
                addIfStrict(reasons, "lfa_budget:daily_realized_pnl_missing");
            } else if (dailyLoss.accountLoss().get().compareTo(properties.maxAccountDailyRealizedLoss()) > 0) {
                reasons.add("lfa_budget:max_account_daily_realized_loss");
            }
        }
        return new BudgetDecision(reasons.stream().toList());
    }

    private BudgetDecision signalBudget(
            TradingStateSnapshot snapshot,
            LfaRunnerTarget target,
            StrategySignalEvent signal,
            Instant now
    ) {
        String symbol = signal.getSymbol().toString();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        CurrentExposure exposure = currentExposure(snapshot, target, symbol);
        if (properties.maxSymbolOpenPositions() != null
                && exposure.symbolOpenPositions() >= properties.maxSymbolOpenPositions().intValue()) {
            reasons.add("lfa_budget:max_symbol_open_positions");
        }
        BigDecimal signalNotional = signalNotional(signal).orElse(null);
        if ((properties.maxAccountPositionNotional() != null || properties.maxSymbolPositionNotional() != null)
                && signalNotional == null) {
            addIfStrict(reasons, "lfa_budget:signal_notional_unbounded");
        }
        if (properties.maxAccountPositionNotional() != null && exposure.valid() && signalNotional != null) {
            BigDecimal projected = exposure.accountPositionNotional().add(signalNotional);
            if (projected.compareTo(properties.maxAccountPositionNotional()) > 0) {
                reasons.add("lfa_budget:max_account_position_notional");
            }
        }
        if (properties.maxSymbolPositionNotional() != null) {
            if (!exposure.valid()) {
                addIfStrict(reasons, "lfa_budget:position_notional_metadata_missing");
            } else if (signalNotional != null
                    && exposure.symbolPositionNotional().add(signalNotional)
                            .compareTo(properties.maxSymbolPositionNotional()) > 0) {
                reasons.add("lfa_budget:max_symbol_position_notional");
            }
        }
        UnrealizedLoss unrealizedLoss = unrealizedLoss(snapshot, target, symbol);
        if (properties.maxSymbolUnrealizedLoss() != null) {
            if (!unrealizedLoss.valid()) {
                addIfStrict(reasons, "lfa_budget:unrealized_pnl_missing");
            } else if (unrealizedLoss.symbolLoss().compareTo(properties.maxSymbolUnrealizedLoss()) > 0) {
                reasons.add("lfa_budget:max_symbol_unrealized_loss");
            }
        }
        DailyLoss dailyLoss = dailyLoss(snapshot, target, symbol, now);
        if (properties.maxSymbolDailyRealizedLoss() != null) {
            if (dailyLoss.symbolLoss().isEmpty()) {
                addIfStrict(reasons, "lfa_budget:daily_realized_pnl_missing");
            } else if (dailyLoss.symbolLoss().get().compareTo(properties.maxSymbolDailyRealizedLoss()) > 0) {
                reasons.add("lfa_budget:max_symbol_daily_realized_loss");
            }
        }
        return new BudgetDecision(reasons.stream().toList());
    }

    private CurrentExposure currentExposure(TradingStateSnapshot snapshot, LfaRunnerTarget target, String symbol) {
        BigDecimal accountNotional = BigDecimal.ZERO;
        BigDecimal symbolNotional = BigDecimal.ZERO;
        int accountOpenPositions = 0;
        int symbolOpenPositions = 0;
        boolean valid = true;
        for (TradingStateProjection.PositionState position : snapshot.positions()) {
            if (!matchesTarget(position, target) || !position.open()) {
                continue;
            }
            accountOpenPositions++;
            if (same(position.symbol(), symbol)) {
                symbolOpenPositions++;
            }
            BigDecimal amount = decimal(position.positionAmount());
            BigDecimal markPrice = decimal(position.markPrice());
            if (amount == null || markPrice == null || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
                valid = false;
                continue;
            }
            BigDecimal notional = amount.abs().multiply(markPrice.abs());
            accountNotional = accountNotional.add(notional);
            if (same(position.symbol(), symbol)) {
                symbolNotional = symbolNotional.add(notional);
            }
        }
        return new CurrentExposure(valid, accountNotional, symbolNotional, accountOpenPositions, symbolOpenPositions);
    }

    private UnrealizedLoss unrealizedLoss(TradingStateSnapshot snapshot, LfaRunnerTarget target, String symbol) {
        BigDecimal accountLoss = BigDecimal.ZERO;
        BigDecimal symbolLoss = BigDecimal.ZERO;
        boolean valid = true;
        for (TradingStateProjection.PositionState position : snapshot.positions()) {
            if (!matchesTarget(position, target) || !position.open()) {
                continue;
            }
            boolean matchesSymbol = same(position.symbol(), symbol);
            BigDecimal unrealizedPnl = decimal(position.unrealizedPnl());
            if (unrealizedPnl == null) {
                if (symbol == null || matchesSymbol) {
                    valid = false;
                }
                continue;
            }
            BigDecimal loss = unrealizedPnl.signum() < 0 ? unrealizedPnl.abs() : BigDecimal.ZERO;
            accountLoss = accountLoss.add(loss);
            if (matchesSymbol) {
                symbolLoss = symbolLoss.add(loss);
            }
        }
        return new UnrealizedLoss(valid, accountLoss, symbolLoss);
    }

    private DailyLoss dailyLoss(TradingStateSnapshot snapshot, LfaRunnerTarget target, String symbol, Instant now) {
        String tradingDay = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        Optional<BigDecimal> accountLoss = Optional.empty();
        Optional<BigDecimal> symbolLoss = Optional.empty();
        for (TradingStateProjection.DailyRealizedPnlState state : snapshot.dailyRealizedPnl()) {
            if (!matchesTarget(state, target) || !tradingDay.equals(state.tradingDay())) {
                continue;
            }
            if (state.symbol() == null) {
                accountLoss = loss(state.realizedPnl());
            } else if (same(state.symbol(), symbol)) {
                symbolLoss = loss(state.realizedPnl());
            }
        }
        return new DailyLoss(accountLoss, symbolLoss);
    }

    private AllocationDecision allocateSignals(
            TradingStateSnapshot snapshot,
            LfaRunnerTarget target,
            List<StrategySignalEvent> signals
    ) {
        if (properties.targetNotionalMarginBalanceFraction() == null) {
            return AllocationDecision.allowed(signals);
        }
        BigDecimal marginBalance = accountMarginBalance(snapshot, target).orElse(null);
        if (marginBalance == null) {
            if (properties.rejectMissingAllocationBalance()) {
                return AllocationDecision.blocked("lfa_allocation:account_margin_balance_missing");
            }
            return AllocationDecision.allowed(signals);
        }
        BigDecimal allocated = marginBalance.multiply(properties.targetNotionalMarginBalanceFraction());
        if (properties.maxAllocatedTargetNotional() != null
                && allocated.compareTo(properties.maxAllocatedTargetNotional()) > 0) {
            allocated = properties.maxAllocatedTargetNotional();
        }
        int allocationSlots = Math.min(signals.size(), properties.maxSignalsPerRun());
        if (allocationSlots <= 0) {
            return AllocationDecision.blocked("lfa_allocation:target_notional_non_positive");
        }
        BigDecimal perSignalAllocated = allocated.divide(BigDecimal.valueOf(allocationSlots), 8, RoundingMode.HALF_UP);
        if (properties.minAllocatedTargetNotional() != null
                && perSignalAllocated.compareTo(properties.minAllocatedTargetNotional()) < 0) {
            return AllocationDecision.blocked("lfa_allocation:target_notional_below_min");
        }
        if (perSignalAllocated.compareTo(BigDecimal.ZERO) <= 0) {
            return AllocationDecision.blocked("lfa_allocation:target_notional_non_positive");
        }
        String allocatedText = decimalText(perSignalAllocated);
        String totalAllocatedText = decimalText(allocated);
        return AllocationDecision.allowed(signals.stream()
                .map(signal -> allocatedSignal(signal, marginBalance, totalAllocatedText, allocatedText))
                .toList());
    }

    private Optional<BigDecimal> accountMarginBalance(TradingStateSnapshot snapshot, LfaRunnerTarget target) {
        TradingStateProjection.RiskState selected = null;
        for (TradingStateProjection.RiskState state : snapshot.risks()) {
            if (!matchesTarget(state, target) || !same(state.riskScope(), "ACCOUNT")) {
                continue;
            }
            BigDecimal marginBalance = decimal(state.marginBalance());
            if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (selected == null || selected.updatedAt() == null
                    || (state.updatedAt() != null && state.updatedAt().isAfter(selected.updatedAt()))) {
                selected = state;
            }
        }
        return selected == null ? Optional.empty() : Optional.of(decimal(selected.marginBalance()));
    }

    private StrategySignalEvent allocatedSignal(
            StrategySignalEvent signal,
            BigDecimal marginBalance,
            String totalAllocatedTargetNotional,
            String allocatedTargetNotional
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (signal.getAttributes() != null) {
            attributes.putAll(signal.getAttributes());
        }
        attributes.put("lfa_allocation_source", "account_margin_balance");
        attributes.put("lfa_allocation_base", decimalText(marginBalance));
        attributes.put("lfa_allocation_fraction", properties.targetNotionalMarginBalanceFraction().toPlainString());
        attributes.put("lfa_allocation_total_target_notional", totalAllocatedTargetNotional);
        attributes.put("lfa_allocated_target_notional", allocatedTargetNotional);
        return StrategySignalEvent.newBuilder(signal)
                .setTargetQuantity(null)
                .setTargetNotional(allocatedTargetNotional)
                .setAttributes(attributes)
                .build();
    }

    private String decimalText(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private Optional<BigDecimal> signalNotional(StrategySignalEvent signal) {
        BigDecimal targetNotional = decimal(signal.getTargetNotional());
        if (targetNotional != null) {
            return Optional.of(targetNotional);
        }
        BigDecimal quantity = decimal(signal.getTargetQuantity());
        BigDecimal limitPrice = decimal(signal.getLimitPrice());
        if (quantity == null || limitPrice == null) {
            return Optional.empty();
        }
        return Optional.of(quantity.multiply(limitPrice).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private Optional<BigDecimal> loss(String realizedPnl) {
        BigDecimal pnl = decimal(realizedPnl);
        if (pnl == null) {
            return Optional.empty();
        }
        return Optional.of(pnl.signum() < 0 ? pnl.abs() : BigDecimal.ZERO);
    }

    private boolean matchesTarget(TradingStateProjection.PositionState position, LfaRunnerTarget target) {
        return same(position.provider(), target.provider())
                && same(position.environment(), target.environment())
                && same(position.account(), target.account())
                && same(position.market(), target.market());
    }

    private boolean matchesTarget(TradingStateProjection.OrderState order, LfaRunnerTarget target) {
        return same(order.provider(), target.provider())
                && same(order.environment(), target.environment())
                && same(order.account(), target.account())
                && same(order.market(), target.market());
    }

    private boolean matchesTarget(TradingStateProjection.DailyRealizedPnlState state, LfaRunnerTarget target) {
        return same(state.provider(), target.provider())
                && same(state.environment(), target.environment())
                && same(state.account(), target.account())
                && same(state.market(), target.market());
    }

    private boolean matchesTarget(TradingStateProjection.RiskState state, LfaRunnerTarget target) {
        return same(state.provider(), target.provider())
                && same(state.environment(), target.environment())
                && same(state.account(), target.account())
                && same(state.market(), target.market());
    }

    private boolean matchesTarget(TradingStateProjection.MarketDataState state, LfaRunnerTarget target) {
        return same(state.provider(), target.provider())
                && same(state.environment(), target.environment())
                && same(state.market(), target.market());
    }

    private void addIfStrict(LinkedHashSet<String> reasons, String reason) {
        if (properties.rejectMissingAccountRiskMetadata()) {
            reasons.add(reason);
        }
    }

    private BigDecimal decimal(CharSequence value) {
        return value == null ? null : decimal(value.toString());
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CompletableFuture<List<PublishedTradingEvent>> publish(List<StrategySignalEvent> signals) {
        CompletableFuture<List<PublishedTradingEvent>> chain = CompletableFuture.completedFuture(List.of());
        for (StrategySignalEvent signal : signals) {
            chain = chain.thenCompose(published -> eventBus.publish(envelope(signal))
                    .thenApply(next -> append(published, next)));
        }
        return chain;
    }

    private TradingEventEnvelope<StrategySignalEvent> envelope(StrategySignalEvent signal) {
        return TradingEventEnvelope.of(
                TradingEventType.STRATEGY_SIGNAL,
                TradingEventKeys.symbol(
                        TradingEventType.STRATEGY_SIGNAL,
                        signal.getProvider().toString(),
                        signal.getEnvironment().toString(),
                        signal.getAccount().toString(),
                        signal.getMarket().toString(),
                        signal.getSymbol().toString()
                ),
                signal
        );
    }

    private List<PublishedTradingEvent> append(List<PublishedTradingEvent> events, PublishedTradingEvent event) {
        return java.util.stream.Stream.concat(events.stream(), java.util.stream.Stream.of(event)).toList();
    }

    private LfaRunnerTarget target() {
        ExecutionProperties.SignalPlanner.Defaults defaults = executionProperties.signalPlanner().defaults();
        return new LfaRunnerTarget(
                first(properties.provider(), defaults.provider(), "provider"),
                first(properties.environment(), defaults.environment(), "environment"),
                first(properties.account(), defaults.account(), "account"),
                first(properties.market(), defaults.market(), "market")
        );
    }

    private String first(String configured, String fallback, String field) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        throw new IllegalArgumentException("LFA signal runner target is missing " + field);
    }

    public record LfaRunnerTarget(
            String provider,
            String environment,
            String account,
            String market
    ) {
    }

    public record LfaSignalRunResult(
            boolean enabled,
            String reason,
            LfaRunnerTarget target,
            int candidateSignals,
            int publishedSignals,
            List<String> signalIds,
            List<String> blockers
    ) {

        public LfaSignalRunResult {
            signalIds = signalIds == null ? List.of() : List.copyOf(signalIds);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        static LfaSignalRunResult disabled(String reason) {
            return new LfaSignalRunResult(false, reason, null, 0, 0, List.of(), List.of());
        }

        static LfaSignalRunResult skipped(String reason) {
            return new LfaSignalRunResult(true, reason, null, 0, 0, List.of(), List.of());
        }

        static LfaSignalRunResult blocked(String reason, LfaRunnerTarget target) {
            return new LfaSignalRunResult(true, reason, target, 0, 0, List.of(), List.of());
        }
    }

    public record LfaLifecycleStatus(
            boolean enabled,
            String configuredLifecycleState,
            String effectiveLifecycleState,
            List<String> allowedLifecycleStates,
            List<String> allowedNextLifecycleStates,
            boolean emergencyStopReactivationAllowed,
            boolean publishEnabled,
            List<String> blockers,
            String changedBy,
            String reason,
            Instant changedAt,
            String eventId,
            int openOrderCount,
            int openPositionCount,
            boolean drainComplete
    ) {

        public LfaLifecycleStatus {
            allowedLifecycleStates = allowedLifecycleStates == null ? List.of() : List.copyOf(allowedLifecycleStates);
            allowedNextLifecycleStates =
                    allowedNextLifecycleStates == null ? List.of() : List.copyOf(allowedNextLifecycleStates);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    private record DrainStatus(int openOrders, int openPositions) {

        private boolean complete() {
            return openOrders == 0 && openPositions == 0;
        }
    }

    private record LfaLifecycleMetadata(
            String changedBy,
            String reason,
            String eventId,
            Instant changedAt
    ) {

        private LfaLifecycleMetadata {
            changedAt = changedAt == null ? Instant.EPOCH : changedAt;
        }
    }

    private record BudgetDecision(List<String> reasons) {

        private BudgetDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        private boolean blocked() {
            return !reasons.isEmpty();
        }
    }

    private record GateDecision(List<String> reasons) {

        private GateDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        private static GateDecision allowed() {
            return new GateDecision(List.of());
        }

        private boolean blocked() {
            return !reasons.isEmpty();
        }
    }

    private record AllocationDecision(List<StrategySignalEvent> signals, List<String> reasons) {

        private AllocationDecision {
            signals = signals == null ? List.of() : List.copyOf(signals);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        private static AllocationDecision allowed(List<StrategySignalEvent> signals) {
            return new AllocationDecision(signals, List.of());
        }

        private static AllocationDecision blocked(String reason) {
            return new AllocationDecision(List.of(), List.of(reason));
        }

        private boolean blocked() {
            return !reasons.isEmpty();
        }
    }

    private record RankedMarketData(
            TradingStateProjection.MarketDataState state,
            BigDecimal spreadBps,
            BigDecimal topOfBookQuoteNotional,
            long ageMillis
    ) {
    }

    private record CurrentExposure(
            boolean valid,
            BigDecimal accountPositionNotional,
            BigDecimal symbolPositionNotional,
            int accountOpenPositions,
            int symbolOpenPositions
    ) {
    }

    private record UnrealizedLoss(
            boolean valid,
            BigDecimal accountLoss,
            BigDecimal symbolLoss
    ) {
    }

    private record DailyLoss(
            Optional<BigDecimal> accountLoss,
            Optional<BigDecimal> symbolLoss
    ) {
    }
}
