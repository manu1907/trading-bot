package io.github.manu.position;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PositionManager {

    private static final int QUANTITY_SCALE = 12;

    private final TradingStateProjection projection;
    private final PositionProperties properties;
    private final TradingEventBus eventBus;
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> emittedSignalIds = ConcurrentHashMap.newKeySet();

    public PositionManager(TradingStateProjection projection) {
        this(projection, new PositionProperties(null), null, null, Clock.systemUTC());
    }

    public PositionManager(
            TradingStateProjection projection,
            PositionProperties properties,
            TradingEventBus eventBus,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker
    ) {
        this(projection, properties, eventBus, reconciliationConfidenceTracker, Clock.systemUTC());
    }

    PositionManager(
            TradingStateProjection projection,
            PositionProperties properties,
            TradingEventBus eventBus,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            Clock clock
    ) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.properties = properties == null ? new PositionProperties(null) : properties;
        this.eventBus = eventBus;
        this.reconciliationConfidenceTracker = reconciliationConfidenceTracker;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(
            fixedDelayString = "${trading.position.lifecycle.interval-millis:30000}",
            initialDelayString = "${trading.position.lifecycle.initial-delay-millis:30000}"
    )
    public void scheduledRun() {
        runOnce();
    }

    public PositionLifecycleRunResult runOnce() {
        PositionProperties.Lifecycle lifecycle = properties.lifecycle();
        if (!lifecycle.enabled()) {
            return PositionLifecycleRunResult.disabled("position_lifecycle:disabled");
        }
        if (eventBus == null) {
            return PositionLifecycleRunResult.blocked("position_lifecycle:event_bus_missing", List.of("event_bus_missing"));
        }
        Target target = target(lifecycle.target());
        List<String> targetProblems = target.problems();
        if (!targetProblems.isEmpty()) {
            return PositionLifecycleRunResult.blocked("position_lifecycle:target_incomplete", targetProblems);
        }
        if (!running.compareAndSet(false, true)) {
            return PositionLifecycleRunResult.skipped("position_lifecycle:already_running");
        }
        try {
            Instant now = Instant.now(clock);
            TradingStateSnapshot snapshot = projection.snapshot();
            List<String> targetBlockers = targetBlockers(lifecycle, target, now);
            if (!targetBlockers.isEmpty()) {
                return PositionLifecycleRunResult.blocked("position_lifecycle:target_blocked", targetBlockers);
            }
            List<TradingStateProjection.PositionState> positions = snapshot.positions().stream()
                    .filter(TradingStateProjection.PositionState::open)
                    .filter(position -> target.matches(position))
                    .filter(position -> target.symbols().isEmpty() || target.symbols().contains(position.symbol()))
                    .toList();
            if (positions.isEmpty()) {
                return new PositionLifecycleRunResult(true, "position_lifecycle:no_open_position", 0, 0, List.of());
            }

            int evaluated = 0;
            int published = 0;
            java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
            for (TradingStateProjection.PositionState position : positions) {
                evaluated++;
                PositionDecision decision = decision(lifecycle, position, now);
                if (decision.blocked()) {
                    blockers.addAll(decision.reasons());
                    continue;
                }
                if (!decision.actionable()) {
                    continue;
                }
                StrategySignalEvent signal = signal(lifecycle, position, decision, now);
                if (!emittedSignalIds.add(signal.getSignalId().toString())) {
                    blockers.add("duplicate_signal:" + signal.getSymbol());
                    continue;
                }
                eventBus.publish(envelope(signal)).join();
                published++;
            }
            String reason = published > 0 ? "position_lifecycle:published" : "position_lifecycle:no_action";
            return new PositionLifecycleRunResult(true, reason, evaluated, published, List.copyOf(blockers));
        } finally {
            running.set(false);
        }
    }

    public boolean hasOpenPositions(String provider, String environment, String account, String market) {
        return projection.hasOpenPositions(provider, environment, account, market);
    }

    @Deprecated
    public boolean hasOpenPositions() {
        throw new IllegalStateException("Runtime target is required to check open positions");
    }

    private List<String> targetBlockers(PositionProperties.Lifecycle lifecycle, Target target, Instant now) {
        java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
        if (lifecycle.requireReconciliationConfidence()) {
            if (reconciliationConfidenceTracker == null) {
                blockers.add("reconciliation_tracker_missing");
            } else {
                ReconciliationTargetConfidence confidence = reconciliationConfidenceTracker.targetConfidence(
                        target.provider(),
                        target.environment(),
                        target.account(),
                        target.market()
                );
                if (confidence.status() != ReconciliationTargetConfidence.Status.CONFIDENT) {
                    blockers.add("reconciliation_not_confident:" + confidence.status().name().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (lifecycle.skipWhenUnknownOrdersExist()
                && projection.hasUnknownOrderStatuses(target.provider(), target.environment(), target.account(), target.market())) {
            blockers.add("unknown_order_status_exists");
        }
        if (lifecycle.skipWhenUnresolvedCommandsExist()
                && projection.hasUnresolvedOrderCommands(target.provider(), target.environment(), target.account(), target.market())) {
            blockers.add("unresolved_order_command_exists");
        }
        if (projection.accountPaused(target.provider(), target.environment(), target.account(), target.market(), now)) {
            blockers.add("account_paused");
        }
        return blockers;
    }

    private PositionDecision decision(
            PositionProperties.Lifecycle lifecycle,
            TradingStateProjection.PositionState position,
            Instant now
    ) {
        java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
        if (projection.symbolPaused(position.provider(), position.environment(), position.account(), position.market(), position.symbol(), now)) {
            blockers.add("symbol_paused:" + position.symbol());
        }
        if (lifecycle.skipWhenOpenOrdersExist()
                && !projection.openOrderStates(position.provider(), position.environment(), position.account(), position.market(), position.symbol()).isEmpty()) {
            blockers.add("open_order_exists:" + position.symbol());
        }
        if (lifecycle.requireMarketData()) {
            projection.marketData(position.provider(), position.environment(), position.market(), position.symbol())
                    .ifPresentOrElse(
                            marketData -> {
                                Duration age = Duration.between(marketData.updatedAt(), now);
                                if (age.toMillis() > lifecycle.maxMarketDataAgeMillis()) {
                                    blockers.add("market_data_stale:" + position.symbol());
                                }
                            },
                            () -> blockers.add("market_data_missing:" + position.symbol())
                    );
        }
        BigDecimal amount = decimal(position.positionAmount());
        BigDecimal unrealizedPnl = decimal(position.unrealizedPnl());
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            blockers.add("position_amount_missing_or_zero:" + position.symbol());
        }
        if (unrealizedPnl == null) {
            blockers.add("unrealized_pnl_missing:" + position.symbol());
        }
        if (!blockers.isEmpty()) {
            return PositionDecision.blocked(blockers);
        }
        BigDecimal loss = unrealizedPnl.signum() < 0 ? unrealizedPnl.abs() : BigDecimal.ZERO;
        BigDecimal closeLoss = decimal(lifecycle.closeWhenUnrealizedLossAtLeast());
        if (closeLoss != null && loss.compareTo(closeLoss) >= 0) {
            return PositionDecision.fullClose("unrealized_loss_close_threshold");
        }
        BigDecimal reduceLoss = decimal(lifecycle.reduceWhenUnrealizedLossAtLeast());
        if (reduceLoss != null && loss.compareTo(reduceLoss) >= 0) {
            BigDecimal reduceQuantity = amount.abs()
                    .multiply(decimal(lifecycle.reduceFraction()))
                    .setScale(QUANTITY_SCALE, RoundingMode.DOWN)
                    .stripTrailingZeros();
            if (reduceQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                return PositionDecision.blocked(List.of("reduce_quantity_non_positive:" + position.symbol()));
            }
            return PositionDecision.reduce("unrealized_loss_reduce_threshold", normalize(reduceQuantity));
        }
        return PositionDecision.none();
    }

    private StrategySignalEvent signal(
            PositionProperties.Lifecycle lifecycle,
            TradingStateProjection.PositionState position,
            PositionDecision decision,
            Instant now
    ) {
        StrategySignalType signalType = signalType(position, decision);
        String signalId = signalId(position, decision);
        Map<CharSequence, CharSequence> features = new LinkedHashMap<>();
        features.put("order_type", lifecycle.orderType());
        if (lifecycle.timeInForce() != null) {
            features.put("time_in_force", lifecycle.timeInForce());
        }
        if (position.positionSide() != null && !position.positionSide().isBlank()) {
            features.put("position_side", position.positionSide());
        }
        if (decision.fullClose()) {
            features.put("close_position", "true");
        }
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("source", "position_lifecycle");
        attributes.put("position_event_id", position.eventId());
        attributes.put("position_amount", position.positionAmount());
        attributes.put("position_side", position.positionSide());
        attributes.put("position_unrealized_pnl", position.unrealizedPnl());
        attributes.put("position_lifecycle_reason", decision.reason());
        attributes.put("position_lifecycle_action", decision.fullClose() ? "close" : "reduce");
        attributes.put("signal_ttl_millis", Long.toString(lifecycle.maxMarketDataAgeMillis()));
        return StrategySignalEvent.newBuilder()
                .setEventId("position-lifecycle-signal:" + signalId)
                .setSchemaVersion(1)
                .setSignalId(signalId)
                .setStrategyId(lifecycle.strategyId())
                .setProvider(position.provider())
                .setEnvironment(position.environment())
                .setAccount(position.account())
                .setMarket(position.market())
                .setSymbol(position.symbol())
                .setSignalType(signalType)
                .setConfidence(1.0d)
                .setTargetQuantity(decision.fullClose() ? null : decision.quantity())
                .setTargetNotional(null)
                .setLimitPrice(null)
                .setStopPrice(null)
                .setEmittedAtMicros(now)
                .setFeatures(features)
                .setAttributes(attributes)
                .build();
    }

    private StrategySignalType signalType(TradingStateProjection.PositionState position, PositionDecision decision) {
        boolean longPosition = longPosition(position);
        if (decision.fullClose()) {
            return longPosition ? StrategySignalType.EXIT_LONG : StrategySignalType.EXIT_SHORT;
        }
        return longPosition ? StrategySignalType.REDUCE_LONG : StrategySignalType.REDUCE_SHORT;
    }

    private boolean longPosition(TradingStateProjection.PositionState position) {
        String side = position.positionSide() == null ? "" : position.positionSide().trim().toUpperCase(Locale.ROOT);
        if ("LONG".equals(side)) {
            return true;
        }
        if ("SHORT".equals(side)) {
            return false;
        }
        BigDecimal amount = decimal(position.positionAmount());
        return amount == null || amount.signum() >= 0;
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

    private String signalId(TradingStateProjection.PositionState position, PositionDecision decision) {
        return String.join(
                ":",
                "position-lifecycle",
                position.provider(),
                position.environment(),
                position.account(),
                position.market(),
                position.symbol(),
                position.positionSide() == null ? "BOTH" : position.positionSide(),
                decision.fullClose() ? "close" : "reduce",
                position.eventId() == null ? "no-event" : position.eventId()
        ).replace('/', '-');
    }

    private Target target(PositionProperties.Target target) {
        return new Target(target.provider(), target.environment(), target.account(), target.market(), target.symbols());
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    private String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record PositionLifecycleRunResult(
            boolean enabled,
            String reason,
            int evaluatedPositions,
            int publishedSignals,
            List<String> blockers
    ) {
        public PositionLifecycleRunResult {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        static PositionLifecycleRunResult disabled(String reason) {
            return new PositionLifecycleRunResult(false, reason, 0, 0, List.of());
        }

        static PositionLifecycleRunResult skipped(String reason) {
            return new PositionLifecycleRunResult(true, reason, 0, 0, List.of());
        }

        static PositionLifecycleRunResult blocked(String reason, List<String> blockers) {
            return new PositionLifecycleRunResult(true, reason, 0, 0, blockers);
        }
    }

    private record Target(
            String provider,
            String environment,
            String account,
            String market,
            List<String> symbols
    ) {
        private List<String> problems() {
            java.util.ArrayList<String> problems = new java.util.ArrayList<>();
            if (provider == null || provider.isBlank()) {
                problems.add("target_provider_missing");
            }
            if (environment == null || environment.isBlank()) {
                problems.add("target_environment_missing");
            }
            if (account == null || account.isBlank()) {
                problems.add("target_account_missing");
            }
            if (market == null || market.isBlank()) {
                problems.add("target_market_missing");
            }
            return problems;
        }

        private boolean matches(TradingStateProjection.PositionState position) {
            return provider.equals(position.provider())
                    && environment.equals(position.environment())
                    && account.equals(position.account())
                    && market.equals(position.market());
        }
    }

    private record PositionDecision(
            boolean actionable,
            boolean fullClose,
            String quantity,
            String reason,
            List<String> reasons
    ) {
        private PositionDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        private boolean blocked() {
            return !reasons.isEmpty();
        }

        private static PositionDecision none() {
            return new PositionDecision(false, false, null, "no_threshold_breached", List.of());
        }

        private static PositionDecision fullClose(String reason) {
            return new PositionDecision(true, true, null, reason, List.of());
        }

        private static PositionDecision reduce(String reason, String quantity) {
            return new PositionDecision(true, false, quantity, reason, List.of());
        }

        private static PositionDecision blocked(List<String> reasons) {
            return new PositionDecision(false, false, null, "blocked", reasons);
        }
    }
}
