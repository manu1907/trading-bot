package io.github.manu.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class PauseGovernanceAuditMetrics {

    static final String STORE_FAILURES = "trading.pause_governance.audit_store.failures";

    private final MeterRegistry meterRegistry;

    public PauseGovernanceAuditMetrics() {
        this(Metrics.globalRegistry);
    }

    @Autowired
    public PauseGovernanceAuditMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void auditStoreFailure(String operation, String store) {
        Counter.builder(STORE_FAILURES)
                .description("Pause governance audit store persistence or query failures")
                .tag("operation", tagValue(operation, "unknown"))
                .tag("store", tagValue(store, "unknown"))
                .register(meterRegistry)
                .increment();
    }

    private String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
