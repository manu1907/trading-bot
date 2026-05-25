package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.messaging.PublishedTradingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        List<PublishedTradingEvent> published = new ArrayList<>();
        if (Boolean.TRUE.equals(reconciliation.openOrdersEnabled())) {
            List<String> symbols = reconciliation.openOrderSymbols();
            if (symbols.isEmpty()) {
                published.addAll(publisher.publishOpenOrders(orderSnapshots.openOrders(null)).join());
            } else {
                for (String symbol : symbols) {
                    published.addAll(publisher.publishOpenOrders(orderSnapshots.openOrders(symbol)).join());
                }
            }
        }
        if (Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())) {
            published.addAll(publisher.publishFuturesBalances(futuresSnapshots.balances()).join());
        }
        if (Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())) {
            published.addAll(publisher.publishFuturesAccount(futuresSnapshots.accountInfo()).join());
        }
        if (Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled())) {
            published.addAll(publisher.publishFuturesPositions(futuresSnapshots.positionRisk(
                    new BinanceFuturesPositionRiskQuery(null, null, null)
            )).join());
        }
        if (Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())) {
            published.addAll(publisher.publishCrossMarginAccount(marginSnapshots.crossAccount()).join());
        }
        if (Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled())) {
            published.addAll(publisher.publishIsolatedMarginAccount(marginSnapshots.isolatedAccount(
                    new BinanceIsolatedMarginAccountQuery(reconciliation.isolatedMarginSymbols())
            )).join());
        }
        return List.copyOf(published);
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
