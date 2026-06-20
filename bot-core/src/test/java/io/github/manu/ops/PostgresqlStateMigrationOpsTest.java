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

class PostgresqlStateMigrationOpsTest {

    private static final String SCRIPT = "ops/database/migrate-postgresql-state.sh";
    private static final String WORKFLOW = ".github/workflows/migrate-google-cloud-postgresql-state.yml";
    private static final String PROJECTION_SCHEMA = "bot-core/src/main/resources/db/projection/postgresql-schema.sql";
    private static final String AUDIT_SCHEMA = "bot-core/src/main/resources/db/audit/pause-governance-postgresql-schema.sql";

    @TempDir
    Path tempDir;

    @Test
    void migration_script_validates_checked_in_schemas_without_database_connection() throws Exception {
        Path script = resolve(SCRIPT).toAbsolutePath();
        Path repo = Objects.requireNonNull(
                Objects.requireNonNull(Objects.requireNonNull(script.getParent()).getParent()).getParent());
        Path output = tempDir.resolve("migration");

        Process process = new ProcessBuilder(
                "bash",
                script.toString(),
                "--environment",
                "demo",
                "--schema",
                "all",
                "--output-dir",
                output.toString(),
                "--plan-only"
        )
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(exited).as(text).isTrue();
        assertThat(process.exitValue()).as(text).isZero();
        assertThat(text)
                .contains("PostgreSQL State Migration")
                .contains("Environment: demo")
                .contains("Schema: all")
                .contains("Status: PLAN_VALIDATED")
                .contains(PROJECTION_SCHEMA)
                .contains(AUDIT_SCHEMA);
        assertThat(Files.readString(output.resolve("postgresql-state-migration-demo-all.md")))
                .contains("Plan only: true")
                .contains("Status: PLAN_VALIDATED");
    }

    @Test
    void migration_script_is_strict_idempotent_and_supports_cloud_sql_jdbc_bindings() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("--environment demo|real")
                .contains("--schema projection|audit|all")
                .contains("--jdbc-url")
                .contains("--password-env")
                .contains("--cloud-sql-proxy")
                .contains("cloudSqlInstance=")
                .contains("create table if not exists|create index if not exists|alter table .+ add column if not exists")
                .contains("refusing potentially destructive migration SQL")
                .contains("PGPASSWORD")
                .contains("ON_ERROR_STOP=1");
    }

    @Test
    void migration_workflow_is_manual_environment_gated_and_uses_dedicated_oidc_identity() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("google-github-actions/auth@v3")
                .contains("service_account: ${{ secrets.GCP_CLOUD_SQL_MIGRATION_SERVICE_ACCOUNT }}")
                .contains("Security workflow has not passed for ${{ steps.target.outputs.sha }}");
    }

    @Test
    void migration_workflow_maps_demo_real_secrets_runs_projection_and_audit_and_uploads_evidence() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("trading-bot-demo-projection-jdbc-url")
                .contains("trading-bot-demo-audit-jdbc-url")
                .contains("trading-bot-real-projection-jdbc-url")
                .contains("trading-bot-real-audit-jdbc-url")
                .contains("RUN_REAL_DATABASE_MIGRATION")
                .contains("gcloud secrets versions access latest")
                .contains("cloud-sql-proxy.linux.amd64")
                .contains("ops/database/migrate-postgresql-state.sh")
                .contains("--schema projection")
                .contains("--schema audit")
                .contains("actions/upload-artifact@v5")
                .contains("trading-bot-postgresql-state-migration-${{ inputs.environment }}-${{ inputs.schema }}-${{ steps.target.outputs.sha }}");
    }

    @Test
    void checked_in_state_schemas_are_idempotent_and_match_runtime_table_prefixes() throws IOException {
        String projection = Files.readString(resolve(PROJECTION_SCHEMA));
        String audit = Files.readString(resolve(AUDIT_SCHEMA));

        assertThat(projection)
                .contains("create table if not exists trading_projection_balances")
                .contains("create table if not exists trading_projection_orders")
                .contains("create table if not exists trading_projection_pause_governance")
                .contains("create table if not exists trading_projection_applied_event_ids")
                .contains("alter table trading_projection_orders add column if not exists side")
                .doesNotContain("drop table")
                .doesNotContain("truncate")
                .doesNotContain("delete from");
        assertThat(audit)
                .contains("create table if not exists trading_audit_pause_governance_events")
                .contains("create index if not exists trading_audit_pause_governance_scope_idx")
                .contains("create index if not exists trading_audit_pause_governance_pause_target_idx")
                .doesNotContain("drop table")
                .doesNotContain("truncate")
                .doesNotContain("delete from");
    }

    @Test
    void migration_ops_files_do_not_inline_secret_values() throws IOException {
        for (String path : List.of(SCRIPT, WORKFLOW, "ops/database/README.md", AUDIT_SCHEMA)) {
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
