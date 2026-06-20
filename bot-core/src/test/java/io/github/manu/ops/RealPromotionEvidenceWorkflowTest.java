package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RealPromotionEvidenceWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/validate-real-promotion-evidence.yml";

    @Test
    void workflow_is_manual_real_environment_gated_and_verifies_source_ci() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: real")
                .contains("contents: read")
                .contains("actions: read")
                .contains("commit_sha must be a full 40-character lowercase git SHA")
                .contains("Verify source CI passed")
                .contains("Security workflow has not passed for ${{ steps.request.outputs.sha }}");
    }

    @Test
    void workflow_downloads_required_evidence_artifacts_and_runs_validator() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("demo_burn_in_workflow_run_id")
                .contains("demo_release_workflow_run_id")
                .contains("real_release_workflow_run_id")
                .contains("evidence workflow run ids must be numeric")
                .contains("evidence file paths must be relative artifact paths without traversal")
                .contains("actions/download-artifact@v6")
                .contains("run-id: ${{ inputs.demo_burn_in_workflow_run_id }}")
                .contains("run-id: ${{ inputs.demo_release_workflow_run_id }}")
                .contains("run-id: ${{ inputs.real_release_workflow_run_id }}")
                .contains("ops/evidence/validate-real-promotion-evidence.sh")
                .contains("--demo-burn-in-file")
                .contains("--demo-release-file")
                .contains("--real-release-file")
                .contains("--output-file");
    }

    @Test
    void workflow_preserves_explicit_btc_only_override_and_uploads_report_even_on_failure() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("real_scope_btc_only")
                .contains("Allow BTC-only demo evidence only when real is intentionally BTC-only")
                .contains("args+=(--real-scope-btc-only)")
                .contains("if: always()")
                .contains("actions/upload-artifact@v5")
                .contains("trading-bot-real-promotion-validation-${{ steps.request.outputs.sha }}")
                .contains("real-promotion-validation.md")
                .contains("if-no-files-found: error");
    }

    @Test
    void workflow_does_not_inline_secret_values() throws IOException {
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
