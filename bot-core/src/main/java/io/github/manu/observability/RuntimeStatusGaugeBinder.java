package io.github.manu.observability;

import io.github.manu.runtime.RuntimeStatusService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

@Component
public final class RuntimeStatusGaugeBinder {

    static final String READINESS_STATES = "trading.runtime.readiness.states";
    static final String BLOCKER_STATES = "trading.runtime.blocker.states";
    static final String PROJECTION_STATES = "trading.runtime.projection.states";
    static final String MARKET_DATA_STATES = "trading.runtime.market_data.states";
    static final String RECONCILIATION_STATES = "trading.runtime.reconciliation.states";

    private static final List<String> READINESS_VALUES = List.of("READY", "ATTENTION", "BLOCKED");
    private static final List<String> RECONCILIATION_VALUES = List.of("NO_OBSERVATIONS", "CONFIDENT", "DEGRADED");
    private static final List<String> BLOCKERS = List.of(
            "reconciliation:no_observations",
            "reconciliation:degraded",
            "orders:unknown_status",
            "orders:unresolved_command",
            "interventions:external_orders",
            "interventions:external_positions",
            "governance:active_pause",
            "market_data:no_symbols",
            "market_data:fresh_symbols_below_minimum"
    );

    private final MeterRegistry meterRegistry;
    private final RuntimeStatusService runtimeStatusService;
    private final AtomicBoolean bound = new AtomicBoolean();

    @Autowired
    public RuntimeStatusGaugeBinder(MeterRegistry meterRegistry, RuntimeStatusService runtimeStatusService) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.runtimeStatusService = Objects.requireNonNull(runtimeStatusService, "runtimeStatusService");
    }

    @PostConstruct
    void bind() {
        if (!bound.compareAndSet(false, true)) {
            return;
        }
        READINESS_VALUES.forEach(this::bindReadinessGauge);
        RECONCILIATION_VALUES.forEach(this::bindReconciliationGauge);
        BLOCKERS.forEach(this::bindBlockerGauge);
        bindProjectionGauge("open_orders", status -> status.projection().openOrders());
        bindProjectionGauge("open_positions", status -> status.projection().openPositions());
        bindProjectionGauge("external_order_interventions", status -> status.projection().externalOrderInterventions());
        bindProjectionGauge("external_position_interventions", status -> status.projection().externalPositionInterventions());
        bindProjectionGauge("unknown_order_statuses", status -> status.projection().unknownOrderStatuses());
        bindProjectionGauge("unresolved_order_commands", status -> status.projection().unresolvedOrderCommands());
        bindProjectionGauge("active_pauses", status -> status.projection().activePauses());
        bindMarketDataGauge("total_symbols", status -> status.projection().marketDataSymbols());
        bindMarketDataGauge("fresh_symbols", status -> status.projection().freshMarketDataSymbols());
        bindMarketDataGauge("stale_symbols", status -> status.projection().staleMarketDataSymbols());
    }

    private void bindReadinessGauge(String readiness) {
        Gauge.builder(READINESS_STATES, this, ignored -> matchingReadiness(readiness))
                .description("Active runtime readiness state. The matching readiness label is 1 and others are 0.")
                .tag("readiness", readiness)
                .register(meterRegistry);
    }

    private void bindReconciliationGauge(String state) {
        Gauge.builder(RECONCILIATION_STATES, this, ignored -> matchingReconciliation(state))
                .description("Active runtime reconciliation confidence state. The matching status label is 1 and others are 0.")
                .tag("status", state)
                .register(meterRegistry);
    }

    private void bindBlockerGauge(String blocker) {
        Gauge.builder(BLOCKER_STATES, this, ignored -> activeBlocker(blocker))
                .description("Active runtime readiness blockers by bounded blocker kind")
                .tag("blocker", blocker)
                .register(meterRegistry);
    }

    private void bindProjectionGauge(String kind, ToDoubleFunction<RuntimeStatusService.RuntimeStatus> value) {
        Gauge.builder(PROJECTION_STATES, this, ignored -> statusValue(value))
                .description("Active runtime projection state counts by bounded kind")
                .tag("kind", kind)
                .register(meterRegistry);
    }

    private void bindMarketDataGauge(String kind, ToDoubleFunction<RuntimeStatusService.RuntimeStatus> value) {
        Gauge.builder(MARKET_DATA_STATES, this, ignored -> statusValue(value))
                .description("Active runtime projected market-data symbol counts by bounded kind")
                .tag("kind", kind)
                .register(meterRegistry);
    }

    private double matchingReadiness(String readiness) {
        RuntimeStatusService.RuntimeStatus status = status();
        if (status == null) {
            return 0.0d;
        }
        return readiness.equals(status.readiness().name()) ? 1.0d : 0.0d;
    }

    private double matchingReconciliation(String reconciliationStatus) {
        RuntimeStatusService.RuntimeStatus status = status();
        if (status == null) {
            return 0.0d;
        }
        return reconciliationStatus.equals(status.reconciliation().status()) ? 1.0d : 0.0d;
    }

    private double activeBlocker(String blocker) {
        RuntimeStatusService.RuntimeStatus status = status();
        if (status == null) {
            return 0.0d;
        }
        return status.blockers().contains(blocker) ? 1.0d : 0.0d;
    }

    private double statusValue(ToDoubleFunction<RuntimeStatusService.RuntimeStatus> value) {
        RuntimeStatusService.RuntimeStatus status = status();
        if (status == null) {
            return 0.0d;
        }
        return value.applyAsDouble(status);
    }

    private RuntimeStatusService.RuntimeStatus status() {
        try {
            return runtimeStatusService.status(RuntimeStatusService.RuntimeStatusRequest.defaults());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
