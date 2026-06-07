package io.github.manu.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigCatalogDefaultsTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void checked_in_catalog_lists_runtime_overridable_trading_defaults() throws IOException {
        JsonNode catalog = jsonMapper.readTree(resolveCatalogPath().toFile());

        assertBoolean(catalog, "trading.journal.enabled", false);
        assertText(catalog, "trading.journal.directory", "data/journal/trading-events");
        assertBoolean(catalog, "trading.journal.recovery.enabled", false);

        assertBoolean(catalog, "trading.projection.snapshot_store.enabled", false);
        assertText(catalog, "trading.projection.snapshot_store.path", "data/projection/trading-state-snapshot.json");
        assertBoolean(catalog, "trading.projection.jdbc_store.enabled", false);
        assertNull(catalog, "trading.projection.jdbc_store.url");
        assertNull(catalog, "trading.projection.jdbc_store.username");
        assertText(catalog, "trading.projection.jdbc_store.password", "");
        assertText(catalog, "trading.projection.jdbc_store.table_prefix", "trading_projection_");
        assertBoolean(catalog, "trading.projection.jdbc_store.initialize_schema", false);

        assertBoolean(catalog, "trading.messaging.enabled", false);
        assertText(catalog, "trading.messaging.bootstrap_servers", "localhost:19092");
        assertText(catalog, "trading.messaging.schema_registry_url", "http://localhost:18081");
        assertText(catalog, "trading.messaging.client_id_prefix", "trading-bot");
        assertBoolean(catalog, "trading.messaging.topics.auto_create", false);
        assertInt(catalog, "trading.messaging.topics.replication_factor", 1);
        assertBoolean(catalog, "trading.messaging.consumers.enabled", false);
        assertBoolean(catalog, "trading.messaging.consumers.auto_start", false);
        assertText(catalog, "trading.messaging.consumers.group_id_suffix", "dispatcher");
        assertInt(catalog, "trading.messaging.consumers.poll_timeout_millis", 250);

        assertBoolean(catalog, "trading.audit.pause_governance.file_store.enabled", false);
        assertText(catalog, "trading.audit.pause_governance.file_store.path", "data/audit/pause-governance-audit.jsonl");
        assertBoolean(catalog, "trading.audit.pause_governance.jdbc_store.enabled", false);
        assertNull(catalog, "trading.audit.pause_governance.jdbc_store.url");
        assertNull(catalog, "trading.audit.pause_governance.jdbc_store.username");
        assertText(catalog, "trading.audit.pause_governance.jdbc_store.password", "");
        assertText(catalog, "trading.audit.pause_governance.jdbc_store.table_prefix",
                "trading_audit_pause_governance_");
        assertBoolean(catalog, "trading.audit.pause_governance.jdbc_store.initialize_schema", false);

        assertBoolean(catalog, "trading.observability.pause_governance.expiry_monitor.enabled", true);
        assertInt(catalog, "trading.observability.pause_governance.expiry_monitor.interval_millis", 30000);

        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.enabled", false);
        assertInt(catalog, "trading.intervention.automated_remediation_runner.interval_millis", 30000);
        assertInt(catalog, "trading.intervention.automated_remediation_runner.initial_delay_millis", 30000);
        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.publish_decisions", true);
        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.execute_remediation", true);
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.provider");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.environment");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.account");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.market");
    }

    @Test
    void checked_in_catalog_keeps_executor_exchange_execution_as_explicit_demo_live_override() throws IOException {
        JsonNode catalog = jsonMapper.readTree(resolveCatalogPath().toFile());

        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.enabled", false);
        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.exchange_execution_enabled", false);
        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.report_only", true);
        assertThat(requiredNode(catalog, "trading.intervention.remediation_executor_policy.allowed_operations"))
                .as("trading.intervention.remediation_executor_policy.allowed_operations")
                .isEmpty();
    }

    private void assertBoolean(JsonNode root, String path, boolean expected) {
        assertThat(requiredNode(root, path).asBoolean()).as(path).isEqualTo(expected);
    }

    private void assertInt(JsonNode root, String path, int expected) {
        assertThat(requiredNode(root, path).asInt()).as(path).isEqualTo(expected);
    }

    private void assertText(JsonNode root, String path, String expected) {
        assertThat(requiredNode(root, path).asString()).as(path).isEqualTo(expected);
    }

    private void assertNull(JsonNode root, String path) {
        assertThat(requiredNode(root, path).isNull()).as(path).isTrue();
    }

    private JsonNode requiredNode(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current.get(segment);
            assertThat(current).as(path).isNotNull();
        }
        return current;
    }

    private Path resolveCatalogPath() {
        Path cwdCatalog = Path.of("config", "catalog.json");
        if (Files.exists(cwdCatalog)) {
            return cwdCatalog;
        }

        Path parentCatalog = Path.of("..", "config", "catalog.json").normalize();
        if (Files.exists(parentCatalog)) {
            return parentCatalog;
        }

        throw new IllegalStateException("Unable to locate checked-in config/catalog.json");
    }
}
