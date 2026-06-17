package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentResponseRunbookTest {

    private static final String RUNBOOK = "ops/runbooks/incident-response.md";

    @Test
    void incident_runbook_covers_live_demo_and_real_without_forked_behavior() throws IOException {
        String runbook = Files.readString(resolve(RUNBOOK));

        assertThat(runbook)
                .startsWith("# Incident Response Runbook")
                .contains("It applies\nto both `demo` and `real`; the code path is the same")
                .contains("runtime configuration, credentials, endpoints, provider\navailability")
                .contains("provider/environment/account/market")
                .contains("Do not start a\nsecond bot instance")
                .contains("Real trading must remain disabled until demo has proven the same intended\nstrategy");
    }

    @Test
    void incident_runbook_covers_required_operational_scenarios() throws IOException {
        String runbook = Files.readString(resolve(RUNBOOK));

        assertThat(runbook)
                .contains("## Exchange Outage Or Binance Connectivity Failure")
                .contains("## Stale User-Data Stream")
                .contains("## Stale Market-Data Stream")
                .contains("## Reconciliation Degradation")
                .contains("## External Order Or Position Detected")
                .contains("## Unknown Order Result Or Ambiguous Modify Outcome")
                .contains("## Failed Deployment, Failed Smoke, Or Bad Runtime Config")
                .contains("## Persistence, Journal, Or Cloud SQL Failure")
                .contains("## Alerting Unavailable")
                .contains("## Credential Compromise Or Rotation")
                .contains("## Cost Or Budget Spike")
                .contains("## Real Environment Rules")
                .contains("## Evidence Bundle")
                .contains("## Incident Review");
    }

    @Test
    void incident_runbook_preserves_policy_gates_and_automated_external_intervention_handling() throws IOException {
        String runbook = Files.readString(resolve(RUNBOOK));

        assertThat(runbook)
                .contains("Move the strategy lifecycle to `DRAINING`")
                .contains("Move to `EMERGENCY_STOP`")
                .contains("OrderExecutionPipeline")
                .contains("OrderRiskGate")
                .contains("idempotency")
                .contains("reconciliation confidence")
                .contains("The bot must evaluate risk automatically")
                .contains("preserve,\n   adopt, cancel, amend, reduce, close, or no action")
                .contains("do not\n   require manual review as the only control path")
                .contains("Do not retry the same action immediately")
                .contains("executor_ambiguous_outcome_detected=true");
    }

    @Test
    void google_cloud_docs_link_to_scenario_specific_incident_runbook() throws IOException {
        assertThat(Files.readString(resolve("ops/google-cloud/README.md")))
                .contains("ops/runbooks/incident-response.md");
        assertThat(Files.readString(resolve("ops/runbooks/google-cloud-operations.md")))
                .contains("scenario-specific incident response runbook")
                .contains("exchange\noutages, stale streams, external interventions");
        assertThat(Files.readString(resolve("docs/current-state-and-scenarios.md")))
                .contains("A scenario-specific\n  incident response runbook now covers exchange outages");
        assertThat(Files.readString(resolve("docs/demo-usdm-futures-user-manual.md")))
                .contains("Scenario-specific incident response procedures live")
                .contains("ops/runbooks/incident-response.md");
        assertThat(Files.readString(resolve("docs/architecture.md")))
                .contains("scenario-specific incident response runbook defines response paths");
    }

    private Path resolve(String path) {
        Path cwd = Path.of(path);
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path parent = Path.of("..").resolve(path).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }
}
