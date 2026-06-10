package io.github.manu.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProjectionSnapshotLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProjectionSnapshotLifecycle.class);

    private final TradingStateProjection projection;
    private final TradingStateProjectionStore store;
    private final AtomicBoolean running = new AtomicBoolean();

    public ProjectionSnapshotLifecycle(TradingStateProjection projection, TradingStateProjectionStore store) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            store.load().ifPresent(snapshot -> {
                projection.restore(snapshot);
                log.info(
                        "Loaded trading-state projection snapshot: balances={}, positions={}, orders={}, risks={}, dailyRealizedPnl={}, pauses={}",
                        snapshot.balances().size(),
                        snapshot.positions().size(),
                        snapshot.orders().size(),
                        snapshot.risks().size(),
                        snapshot.dailyRealizedPnl().size(),
                        snapshot.pauseGovernance().size()
                );
            });
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            TradingStateSnapshot snapshot = projection.snapshot();
            store.save(snapshot);
            log.info(
                    "Saved trading-state projection snapshot: balances={}, positions={}, orders={}, risks={}, dailyRealizedPnl={}, pauses={}",
                    snapshot.balances().size(),
                    snapshot.positions().size(),
                    snapshot.orders().size(),
                    snapshot.risks().size(),
                    snapshot.dailyRealizedPnl().size(),
                    snapshot.pauseGovernance().size()
            );
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
