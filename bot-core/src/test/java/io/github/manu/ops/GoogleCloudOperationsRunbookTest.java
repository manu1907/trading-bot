package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudOperationsRunbookTest {

    private static final String RUNBOOK = "ops/runbooks/google-cloud-operations.md";

    @Test
    void runbook_covers_google_cloud_deployment_operations_end_to_end() throws IOException {
        String runbook = Files.readString(resolve(RUNBOOK));

        assertThat(runbook)
                .contains("# Google Cloud Operations Runbook")
                .contains("same codebase")
                .contains("GITHUB_CONFIGURE_ENVIRONMENTS=true")
                .contains("publish-google-cloud-image.yml")
                .contains("deploy-google-cloud-cloud-run.yml")
                .contains("smoke-google-cloud-cloud-run.yml")
                .contains("rollback-google-cloud-cloud-run.yml")
                .contains("render-google-cloud-alertmanager.sh")
                .contains("Cloud SQL")
                .contains("Secret Manager")
                .contains("Artifact Registry")
                .contains("Workload Identity Federation")
                .contains("Security")
                .contains("full 40-character SHA");
    }

    @Test
    void runbook_covers_safety_incidents_and_real_promotion_gates() throws IOException {
        String runbook = Files.readString(resolve(RUNBOOK));

        assertThat(runbook)
                .contains("Emergency Stop And Controlled Drain")
                .contains("EMERGENCY_STOP")
                .contains("DRAINING")
                .contains("Do not start a second bot instance")
                .contains("Incident Handling")
                .contains("Unexpected external order or position")
                .contains("Unknown order result")
                .contains("Degraded reconciliation")
                .contains("Real Promotion Rules")
                .contains("Demo burn-in proves the intended real strategy/remediation/symbol-universe")
                .contains("behavior, not a reduced toy behavior")
                .contains("Real trading must not be enabled just because the same workflow can deploy real")
                .contains("keep real execution disabled");
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
