package io.github.manu.strategy.lfa;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LfaSignalRunner {

    private static final Logger log = LoggerFactory.getLogger(LfaSignalRunner.class);

    private final LfaMarketSignalAnalyzer analyzer;
    private final LfaStrategyProperties.SignalRunner properties;
    private final TradingStateProjection projection;
    private final TradingEventBus eventBus;
    private final ExecutionProperties executionProperties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

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
                Clock.systemUTC()
        );
    }

    LfaSignalRunner(
            LfaMarketSignalAnalyzer analyzer,
            LfaStrategyProperties.SignalRunner properties,
            TradingStateProjection projection,
            TradingEventBus eventBus,
            ExecutionProperties executionProperties,
            Clock clock
    ) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.executionProperties = Objects.requireNonNull(executionProperties, "executionProperties");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            List<StrategySignalEvent> signals = analyzer.analyze(
                    request,
                    projection.snapshot().marketData(),
                    now
            ).stream().limit(properties.maxSignalsPerRun()).toList();
            if (signals.isEmpty()) {
                return new LfaSignalRunResult(true, "lfa_signal_runner:no_signal", target, 0, 0, List.of());
            }
            List<PublishedTradingEvent> published = publish(signals).join();
            return new LfaSignalRunResult(
                    true,
                    "lfa_signal_runner:published",
                    target,
                    signals.size(),
                    published.size(),
                    signals.stream().map(signal -> signal.getSignalId().toString()).toList()
            );
        } finally {
            running.set(false);
        }
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
            List<String> signalIds
    ) {

        public LfaSignalRunResult {
            signalIds = signalIds == null ? List.of() : List.copyOf(signalIds);
        }

        static LfaSignalRunResult disabled(String reason) {
            return new LfaSignalRunResult(false, reason, null, 0, 0, List.of());
        }

        static LfaSignalRunResult skipped(String reason) {
            return new LfaSignalRunResult(true, reason, null, 0, 0, List.of());
        }

        static LfaSignalRunResult blocked(String reason, LfaRunnerTarget target) {
            return new LfaSignalRunResult(true, reason, target, 0, 0, List.of());
        }
    }
}
