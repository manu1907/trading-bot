package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCloudDemoDeploymentConfigTest {

    private static final String DEPLOYMENT_PATH = "ops/google-cloud/demo-usdm-futures-deployment.yml";

    @Test
    void demo_deployment_selects_active_target_and_jdbc_audit_backend() throws IOException {
        Map<String, Object> deployment = yaml(DEPLOYMENT_PATH);

        Map<String, Object> contract = map(deployment, "contract");
        assertThat(contract)
                .containsEntry("id", "io.github.manu.trading-bot.deployment-contract")
                .containsEntry("version", 1);

        Map<String, Object> metadata = map(deployment, "deployment");
        assertThat(metadata)
                .containsEntry("platform", "google_cloud")
                .containsEntry("runtime", "cloud_run")
                .containsEntry("provider", "binance")
                .containsEntry("environment", "demo")
                .containsEntry("account", "main")
                .containsEntry("market", "usdm_futures")
                .containsEntry("service_name", "trading-bot-demo-main-usdm-futures");

        Map<String, Object> runtimeEnv = map(deployment, "runtime_env");
        assertThat(runtimeEnv)
                .containsEntry("SPRING_PROFILES_ACTIVE", "live")
                .containsEntry("BOT_CONFIG_DIR", "/app/config")
                .containsEntry("BOT_PROVIDER", "binance")
                .containsEntry("BOT_ENVIRONMENT", "demo")
                .containsEntry("BOT_ACCOUNT", "main")
                .containsEntry("BOT_MARKET", "usdm_futures")
                .containsEntry("TRADING_INTERVENTION_OPERATOR_API_ENABLED", "true")
                .containsEntry("TRADING_AUDIT_PAUSE_GOVERNANCE_FILE_STORE_ENABLED", "false")
                .containsEntry("TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_STORE_ENABLED", "true")
                .containsEntry("TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_STORE_INITIALIZE_SCHEMA", "false")
                .containsEntry("TRADING_PROJECTION_SNAPSHOT_STORE_ENABLED", "false")
                .containsEntry("TRADING_PROJECTION_JDBC_STORE_ENABLED", "true")
                .containsEntry("TRADING_PROJECTION_JDBC_STORE_TABLE_PREFIX", "trading_projection_")
                .containsEntry("TRADING_PROJECTION_JDBC_STORE_INITIALIZE_SCHEMA", "false");

        Map<String, Object> auditBackend = map(deployment, "audit_backend");
        assertThat(auditBackend).containsEntry("selected", "jdbc");
        Map<String, Object> jdbc = map(auditBackend, "jdbc");
        assertThat(jdbc)
                .containsEntry("database", "cloud_sql_postgresql")
                .containsEntry("schema_owner", "deployment_migration")
                .containsEntry("table_prefix", "trading_audit_pause_governance_")
                .containsEntry("retention_days", 180);
        Map<String, Object> backups = map(jdbc, "backups");
        assertThat(backups)
                .containsEntry("enabled", true)
                .containsEntry("mechanism", "cloud_sql_automated_backup")
                .containsEntry("minimum_recovery_days", 7)
                .containsEntry("restore_test_interval_days", 90);

        Map<String, Object> stateBackend = map(deployment, "state_backend");
        Map<String, Object> projection = map(stateBackend, "projection");
        assertThat(projection).containsEntry("selected", "jdbc");
        Map<String, Object> projectionJdbc = map(projection, "jdbc");
        assertThat(projectionJdbc)
                .containsEntry("database", "cloud_sql_postgresql")
                .containsEntry("schema_owner", "deployment_migration")
                .containsEntry("table_prefix", "trading_projection_")
                .containsEntry("retention_days", 180);
        Map<String, Object> compaction = map(projectionJdbc, "compaction");
        assertThat(compaction)
                .containsEntry("enabled", true)
                .containsEntry("minimum_interval_days", 7)
                .containsEntry("preserve_latest_snapshot", true)
                .containsEntry("preserve_applied_event_ids", true);
        Map<String, Object> projectionBackups = map(projectionJdbc, "backups");
        assertThat(projectionBackups)
                .containsEntry("enabled", true)
                .containsEntry("mechanism", "cloud_sql_automated_backup")
                .containsEntry("minimum_recovery_days", 7)
                .containsEntry("restore_test_interval_days", 90);
        assertThat(map(stateBackend, "journal_archive"))
                .containsEntry("enabled", true)
                .containsEntry("mechanism", "cloud_storage_object_archive")
                .containsEntry("layout", "trading_event_archive_layout_v1")
                .containsEntry("retention_days", 180);
    }

    @Test
    void demo_deployment_maps_required_application_and_alertmanager_secrets() throws IOException {
        Map<String, Object> deployment = yaml(DEPLOYMENT_PATH);

        assertSecretEnvContains(
                map(deployment, "secret_env"),
                Set.of(
                        "BINANCE_DEMO_API_KEY",
                        "BINANCE_DEMO_API_SECRET",
                        "TRADING_INTERVENTION_OPERATOR_API_OPERATOR_TOKEN",
                        "TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_URL",
                        "TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_USERNAME",
                        "TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_PASSWORD",
                        "TRADING_PROJECTION_JDBC_URL",
                        "TRADING_PROJECTION_JDBC_USERNAME",
                        "TRADING_PROJECTION_JDBC_PASSWORD"
                )
        );
        assertSecretEnvContains(
                map(deployment, "alertmanager_secret_substitutions"),
                Set.of(
                        "ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY",
                        "ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY",
                        "ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK",
                        "ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL",
                        "ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK",
                        "ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL",
                        "ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK",
                        "ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL"
                )
        );
    }

    @Test
    void demo_deployment_does_not_inline_secret_values() throws IOException {
        String content = Files.readString(resolve(DEPLOYMENT_PATH));

        assertThat(content)
                .doesNotContain("hooks.slack.com")
                .doesNotContain("xoxb-")
                .doesNotContain("pd_")
                .doesNotContain("-----BEGIN")
                .doesNotContain("jdbc:postgresql://");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String path) throws IOException {
        try (var reader = Files.newBufferedReader(resolve(path))) {
            return new Yaml().loadAs(reader, Map.class);
        }
    }

    private void assertSecretEnvContains(Map<String, Object> secretEnv, Set<String> expectedNames) {
        assertThat(secretEnv.keySet()).containsAll(expectedNames);
        expectedNames.forEach(name -> {
            Map<String, Object> binding = map(secretEnv, name);
            assertThat((String) binding.get("secret")).startsWith("trading-bot-demo-");
            assertThat(binding).containsEntry("version", "latest");
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
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
