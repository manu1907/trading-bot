package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudBootstrapScriptTest {

    private static final String SCRIPT = "ops/google-cloud/bootstrap-deployment-prereqs.sh";

    @Test
    void bootstrap_script_is_strict_and_documents_required_inputs() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("BINANCE_DEMO_API_KEY")
                .contains("BINANCE_DEMO_API_SECRET")
                .contains("GCP_PROJECT_ID")
                .contains("GCP_REGION")
                .contains("GCP_ARTIFACT_REGISTRY_LOCATION")
                .contains("GCP_ARTIFACT_REGISTRY_REPOSITORY")
                .contains("GCP_CLOUD_SQL_INSTANCE")
                .contains("GCP_BUDGET_ALERTS_ENABLED=true|false")
                .contains("GCP_BUDGET_AMOUNT")
                .contains("GITHUB_OWNER")
                .contains("GITHUB_REPO")
                .contains("GITHUB_CONFIGURE_ENVIRONMENTS=true|false")
                .contains("Default: current gcloud project")
                .contains("Default: europe-west1")
                .contains("Default: inferred from git remote")
                .contains("source api.env")
                .contains("Operator tokens and Cloud SQL JDBC values are generated automatically");
    }

    @Test
    void bootstrap_script_defaults_non_secret_inputs_and_generates_operator_tokens() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("infer_defaults()")
                .contains("gcloud config get-value project")
                .contains("git remote get-url origin")
                .contains("GCP_REGION=\"${GCP_REGION:-europe-west1}\"")
                .contains("GCP_ARTIFACT_REGISTRY_REPOSITORY=\"${GCP_ARTIFACT_REGISTRY_REPOSITORY:-trading-bot}\"")
                .contains("GCP_CLOUD_SQL_INSTANCE=\"${GCP_CLOUD_SQL_INSTANCE:-trading-bot-postgres}\"")
                .contains("GCP_CLOUD_SQL_DATABASE_VERSION=\"${GCP_CLOUD_SQL_DATABASE_VERSION:-POSTGRES_16}\"")
                .contains("GCP_BUDGET_ALERTS_ENABLED=\"${GCP_BUDGET_ALERTS_ENABLED:-false}\"")
                .contains("GCP_BUDGET_DISPLAY_NAME=\"${GCP_BUDGET_DISPLAY_NAME:-trading-bot-${GCP_PROJECT_ID}-monthly}\"")
                .contains("GCP_BUDGET_AMOUNT=\"${GCP_BUDGET_AMOUNT:-250USD}\"")
                .contains("GCP_BUDGET_CALENDAR_PERIOD=\"${GCP_BUDGET_CALENDAR_PERIOD:-month}\"")
                .contains("GCP_BUDGET_FILTER_PROJECTS=\"${GCP_BUDGET_FILTER_PROJECTS:-projects/${GCP_PROJECT_ID}}\"")
                .contains("DEMO_CLOUD_SQL_DATABASE=\"${DEMO_CLOUD_SQL_DATABASE:-trading_bot_demo}\"")
                .contains("DEMO_AUDIT_CLOUD_SQL_USERNAME=\"${DEMO_AUDIT_CLOUD_SQL_USERNAME:-trading_bot_demo_audit}\"")
                .contains("DEMO_PROJECTION_CLOUD_SQL_USERNAME=\"${DEMO_PROJECTION_CLOUD_SQL_USERNAME:-trading_bot_demo_projection}\"")
                .contains("REAL_CLOUD_SQL_DATABASE=\"${REAL_CLOUD_SQL_DATABASE:-trading_bot_real}\"")
                .contains("REAL_AUDIT_CLOUD_SQL_USERNAME=\"${REAL_AUDIT_CLOUD_SQL_USERNAME:-trading_bot_real_audit}\"")
                .contains("REAL_PROJECTION_CLOUD_SQL_USERNAME=\"${REAL_PROJECTION_CLOUD_SQL_USERNAME:-trading_bot_real_projection}\"")
                .contains("GITHUB_OWNER=\"${GITHUB_OWNER:-manu1907}\"")
                .contains("GITHUB_REPO=\"${GITHUB_REPO:-trading-bot}\"")
                .contains("GITHUB_CONFIGURE_ENVIRONMENTS=\"${GITHUB_CONFIGURE_ENVIRONMENTS:-false}\"")
                .contains("openssl rand -base64 48")
                .contains("ensure_secret_with_generated_fallback trading-bot-demo-operator-token DEMO_OPERATOR_TOKEN")
                .contains("ensure_secret_with_generated_fallback trading-bot-real-operator-token REAL_OPERATOR_TOKEN");
    }

    @Test
    void bootstrap_script_enables_required_google_cloud_services() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("ensure_api iam.googleapis.com")
                .contains("ensure_api iamcredentials.googleapis.com")
                .contains("ensure_api cloudresourcemanager.googleapis.com")
                .contains("ensure_api serviceusage.googleapis.com")
                .contains("ensure_api artifactregistry.googleapis.com")
                .contains("ensure_api run.googleapis.com")
                .contains("ensure_api secretmanager.googleapis.com")
                .contains("ensure_api storage.googleapis.com")
                .contains("ensure_api sqladmin.googleapis.com");
    }

    @Test
    void bootstrap_script_can_optionally_create_project_scoped_budget_alerts() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("GCP_BUDGET_ALERTS_ENABLED=true|false")
                .contains("Default: percent=0.50;percent=0.80;percent=1.00;percent=1.00,basis=forecasted-spend")
                .contains("budget_exists()")
                .contains("gcloud billing budgets list")
                .contains("--format='value(displayName)'")
                .contains("ensure_budget_alert()")
                .contains("require_env GCP_BILLING_ACCOUNT")
                .contains("gcloud billing budgets create")
                .contains("--billing-account=$GCP_BILLING_ACCOUNT")
                .contains("--display-name=$GCP_BUDGET_DISPLAY_NAME")
                .contains("--budget-amount=$GCP_BUDGET_AMOUNT")
                .contains("--calendar-period=$GCP_BUDGET_CALENDAR_PERIOD")
                .contains("--filter-projects=$GCP_BUDGET_FILTER_PROJECTS")
                .contains("--threshold-rule=$threshold_rule")
                .contains("--notifications-rule-pubsub-topic=$GCP_BUDGET_PUBSUB_TOPIC")
                .contains("--notifications-rule-monitoring-notification-channels=$GCP_BUDGET_MONITORING_NOTIFICATION_CHANNELS")
                .contains("--disable-default-iam-recipients")
                .contains("ensure_api billingbudgets.googleapis.com")
                .contains("if [[ \"$GCP_BUDGET_ALERTS_ENABLED\" == \"true\" ]]")
                .contains("BUDGET_ALERTS_STATE=\"existing\"")
                .contains("BUDGET_ALERTS_STATE=\"created\"");
    }

    @Test
    void bootstrap_script_creates_expected_service_accounts_and_iam_bindings() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-artifact-publisher")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-deployer")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-runtime")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-smoke")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-rollback")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-evidence-archiver")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-sql-migration")
                .contains("roles/artifactregistry.writer")
                .contains("roles/artifactregistry.reader")
                .contains("roles/run.admin")
                .contains("roles/run.viewer")
                .contains("roles/run.invoker")
                .contains("roles/secretmanager.secretAccessor")
                .contains("roles/cloudsql.client")
                .contains("roles/storage.objectAdmin")
                .contains("roles/iam.serviceAccountUser")
                .contains("roles/iam.workloadIdentityUser");
    }

    @Test
    void bootstrap_script_configures_github_workload_identity_provider() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("gcloud iam workload-identity-pools create")
                .contains("gcloud iam workload-identity-pools providers create-oidc")
                .contains("https://token.actions.githubusercontent.com")
                .contains("google.subject=assertion.sub")
                .contains("attribute.repository=assertion.repository")
                .contains("assertion.repository == '${GITHUB_OWNER}/${GITHUB_REPO}'")
                .contains("GCP_WORKLOAD_IDENTITY_PROVIDER")
                .contains("GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_RUN_DEPLOY_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT")
                .contains("GCP_EVIDENCE_ARCHIVE_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_SQL_MIGRATION_SERVICE_ACCOUNT");
    }

    @Test
    void bootstrap_script_creates_deployment_secret_containers() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("trading-bot-demo-binance-api-key")
                .contains("trading-bot-demo-binance-api-secret")
                .contains("ensure_secret_with_optional_version trading-bot-demo-binance-api-key BINANCE_DEMO_API_KEY")
                .contains("ensure_secret_with_optional_version trading-bot-demo-binance-api-secret BINANCE_DEMO_API_SECRET")
                .contains("trading-bot-demo-operator-token")
                .contains("trading-bot-demo-audit-jdbc-url")
                .contains("trading-bot-demo-projection-jdbc-password")
                .contains("trading-bot-real-binance-api-key")
                .contains("trading-bot-real-binance-api-secret")
                .contains("trading-bot-real-operator-token")
                .contains("trading-bot-real-audit-jdbc-url")
                .contains("trading-bot-real-projection-jdbc-password")
                .contains("trading-bot-demo-alert-operator-slack-webhook")
                .contains("trading-bot-demo-alert-fallback-slack-channel")
                .contains("trading-bot-demo-alert-smtp-smarthost")
                .contains("trading-bot-demo-alert-smtp-auth-password")
                .contains("trading-bot-demo-alert-operator-email-to")
                .contains("trading-bot-demo-alert-platform-email-to")
                .contains("trading-bot-demo-alert-fallback-email-to")
                .contains("DEMO_ALERT_EMAIL_TO")
                .contains("trading-bot-real-alert-operator-slack-webhook")
                .contains("trading-bot-real-alert-fallback-slack-channel")
                .contains("trading-bot-real-alert-smtp-smarthost")
                .contains("trading-bot-real-alert-smtp-auth-password")
                .contains("trading-bot-real-alert-operator-email-to")
                .contains("trading-bot-real-alert-platform-email-to")
                .contains("trading-bot-real-alert-fallback-email-to")
                .contains("REAL_ALERT_EMAIL_TO");
    }

    @Test
    void bootstrap_script_provisions_cloud_sql_and_generates_jdbc_secrets() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("ensure_cloud_sql_instance()")
                .contains("gcloud sql instances create")
                .contains("--database-version=$GCP_CLOUD_SQL_DATABASE_VERSION")
                .contains("--deletion-protection")
                .contains("ensure_cloud_sql_database \"$DEMO_CLOUD_SQL_DATABASE\"")
                .contains("ensure_cloud_sql_database \"$REAL_CLOUD_SQL_DATABASE\"")
                .contains("ensure_cloud_sql_user_password \"$DEMO_AUDIT_CLOUD_SQL_USERNAME\"")
                .contains("ensure_cloud_sql_user_password \"$DEMO_PROJECTION_CLOUD_SQL_USERNAME\"")
                .contains("ensure_cloud_sql_user_password \"$REAL_AUDIT_CLOUD_SQL_USERNAME\"")
                .contains("ensure_cloud_sql_user_password \"$REAL_PROJECTION_CLOUD_SQL_USERNAME\"")
                .contains("cloud_sql_postgres_jdbc_url()")
                .contains("com.google.cloud.sql.postgres.SocketFactory")
                .contains("ensure_secret_with_literal_fallback trading-bot-demo-audit-jdbc-url")
                .contains("trading-bot-demo-projection-jdbc-password DEMO_PROJECTION_JDBC_PASSWORD")
                .contains("trading-bot-real-projection-jdbc-password REAL_PROJECTION_JDBC_PASSWORD")
                .contains("Cloud SQL/PostgreSQL created or verified");
    }

    @Test
    void bootstrap_script_can_optionally_configure_github_environments() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("configure_github_environments()")
                .contains("require_command gh")
                .contains("configure_github_environment demo")
                .contains("configure_github_environment real")
                .contains("gh api")
                .contains("repos/$(github_repo_slug)/environments/${environment}")
                .contains("gh secret set \"$secret_name\"")
                .contains("gh variable set \"$variable_name\"")
                .contains("GCP_WORKLOAD_IDENTITY_PROVIDER")
                .contains("GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT")
                .contains("GCP_EVIDENCE_ARCHIVE_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_SQL_MIGRATION_SERVICE_ACCOUNT")
                .contains("GCP_CLOUD_SQL_INSTANCE")
                .contains("GCP_EVIDENCE_ARCHIVE_BUCKET")
                .contains("if [[ \"$GITHUB_CONFIGURE_ENVIRONMENTS\" == \"true\" ]]")
                .contains("GitHub environments configured:");
    }

    @Test
    void bootstrap_script_creates_versioned_journal_and_evidence_archive_buckets() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("TRADING_BOT_JOURNAL_ARCHIVE_BUCKET")
                .contains("TRADING_BOT_EVIDENCE_ARCHIVE_BUCKET")
                .contains("TRADING_BOT_EVIDENCE_ARCHIVE_BUCKET=\"${TRADING_BOT_EVIDENCE_ARCHIVE_BUCKET:-${GCP_PROJECT_ID}-trading-bot-evidence-archive}\"")
                .contains("ensure_bucket()")
                .contains("--uniform-bucket-level-access")
                .contains("--public-access-prevention")
                .contains("gcloud storage buckets update \"gs://${bucket}\" --versioning")
                .contains("ensure_bucket \"$TRADING_BOT_JOURNAL_ARCHIVE_BUCKET\"")
                .contains("ensure_bucket \"$TRADING_BOT_EVIDENCE_ARCHIVE_BUCKET\"")
                .contains("Evidence archive bucket created or verified");
    }

    @Test
    void bootstrap_script_does_not_inline_secret_values() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .doesNotContain("-----BEGIN")
                .doesNotContain("jdbc:postgresql://")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("xoxb-")
                .doesNotContain("AIza")
                .doesNotContain("ghp_");
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
