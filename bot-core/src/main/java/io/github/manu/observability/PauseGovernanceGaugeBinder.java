package io.github.manu.observability;

import io.github.manu.projection.TradingStateProjection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class PauseGovernanceGaugeBinder {

    static final String ACTIVE_STATES = "trading.pause_governance.active.states";

    private final MeterRegistry meterRegistry;
    private final TradingStateProjection projection;
    private final Clock clock;
    private final AtomicBoolean bound = new AtomicBoolean();

    @Autowired
    public PauseGovernanceGaugeBinder(MeterRegistry meterRegistry, TradingStateProjection projection) {
        this(meterRegistry, projection, Clock.systemUTC());
    }

    PauseGovernanceGaugeBinder(MeterRegistry meterRegistry, TradingStateProjection projection, Clock clock) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostConstruct
    void bind() {
        if (!bound.compareAndSet(false, true)) {
            return;
        }
        bindActiveGauge("ACCOUNT");
        bindActiveGauge("SYMBOL");
    }

    private void bindActiveGauge(String scope) {
        Gauge.builder(ACTIVE_STATES, projection, ignored -> activePauseCount(scope))
                .description("Effective active pause governance states by pause scope")
                .tag("scope", scope)
                .register(meterRegistry);
    }

    private double activePauseCount(String scope) {
        Instant now = Instant.now(clock);
        return projection.snapshot()
                .pauseGovernance()
                .stream()
                .filter(state -> scope.equals(state.pauseScope()))
                .filter(state -> state.effectiveActive(now))
                .count();
    }
}
