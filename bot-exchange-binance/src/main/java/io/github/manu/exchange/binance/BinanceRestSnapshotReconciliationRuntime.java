package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.messaging.PublishedTradingEvent;
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
    private final BinanceRestSnapshotEventPublisher publisher;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();

    private ScheduledFuture<?> scheduledRun;
    private boolean stopped = true;

    BinanceRestSnapshotReconciliationRuntime(
            BinanceProperties.Reconciliation reconciliation,
            OrderSnapshots orderSnapshots,
            FuturesSnapshots futuresSnapshots,
            MarginSnapshots marginSnapshots,
            BinanceRestSnapshotEventPublisher publisher,
            ScheduledExecutorService executor
    ) {
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.orderSnapshots = orderSnapshots;
        this.futuresSnapshots = futuresSnapshots;
        this.marginSnapshots = marginSnapshots;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.executor = Objects.requireNonNull(executor, "executor");
        requirePositiveInterval();
        requireConfiguredSources();
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
        return publisher.publishEnvelopes(unique(envelopes)).join();
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
    }

    private List<TradingEventEnvelope<?>> unique(List<TradingEventEnvelope<?>> envelopes) {
        Set<String> eventIds = new LinkedHashSet<>();
        List<TradingEventEnvelope<?>> unique = new ArrayList<>();
        for (TradingEventEnvelope<?> envelope : envelopes) {
            String eventId = eventId(envelope);
            if (eventIds.add(eventId)) {
                unique.add(envelope);
            } else {
                log.warn("Suppressing duplicate Binance reconciliation event: {}", eventId);
            }
        }
        return List.copyOf(unique);
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
}
