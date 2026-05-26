package io.github.manu.position;

import io.github.manu.projection.TradingStateProjection;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PositionManager {

    private final TradingStateProjection projection;

    public PositionManager(TradingStateProjection projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public boolean hasOpenPositions(String provider, String environment, String account, String market) {
        return projection.hasOpenPositions(provider, environment, account, market);
    }

    @Deprecated
    public boolean hasOpenPositions() {
        throw new IllegalStateException("Runtime target is required to check open positions");
    }
}
