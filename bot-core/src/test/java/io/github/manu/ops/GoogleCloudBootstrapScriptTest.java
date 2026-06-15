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
                .contains("GCP_PROJECT_ID")
                .contains("GCP_REGION")
                .contains("GCP_ARTIFACT_REGISTRY_LOCATION")
                .contains("GCP_ARTIFACT_REGISTRY_REPOSITORY")
                .contains("GITHUB_OWNER")
                .contains("GITHUB_REPO")
                .contains("deployment will still")
                .contains("require a secret version before Cloud Run can bind :latest");
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
    void bootstrap_script_creates_expected_service_accounts_and_iam_bindings() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-artifact-publisher")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-deployer")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-runtime")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-smoke")
                .contains("${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-rollback")
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
                .contains("GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT");
    }

    @Test
    void bootstrap_script_creates_deployment_secret_containers() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .contains("trading-bot-demo-binance-api-key")
                .contains("trading-bot-demo-binance-api-secret")
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
                .contains("trading-bot-real-alert-operator-slack-webhook")
                .contains("trading-bot-real-alert-fallback-slack-channel");
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
