package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudMonitoringPolicyConfigTest {

    private static final String SCRIPT = "ops/google-cloud/provision-monitoring-alert-policies.sh";

    @Test
    void provisioning_script_renders_templates_and_creates_missing_policies_by_display_name() throws IOException {
        String script = Files.readString(resolve(SCRIPT));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("demo | real")
                .contains("--validate-only")
                .contains("ops/google-cloud/monitoring/alert-policies")
                .contains("render_policy_template()")
                .contains("python3 -m json.tool \"$output\"")
                .contains("policy_display_name()")
                .contains("gcloud monitoring policies list")
                .contains("--filter=\"displayName=\\\"${display_name}\\\"\"")
                .contains("gcloud monitoring policies create \"${create_args[@]}\"")
                .contains("--policy-from-file=$policy_file")
                .contains("--notification-channels=$GCP_MONITORING_NOTIFICATION_CHANNELS")
                .contains("Monitoring policy already exists")
                .contains("Created monitoring policy");
    }

    @Test
    void monitoring_policy_templates_cover_cloud_run_and_cloud_sql_platform_risks() throws IOException {
        assertTemplate(
                "cloud-run-5xx-requests.json",
                "run.googleapis.com/request_count",
                "metric.labels.response_code_class = \\\"5xx\\\"",
                "cloud_run_revision",
                "${CLOUD_RUN_SERVICE}"
        );
        assertTemplate(
                "cloud-sql-cpu-high.json",
                "cloudsql.googleapis.com/database/cpu/utilization",
                "thresholdValue\": 0.8",
                "cloudsql_database",
                "${CLOUD_SQL_INSTANCE}"
        );
        assertTemplate(
                "cloud-sql-disk-high.json",
                "cloudsql.googleapis.com/database/disk/utilization",
                "thresholdValue\": 0.85",
                "cloudsql_database",
                "${CLOUD_SQL_INSTANCE}"
        );
    }

    @Test
    void monitoring_policy_templates_are_parameterized_and_do_not_inline_receivers_or_secrets() throws IOException {
        for (String template : Files.list(resolve("ops/google-cloud/monitoring/alert-policies"))
                .filter(path -> path.toString().endsWith(".json"))
                .map(path -> path.getFileName().toString())
                .toList()) {
            String content = Files.readString(resolve("ops/google-cloud/monitoring/alert-policies/" + template));
            assertThat(content)
                    .contains("${TARGET_ENVIRONMENT}")
                    .contains("${GCP_PROJECT_ID}")
                    .contains("trading_bot")
                    .doesNotContain("euggio1907")
                    .doesNotContain("@gmail.com")
                    .doesNotContain("hooks.slack.com")
                    .doesNotContain("routing_key")
                    .doesNotContain("xoxb-")
                    .doesNotContain("-----BEGIN");
        }
    }

    private void assertTemplate(String fileName, String metricType, String expected, String resourceType, String target) throws IOException {
        String content = Files.readString(resolve("ops/google-cloud/monitoring/alert-policies/" + fileName));

        assertThat(content)
                .contains(metricType)
                .contains(expected)
                .contains("resource.type = \\\"" + resourceType + "\\\"")
                .contains(target)
                .contains("\"duration\": \"300s\"")
                .contains("\"managed_by\": \"trading_bot_repo\"");
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
