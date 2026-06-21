package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JournalArchiveOpsTest {

    private static final String SCRIPT = "ops/database/archive-journal-segments.sh";
    private static final String WORKFLOW = ".github/workflows/archive-google-cloud-journal.yml";

    @TempDir
    Path tempDir;

    @Test
    void journal_archive_script_validates_directory_writes_manifest_and_uses_restore_path() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path journal = tempDir.resolve("journal");
        Files.createDirectories(journal.resolve("cycle-1"));
        Files.writeString(journal.resolve("cycle-1/metadata.cq4t"), "journal metadata\n", StandardCharsets.UTF_8);
        Files.writeString(journal.resolve("cycle-1/data.cq4"), "journal segment\n", StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "--environment",
                "demo",
                "--provider",
                "binance",
                "--account",
                "main",
                "--market",
                "usdm_futures",
                "--archive-id",
                "archive-001",
                "--journal-dir",
                journal.toString(),
                "--bucket",
                "journal-bucket",
                "--validate-only"
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(exited).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains(
                "gs://journal-bucket/demo/binance/main/usdm_futures/journal-segments/v1/archive-001/"
        );
        assertThat(Files.readString(journal.resolve("journal-archive-manifest.tsv")))
                .contains("sha256\trelative_path")
                .contains("cycle-1/data.cq4")
                .contains("cycle-1/metadata.cq4t");
    }

    @Test
    void journal_archive_workflow_is_manual_environment_gated_and_uses_dedicated_oidc_identity() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("actions: read")
                .contains("service_account: ${{ secrets.GCP_JOURNAL_ARCHIVE_SERVICE_ACCOUNT }}")
                .contains("Real journal archive requires confirm_real_archive=ARCHIVE_REAL_JOURNAL");
    }

    @Test
    void journal_archive_workflow_downloads_artifact_archives_to_configured_bucket_and_uploads_manifest()
            throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("source_workflow_run_id must be numeric")
                .contains("archive_id contains unsupported characters")
                .contains("journal_path must be a relative artifact path without traversal")
                .contains("actions/download-artifact@v6")
                .contains("run-id: ${{ inputs.source_workflow_run_id }}")
                .contains("name: ${{ inputs.artifact_name }}")
                .contains("ops/database/archive-journal-segments.sh")
                .contains("--bucket \"${{ vars.GCP_JOURNAL_ARCHIVE_BUCKET }}\"")
                .contains("--project \"${{ vars.GCP_PROJECT_ID }}\"")
                .contains("trading-bot-journal-archive-manifest-${{ inputs.environment }}-${{ inputs.archive_id }}");
    }

    @Test
    void journal_archive_script_and_workflow_do_not_inline_secret_values() throws IOException {
        for (String path : List.of(SCRIPT, WORKFLOW, "ops/database/README.md")) {
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
