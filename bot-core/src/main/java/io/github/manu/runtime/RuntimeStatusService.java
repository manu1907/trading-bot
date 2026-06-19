package io.github.manu.runtime;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public final class RuntimeStatusService {

    public static final long DEFAULT_MAX_MARKET_DATA_AGE_MILLIS = 60_000L;
    public static final int DEFAULT_MIN_FRESH_MARKET_DATA_SYMBOLS = 1;

    private final ConfigManager configManager;
    private final TradingStateProjection projection;
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker;
    private final Clock clock;

    public RuntimeStatusService(
            ConfigManager configManager,
            TradingStateProjection projection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker
    ) {
        this(configManager, projection, reconciliationConfidenceTracker, Clock.systemUTC());
    }

    public RuntimeStatusService(
            ConfigManager configManager,
            TradingStateProjection projection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            Clock clock
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.reconciliationConfidenceTracker = Objects.requireNonNull(
                reconciliationConfidenceTracker,
                "reconciliationConfidenceTracker"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RuntimeStatus status(RuntimeStatusRequest request) {
        RuntimeStatusRequest normalized = normalize(request);
        Instant now = Instant.now(clock);
        ReconciliationTargetConfidence reconciliation = reconciliationConfidenceTracker.targetConfidence(
                normalized.provider(),
                normalized.environment(),
                normalized.account(),
                normalized.market()
        );
        ProjectionSummary projectionSummary = projectionSummary(normalized, now);
        List<String> blockers = blockers(normalized, reconciliation, projectionSummary);
        RuntimeReadiness readiness = readiness(blockers, reconciliation, projectionSummary);
        return new RuntimeStatus(
                now,
                readiness,
                target(normalized),
                new ReconciliationSummary(
                        reconciliation.status().name(),
                        reconciliation.observedStates(),
                        reconciliation.degradedStates(),
                        reconciliation.observedAt()
                ),
                projectionSummary,
                blockers
        );
    }

    private RuntimeStatusRequest normalize(RuntimeStatusRequest request) {
        RuntimeStatusRequest safeRequest = request == null ? RuntimeStatusRequest.defaults() : request;
        TradingBotProperties config = configManager.getConfig();
        if (config == null) {
            return new RuntimeStatusRequest(
                    requireText(safeRequest.provider(), "provider"),
                    requireText(safeRequest.environment(), "environment"),
                    requireText(safeRequest.account(), "account"),
                    requireText(safeRequest.market(), "market"),
                    normalizeMaxMarketDataAgeMillis(safeRequest.maxMarketDataAgeMillis()),
                    normalizeMinimumFreshSymbols(safeRequest.minFreshMarketDataSymbols()),
                    safeRequest.strategyId()
            );
        }
        ExchangeProperties exchange = config.getExchange();
        return new RuntimeStatusRequest(
                firstText(safeRequest.provider(), exchange.provider()),
                firstText(safeRequest.environment(), exchange.environment()),
                firstText(safeRequest.account(), exchange.account()),
                firstText(safeRequest.market(), exchange.market()),
                normalizeMaxMarketDataAgeMillis(safeRequest.maxMarketDataAgeMillis()),
                normalizeMinimumFreshSymbols(safeRequest.minFreshMarketDataSymbols()),
                safeRequest.strategyId()
        );
    }

    private RuntimeTarget target(RuntimeStatusRequest request) {
        TradingBotProperties config = configManager.getConfig();
        if (config == null) {
            return new RuntimeTarget(null, null, request.provider(), request.environment(), request.account(), request.market(), null);
        }
        return new RuntimeTarget(
                config.getBot().targetId(),
                config.getBot().instanceId(),
                request.provider(),
                request.environment(),
                request.account(),
                request.market(),
                config.getVersion()
        );
    }

    private ProjectionSummary projectionSummary(RuntimeStatusRequest request, Instant now) {
        TradingStateSnapshot snapshot = projection.snapshot();
        List<TradingStateProjection.MarketDataState> marketData = snapshot.marketData().stream()
                .filter(state -> request.provider().equals(state.provider()))
                .filter(state -> request.environment().equals(state.environment()))
                .filter(state -> request.market().equals(state.market()))
                .sorted(Comparator.comparing(TradingStateProjection.MarketDataState::symbol))
                .toList();
        Instant newestMarketDataAt = marketData.stream()
                .map(TradingStateProjection.MarketDataState::updatedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        Instant oldestMarketDataAt = marketData.stream()
                .map(TradingStateProjection.MarketDataState::updatedAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        long freshMarketDataSymbols = marketData.stream()
                .filter(state -> fresh(state.updatedAt(), now, request.maxMarketDataAgeMillis()))
                .count();
        long activePauses = projection.pauseGovernanceStates(
                        request.provider(),
                        request.environment(),
                        request.account(),
                        request.market()
                )
                .stream()
                .filter(state -> state.effectiveActive(now))
                .count();
        return new ProjectionSummary(
                projection.openOrderStates(request.provider(), request.environment(), request.account(), request.market()).size(),
                projection.openPositionStates(request.provider(), request.environment(), request.account(), request.market()).size(),
                projection.externalOrderInterventionStates(
                        request.provider(),
                        request.environment(),
                        request.account(),
                        request.market()
                ).size(),
                projection.externalPositionInterventionStates(
                        request.provider(),
                        request.environment(),
                        request.account(),
                        request.market()
                ).size(),
                projection.unknownOrderStatusStates(request.provider(), request.environment(), request.account(), request.market()).size(),
                projection.unresolvedOrderCommandStates(
                        request.provider(),
                        request.environment(),
                        request.account(),
                        request.market()
                ).size(),
                (int) activePauses,
                marketData.size(),
                (int) freshMarketDataSymbols,
                marketData.size() - (int) freshMarketDataSymbols,
                newestMarketDataAt,
                oldestMarketDataAt,
                strategyLifecycle(request)
        );
    }

    private StrategyLifecycleSummary strategyLifecycle(RuntimeStatusRequest request) {
        if (request.strategyId() == null || request.strategyId().isBlank()) {
            return null;
        }
        return projection.strategyLifecycle(
                        request.strategyId(),
                        request.provider(),
                        request.environment(),
                        request.account(),
                        request.market()
                )
                .map(state -> new StrategyLifecycleSummary(
                        state.strategyId(),
                        state.lifecycleState(),
                        state.previousLifecycleState(),
                        state.updatedAt()
                ))
                .orElseGet(() -> new StrategyLifecycleSummary(request.strategyId(), "UNKNOWN", null, null));
    }

    private boolean fresh(Instant observedAt, Instant now, long maxAgeMillis) {
        if (observedAt == null) {
            return false;
        }
        long ageMillis = Duration.between(observedAt, now).toMillis();
        return ageMillis >= 0 && ageMillis <= maxAgeMillis;
    }

    private List<String> blockers(
            RuntimeStatusRequest request,
            ReconciliationTargetConfidence reconciliation,
            ProjectionSummary projectionSummary
    ) {
        List<String> blockers = new ArrayList<>();
        if (reconciliation.status() == ReconciliationTargetConfidence.Status.NO_OBSERVATIONS) {
            blockers.add("reconciliation:no_observations");
        } else if (reconciliation.status() == ReconciliationTargetConfidence.Status.DEGRADED) {
            blockers.add("reconciliation:degraded");
        }
        if (projectionSummary.unknownOrderStatuses() > 0) {
            blockers.add("orders:unknown_status");
        }
        if (projectionSummary.unresolvedOrderCommands() > 0) {
            blockers.add("orders:unresolved_command");
        }
        if (projectionSummary.externalOrderInterventions() > 0) {
            blockers.add("interventions:external_orders");
        }
        if (projectionSummary.externalPositionInterventions() > 0) {
            blockers.add("interventions:external_positions");
        }
        if (projectionSummary.activePauses() > 0) {
            blockers.add("governance:active_pause");
        }
        if (projectionSummary.marketDataSymbols() == 0) {
            blockers.add("market_data:no_symbols");
        } else if (projectionSummary.freshMarketDataSymbols() < request.minFreshMarketDataSymbols()) {
            blockers.add("market_data:fresh_symbols_below_minimum");
        }
        return List.copyOf(blockers);
    }

    private RuntimeReadiness readiness(
            List<String> blockers,
            ReconciliationTargetConfidence reconciliation,
            ProjectionSummary projectionSummary
    ) {
        if (blockers.stream().anyMatch(blocker -> blocker.startsWith("reconciliation:")
                || blocker.startsWith("orders:")
                || blocker.startsWith("interventions:")
                || blocker.startsWith("governance:"))) {
            return RuntimeReadiness.BLOCKED;
        }
        if (!blockers.isEmpty()
                || reconciliation.status() != ReconciliationTargetConfidence.Status.CONFIDENT
                || projectionSummary.freshMarketDataSymbols() == 0) {
            return RuntimeReadiness.ATTENTION;
        }
        return RuntimeReadiness.READY;
    }

    private long normalizeMaxMarketDataAgeMillis(Long value) {
        if (value == null) {
            return DEFAULT_MAX_MARKET_DATA_AGE_MILLIS;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("maxMarketDataAgeMillis must be positive");
        }
        return value;
    }

    private int normalizeMinimumFreshSymbols(Integer value) {
        if (value == null) {
            return DEFAULT_MIN_FRESH_MARKET_DATA_SYMBOLS;
        }
        if (value < 0) {
            throw new IllegalArgumentException("minFreshMarketDataSymbols must not be negative");
        }
        return value;
    }

    private String firstText(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return requireText(fallback, "fallback");
        }
        return candidate.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record RuntimeStatusRequest(
            String provider,
            String environment,
            String account,
            String market,
            Long maxMarketDataAgeMillis,
            Integer minFreshMarketDataSymbols,
            String strategyId
    ) {
        public static RuntimeStatusRequest defaults() {
            return new RuntimeStatusRequest(null, null, null, null, null, null, null);
        }
    }

    public enum RuntimeReadiness {
        READY,
        ATTENTION,
        BLOCKED
    }

    public record RuntimeStatus(
            Instant generatedAt,
            RuntimeReadiness readiness,
            RuntimeTarget target,
            ReconciliationSummary reconciliation,
            ProjectionSummary projection,
            List<String> blockers
    ) {
        public RuntimeStatus {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record RuntimeTarget(
            String targetId,
            String instanceId,
            String provider,
            String environment,
            String account,
            String market,
            Integer configVersion
    ) {
    }

    public record ReconciliationSummary(
            String status,
            int observedStates,
            int degradedStates,
            Instant observedAt
    ) {
    }

    public record ProjectionSummary(
            int openOrders,
            int openPositions,
            int externalOrderInterventions,
            int externalPositionInterventions,
            int unknownOrderStatuses,
            int unresolvedOrderCommands,
            int activePauses,
            int marketDataSymbols,
            int freshMarketDataSymbols,
            int staleMarketDataSymbols,
            Instant newestMarketDataAt,
            Instant oldestMarketDataAt,
            StrategyLifecycleSummary strategyLifecycle
    ) {
    }

    public record StrategyLifecycleSummary(
            String strategyId,
            String lifecycleState,
            String previousLifecycleState,
            Instant updatedAt
    ) {
    }
}
