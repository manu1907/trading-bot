package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudEvidenceArchiveWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/archive-google-cloud-evidence.yml";
    private static final String SCRIPT = "ops/evidence/archive-google-cloud-evidence.sh";

    @Test
    void archive_workflow_is_manual_environment_gated_and_uses_oidc() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("actions: read")
                .contains("google-github-actions/auth@v3")
                .contains("workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}")
                .contains("service_account: ${{ secrets.GCP_EVIDENCE_ARCHIVE_SERVICE_ACCOUNT }}");
    }

    @Test
    void archive_workflow_downloads_requested_artifact_and_archives_to_configured_bucket() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("source_workflow_run_id must be numeric")
                .contains("evidence_id contains unsupported characters")
                .contains("actions/download-artifact@v6")
                .contains("run-id: ${{ inputs.source_workflow_run_id }}")
                .contains("name: ${{ inputs.artifact_name }}")
                .contains("ops/evidence/archive-google-cloud-evidence.sh")
                .contains("--bucket \"${{ vars.GCP_EVIDENCE_ARCHIVE_BUCKET }}\"")
                .contains("--project \"${{ vars.GCP_PROJECT_ID }}\"")
                .contains("trading-bot-evidence-archive-manifest-${{ inputs.environment }}-${{ inputs.evidence_type }}-${{ inputs.evidence_id }}");
    }

    @Test
    void archive_script_scans_for_secret_like_content_and_writes_manifest_before_upload() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("forbidden_secret_scan")
                .contains("refusing to archive possible secret-bearing evidence bundle")
                .contains("archive-manifest.tsv")
                .contains("sha256\trelative_path")
                .contains("gs://BUCKET/ENVIRONMENT/EVIDENCE_TYPE/EVIDENCE_ID/")
                .contains("gcloud \"${args[@]}\"")
                .contains("--dry-run")
                .contains("release|burn-in|smoke|rollback|incident|drill|promotion");
    }

    @Test
    void archive_workflow_and_script_do_not_inline_secret_values() throws IOException {
        for (String path : new String[] { WORKFLOW, SCRIPT }) {
            String content = Files.readString(resolve(path));

            assertThat(content)
                    .doesNotContain("-----BEGIN")
                    .doesNotContain("jdbc:postgresql://")
                    .doesNotContain("hooks.slack.com")
                    .doesNotContain("xoxb-")
                    .doesNotContain("ghp_")
                    .doesNotContain("AIza");
        }
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
