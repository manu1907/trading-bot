package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudRunDeployWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/deploy-google-cloud-cloud-run.yml";

    @Test
    void deploy_workflow_is_manual_environment_gated_and_uses_oidc() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("id-token: write")
                .contains("workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}")
                .contains("service_account: ${{ secrets.GCP_CLOUD_RUN_DEPLOY_SERVICE_ACCOUNT }}")
                .contains("--service-account \"${{ secrets.GCP_CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT }}\"");
    }

    @Test
    void deploy_workflow_verifies_ci_and_published_image_before_deploy() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("commit_sha must be a full 40-character lowercase git SHA")
                .contains("Verify source CI passed")
                .contains("Security workflow has not passed for ${{ steps.target.outputs.sha }}")
                .contains("Verify published image exists")
                .contains("gcloud artifacts docker images describe \"${{ steps.target.outputs.image }}\"")
                .contains("gcloud run deploy \"${{ steps.target.outputs.service_name }}\"")
                .contains("--no-allow-unauthenticated")
                .contains("--execution-environment gen2")
                .contains("--add-cloudsql-instances \"${{ steps.target.outputs.cloud_sql_instance }}\"")
                .contains("--revision-suffix \"$revision_suffix\"")
                .contains("--labels \"app=trading-bot,environment=${{ inputs.environment }},commit-sha=${{ steps.target.outputs.sha }}\"")
                .contains("trading-bot-google-cloud-run-${{ inputs.environment }}-${{ steps.target.outputs.sha }}");
    }

    @Test
    void deploy_workflow_maps_demo_and_real_contract_runtime_surfaces() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("trading-bot-demo-main-usdm-futures")
                .contains("cloud_sql_instance=\"${{ vars.GCP_PROJECT_ID }}:${{ vars.GCP_REGION }}:${{ vars.GCP_CLOUD_SQL_INSTANCE }}\"")
                .contains("ops/google-cloud/demo-usdm-futures-deployment.yml")
                .contains("BINANCE_DEMO_API_KEY=trading-bot-demo-binance-api-key:latest")
                .contains("TRADING_PROJECTION_JDBC_PASSWORD=trading-bot-demo-projection-jdbc-password:latest")
                .contains("BOT_ENVIRONMENT=demo")
                .contains("trading-bot-real-main-usdm-futures")
                .contains("\"cloud_sql_instance\": \"${{ steps.target.outputs.cloud_sql_instance }}\"")
                .contains("ops/google-cloud/real-usdm-futures-deployment.yml")
                .contains("BINANCE_REAL_API_KEY=trading-bot-real-binance-api-key:latest")
                .contains("TRADING_PROJECTION_JDBC_PASSWORD=trading-bot-real-projection-jdbc-password:latest")
                .contains("BOT_ENVIRONMENT=real")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ENABLED=false")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_EXCHANGE_EXECUTION_ENABLED=false")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_REPORT_ONLY=true")
                .contains("TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ALLOW_REAL_ENVIRONMENT=false");
    }

    @Test
    void deploy_workflow_does_not_inline_secret_values() throws IOException {
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
