package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudRunSmokeWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/smoke-google-cloud-cloud-run.yml";

    @Test
    void smoke_workflow_is_manual_environment_gated_and_uses_oidc() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("contents: read")
                .contains("actions: read")
                .contains("workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}")
                .contains("service_account: ${{ secrets.GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT }}")
                .contains("google-github-actions/auth@v3")
                .contains("google-github-actions/setup-gcloud@v3");
    }

    @Test
    void smoke_workflow_verifies_ci_revision_image_and_private_readiness() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("commit_sha must be a full 40-character lowercase git SHA")
                .contains("Verify source CI passed")
                .contains("Security workflow has not passed for ${{ steps.target.outputs.sha }}")
                .contains("gcloud run services describe \"$service\"")
                .contains("status.latestReadyRevisionName")
                .contains("gcloud run revisions describe \"$revision\"")
                .contains("metadata.labels.commit-sha")
                .contains("spec.containers[0].image")
                .contains("Latest ready revision $revision is labeled $deployed_sha")
                .contains("expected tag ${{ steps.target.outputs.sha }}")
                .contains("gcloud auth print-identity-token --audiences=\"$url\"")
                .contains("${url}/actuator/health/readiness")
                .contains("\"status\"[[:space:]]*:[[:space:]]*\"UP\"")
                .contains("trading-bot-google-cloud-run-smoke-${{ inputs.environment }}-${{ steps.target.outputs.sha }}");
    }

    @Test
    void smoke_workflow_maps_demo_and_real_cloud_run_targets() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("trading-bot-demo-main-usdm-futures")
                .contains("ops/google-cloud/demo-usdm-futures-deployment.yml")
                .contains("trading-bot-real-main-usdm-futures")
                .contains("ops/google-cloud/real-usdm-futures-deployment.yml")
                .contains("${{ vars.GCP_ARTIFACT_REGISTRY_LOCATION }}-docker.pkg.dev/${{ vars.GCP_PROJECT_ID }}/${{ vars.GCP_ARTIFACT_REGISTRY_REPOSITORY }}/trading-bot:${requested_sha}")
                .contains("--project \"${{ vars.GCP_PROJECT_ID }}\"")
                .contains("--region \"${{ vars.GCP_REGION }}\"");
    }

    @Test
    void smoke_workflow_does_not_inline_secret_values() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .doesNotContain("-----BEGIN")
                .doesNotContain("jdbc:postgresql://")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("xoxb-")
                .doesNotContain("ghp_")
                .doesNotContain("AIza");
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
