package io.github.manu.audit;

import java.util.List;

public interface PauseGovernanceAuditStore {

    void record(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event);

    List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> recent(
            String provider,
            String environment,
            String account,
            String market,
            int limit
    );

    default String storeName() {
        return getClass().getSimpleName();
    }
}
