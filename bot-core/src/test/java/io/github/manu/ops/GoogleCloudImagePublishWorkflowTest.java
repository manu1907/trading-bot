package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudImagePublishWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/publish-google-cloud-image.yml";

    @Test
    void publish_workflow_is_manual_environment_gated_and_uses_oidc() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("contents: read")
                .contains("workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}")
                .contains("service_account: ${{ secrets.GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT }}")
                .contains("google-github-actions/auth@v3")
                .contains("google-github-actions/setup-gcloud@v3");
    }

    @Test
    void publish_workflow_builds_and_pushes_traceable_artifact_registry_image() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("commit_sha must be a full 40-character lowercase git SHA")
                .contains("actions/checkout@v6")
                .contains("ref: ${{ steps.target.outputs.sha }}")
                .contains("Verify source CI passed")
                .contains("Security workflow has not passed for ${{ steps.target.outputs.sha }}")
                .contains("actions/runs?head_sha=${{ steps.target.outputs.sha }}&event=push&per_page=20")
                .contains("./gradlew --no-daemon :bot-app:bootJar")
                .contains("gcloud auth configure-docker ${{ vars.GCP_ARTIFACT_REGISTRY_LOCATION }}-docker.pkg.dev --quiet")
                .contains("docker/build-push-action@v6")
                .contains("push: true")
                .contains("IMAGE_REVISION=${{ steps.target.outputs.sha }}")
                .contains("org.opencontainers.image.revision=${{ steps.target.outputs.sha }}")
                .contains("io.github.manu.trading-bot.environment=${{ inputs.environment }}")
                .contains("metadata-file: build/container/google-cloud-buildx-metadata.json")
                .contains("${{ vars.GCP_ARTIFACT_REGISTRY_LOCATION }}-docker.pkg.dev/${{ vars.GCP_PROJECT_ID }}/${{ vars.GCP_ARTIFACT_REGISTRY_REPOSITORY }}/trading-bot")
                .contains("trading-bot-google-cloud-image-${{ inputs.environment }}-${{ steps.target.outputs.sha }}");
    }

    @Test
    void publish_workflow_does_not_inline_secret_values() throws IOException {
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
