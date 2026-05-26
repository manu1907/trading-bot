package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class BinanceRestSnapshotReconciliationRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BinanceRestSnapshotReconciliationRuntime.class);

    private final BinanceProperties.Reconciliation reconciliation;
    private final OrderSnapshots orderSnapshots;
    private final FuturesSnapshots futuresSnapshots;
    private final MarginSnapshots marginSnapshots;
    private final OptionsSnapshots optionsSnapshots;
    private final BinanceRestSnapshotEventPublisher publisher;
    private final BinanceRestSnapshotProjectionComparator projectionComparator;
    private final ReconciliationConfidenceTracker confidenceTracker;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();
    private final LinkedHashSet<String> recentEventIds = new LinkedHashSet<>();

    private ScheduledFuture<?> scheduledRun;
    private boolean stopped = true;

    BinanceRestSnapshotReconciliationRuntime(
            BinanceProperties.Reconciliation reconciliation,
            OrderSnapshots orderSnapshots,
            FuturesSnapshots futuresSnapshots,
            MarginSnapshots marginSnapshots,
            OptionsSnapshots optionsSnapshots,
            BinanceRestSnapshotEventPublisher publisher,
            ScheduledExecutorService executor
    ) {
        this(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                publisher,
                null,
                null,
                executor,
                List.of()
        );
    }

    BinanceRestSnapshotReconciliationRuntime(
            BinanceProperties.Reconciliation reconciliation,
            OrderSnapshots orderSnapshots,
            FuturesSnapshots futuresSnapshots,
            MarginSnapshots marginSnapshots,
            OptionsSnapshots optionsSnapshots,
            BinanceRestSnapshotEventPublisher publisher,
            BinanceRestSnapshotProjectionComparator projectionComparator,
            ScheduledExecutorService executor,
            List<String> initialRecentEventIds
    ) {
        this(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                publisher,
                projectionComparator,
                null,
                executor,
                initialRecentEventIds
        );
    }

    BinanceRestSnapshotReconciliationRuntime(
            BinanceProperties.Reconciliation reconciliation,
            OrderSnapshots orderSnapshots,
            FuturesSnapshots futuresSnapshots,
            MarginSnapshots marginSnapshots,
            OptionsSnapshots optionsSnapshots,
            BinanceRestSnapshotEventPublisher publisher,
            BinanceRestSnapshotProjectionComparator projectionComparator,
            ReconciliationConfidenceTracker confidenceTracker,
            ScheduledExecutorService executor,
            List<String> initialRecentEventIds
    ) {
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.orderSnapshots = orderSnapshots;
        this.futuresSnapshots = futuresSnapshots;
        this.marginSnapshots = marginSnapshots;
        this.optionsSnapshots = optionsSnapshots;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.projectionComparator = projectionComparator;
        this.confidenceTracker = confidenceTracker;
        this.executor = Objects.requireNonNull(executor, "executor");
        requirePositiveInterval();
        requirePositiveDedupeWindow();
        requireConfiguredSources();
        rememberEventIds(Objects.requireNonNull(initialRecentEventIds, "initialRecentEventIds"));
    }

    BinanceRestSnapshotReconciliationRuntime(
            BinanceProperties.Reconciliation reconciliation,
            OrderSnapshots orderSnapshots,
            FuturesSnapshots futuresSnapshots,
            MarginSnapshots marginSnapshots,
            OptionsSnapshots optionsSnapshots,
            BinanceRestSnapshotEventPublisher publisher,
            ScheduledExecutorService executor,
            List<String> initialRecentEventIds
    ) {
        this(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
                publisher,
                null,
                executor,
                initialRecentEventIds
        );
    }

    void start() {
        synchronized (lock) {
            if (!stopped) {
                return;
            }
            stopped = false;
            schedule(Duration.ZERO);
        }
    }

    List<PublishedTradingEvent> runOnce() {
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        if (Boolean.TRUE.equals(reconciliation.openOrdersEnabled())) {
            List<String> symbols = reconciliation.openOrderSymbols();
            if (symbols.isEmpty()) {
                envelopes.addAll(publisher.mapOpenOrders(orderSnapshots.openOrders(null)));
            } else {
                for (String symbol : symbols) {
                    envelopes.addAll(publisher.mapOpenOrders(orderSnapshots.openOrders(symbol)));
                }
            }
        }
        if (Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())) {
            envelopes.addAll(publisher.mapFuturesBalances(futuresSnapshots.balances()));
        }
        if (Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())) {
            envelopes.addAll(publisher.mapFuturesAccount(futuresSnapshots.accountInfo()));
        }
        if (Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled())) {
            envelopes.addAll(publisher.mapFuturesPositions(futuresSnapshots.positionRisk(
                    new BinanceFuturesPositionRiskQuery(null, null, null)
            )));
        }
        if (Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())) {
            envelopes.addAll(publisher.mapCrossMarginAccount(marginSnapshots.crossAccount()));
        }
        if (Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled())) {
            envelopes.addAll(publisher.mapIsolatedMarginAccount(marginSnapshots.isolatedAccount(
                    new BinanceIsolatedMarginAccountQuery(reconciliation.isolatedMarginSymbols())
            )));
        }
        if (Boolean.TRUE.equals(reconciliation.optionsAccountEnabled())) {
            envelopes.addAll(publisher.mapOptionsMarginAccount(optionsSnapshots.marginAccount()));
        }
        if (Boolean.TRUE.equals(reconciliation.optionsPositionsEnabled())) {
            List<String> symbols = reconciliation.optionsPositionSymbols();
            if (symbols.isEmpty()) {
                envelopes.addAll(publisher.mapOptionsPositions(optionsSnapshots.positions(null)));
            } else {
                for (String symbol : symbols) {
                    envelopes.addAll(publisher.mapOptionsPositions(optionsSnapshots.positions(symbol)));
                }
            }
        }
        compareProjection(envelopes);
        List<TradingEventEnvelope<?>> publishable = uniqueAndNotRecentlyPublished(envelopes);
        List<PublishedTradingEvent> published = publisher.publishEnvelopes(publishable).join();
        remember(publishable);
        return published;
    }

    private void compareProjection(List<TradingEventEnvelope<?>> envelopes) {
        if (!Boolean.TRUE.equals(reconciliation.projectionComparisonEnabled()) || projectionComparator == null) {
            return;
        }
        List<BinanceRestSnapshotProjectionComparison> comparisons = projectionComparator.compare(envelopes);
        if (confidenceTracker != null) {
            confidenceTracker.recordAll(comparisons.stream()
                    .map(BinanceRestSnapshotProjectionComparison::toObservation)
                    .toList());
        }
        List<BinanceRestSnapshotProjectionComparison> unaligned = comparisons.stream()
                .filter(comparison -> !comparison.aligned())
                .toList();
        for (BinanceRestSnapshotProjectionComparison comparison : unaligned) {
            log.warn(
                    "Binance REST snapshot does not match projected state: eventType={}, entityKey={}, status={}, differences={}",
                    comparison.eventType(),
                    comparison.entityKey(),
                    comparison.status(),
                    comparison.differences()
            );
        }
        if (!unaligned.isEmpty() && Boolean.TRUE.equals(reconciliation.failOnProjectionMismatch())) {
            throw new IllegalStateException("Binance REST snapshot/projection mismatch: " + unaligned);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopped = true;
            if (scheduledRun != null) {
                scheduledRun.cancel(false);
                scheduledRun = null;
            }
        }
    }

    private void executeScheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Binance REST snapshot reconciliation failed", e);
        } finally {
            synchronized (lock) {
                if (!stopped) {
                    schedule(Duration.ofSeconds(reconciliation.intervalSeconds()));
                }
            }
        }
    }

    private void schedule(Duration delay) {
        scheduledRun = executor.schedule(
                this::executeScheduledRun,
                Math.max(0L, delay.toMillis()),
                TimeUnit.MILLISECONDS
        );
    }

    private void requirePositiveInterval() {
        Integer intervalSeconds = reconciliation.intervalSeconds();
        if (intervalSeconds == null || intervalSeconds <= 0) {
            throw new IllegalArgumentException("reconciliation.interval_seconds must be positive");
        }
    }

    private void requirePositiveDedupeWindow() {
        Integer dedupeWindowEventIds = reconciliation.dedupeWindowEventIds();
        if (dedupeWindowEventIds == null || dedupeWindowEventIds <= 0) {
            throw new IllegalArgumentException("reconciliation.dedupe_window_event_ids must be positive");
        }
    }

    private void requireConfiguredSources() {
        if (Boolean.TRUE.equals(reconciliation.openOrdersEnabled()) && orderSnapshots == null) {
            throw new IllegalArgumentException("open order reconciliation requires order snapshots");
        }
        if ((Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled())) && futuresSnapshots == null) {
            throw new IllegalArgumentException("futures reconciliation requires futures snapshots");
        }
        if ((Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled())) && marginSnapshots == null) {
            throw new IllegalArgumentException("margin reconciliation requires margin snapshots");
        }
        if ((Boolean.TRUE.equals(reconciliation.optionsAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.optionsPositionsEnabled())) && optionsSnapshots == null) {
            throw new IllegalArgumentException("options reconciliation requires options snapshots");
        }
    }

    private List<TradingEventEnvelope<?>> uniqueAndNotRecentlyPublished(List<TradingEventEnvelope<?>> envelopes) {
        Set<String> eventIds = new LinkedHashSet<>();
        List<TradingEventEnvelope<?>> unique = new ArrayList<>();
        synchronized (lock) {
            for (TradingEventEnvelope<?> envelope : envelopes) {
                String eventId = eventId(envelope);
                if (!eventIds.add(eventId)) {
                    log.warn("Suppressing duplicate Binance reconciliation event inside run: {}", eventId);
                } else if (recentEventIds.contains(eventId)) {
                    log.warn("Suppressing recently published Binance reconciliation event: {}", eventId);
                } else {
                    unique.add(envelope);
                }
            }
        }
        return List.copyOf(unique);
    }

    private void remember(List<TradingEventEnvelope<?>> envelopes) {
        synchronized (lock) {
            for (TradingEventEnvelope<?> envelope : envelopes) {
                rememberEventId(eventId(envelope));
            }
        }
    }

    private void rememberEventIds(List<String> eventIds) {
        synchronized (lock) {
            for (String eventId : eventIds) {
                if (eventId != null && !eventId.isBlank()) {
                    rememberEventId(eventId.trim());
                }
            }
        }
    }

    private void rememberEventId(String eventId) {
        recentEventIds.remove(eventId);
        recentEventIds.add(eventId);
        while (recentEventIds.size() > reconciliation.dedupeWindowEventIds()) {
            recentEventIds.remove(recentEventIds.getFirst());
        }
    }

    private String eventId(TradingEventEnvelope<?> envelope) {
        Object value = envelope.value();
        if (value instanceof OrderResultEvent event) {
            return event.getEventId().toString();
        }
        if (value instanceof BalanceUpdateEvent event) {
            return event.getEventId().toString();
        }
        if (value instanceof PositionUpdateEvent event) {
            return event.getEventId().toString();
        }
        if (value instanceof RiskUpdateEvent event) {
            return event.getEventId().toString();
        }
        throw new IllegalArgumentException("Unsupported reconciliation event type: " + envelope.eventType());
    }

    interface OrderSnapshots {

        List<BinanceOrderResult> openOrders(String symbol);
    }

    interface FuturesSnapshots {

        List<BinanceFuturesBalance> balances();

        BinanceFuturesAccountSnapshot accountInfo();

        List<BinanceFuturesPositionSnapshot> positionRisk(BinanceFuturesPositionRiskQuery query);
    }

    interface MarginSnapshots {

        BinanceCrossMarginAccountSnapshot crossAccount();

        BinanceIsolatedMarginAccountSnapshot isolatedAccount(BinanceIsolatedMarginAccountQuery query);
    }

    interface OptionsSnapshots {

        BinanceOptionsMarginAccountSnapshot marginAccount();

        List<BinanceOptionsPositionSnapshot> positions(String symbol);
    }
}
