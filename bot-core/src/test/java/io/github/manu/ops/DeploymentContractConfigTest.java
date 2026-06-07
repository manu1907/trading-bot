package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentContractConfigTest {

    private static final String CONTRACT_SCHEMA_PATH = "ops/deployment/deployment-contract.yml";
    private static final List<String> DEPLOYMENT_CONTRACTS = List.of(
            "ops/google-cloud/demo-usdm-futures-deployment.yml",
            "ops/aws/demo-usdm-futures-deployment.yml"
    );

    @Test
    void cloud_deployment_contracts_conform_to_neutral_runtime_contract() throws IOException {
        Map<String, Object> schema = yaml(CONTRACT_SCHEMA_PATH);

        for (String contractPath : DEPLOYMENT_CONTRACTS) {
            Map<String, Object> contract = yaml(contractPath);

            assertRequiredTopLevelSections(schema, contract);
            assertContractIdentity(schema, contract);
            assertRequiredDeploymentFields(schema, contract);
            assertRequiredRuntimeEnv(schema, contract);
            assertSecretBindings(schema, contract, "secret_env", "required_secret_env");
            assertSecretBindings(
                    schema,
                    contract,
                    "alertmanager_secret_substitutions",
                    "required_alertmanager_secret_substitutions"
            );
            assertAuditBackend(schema, contract);
            assertNoInlineSecretValues(schema, contractPath);
        }
    }

    @Test
    void cloud_deployment_contracts_keep_the_same_app_facing_runtime_surface() throws IOException {
        Map<String, Object> googleCloud = yaml("ops/google-cloud/demo-usdm-futures-deployment.yml");
        Map<String, Object> aws = yaml("ops/aws/demo-usdm-futures-deployment.yml");

        assertThat(map(googleCloud, "runtime_env")).isEqualTo(map(aws, "runtime_env"));
        assertThat(map(googleCloud, "secret_env").keySet()).isEqualTo(map(aws, "secret_env").keySet());
        assertThat(map(googleCloud, "alertmanager_secret_substitutions").keySet())
                .isEqualTo(map(aws, "alertmanager_secret_substitutions").keySet());
    }

    private void assertRequiredTopLevelSections(Map<String, Object> schema, Map<String, Object> contract) {
        strings(schema, "required_top_level_sections")
                .forEach(section -> assertThat(contract).containsKey(section));
    }

    private void assertContractIdentity(Map<String, Object> schema, Map<String, Object> contract) {
        Map<String, Object> expected = map(schema, "contract");
        Map<String, Object> actual = map(contract, "contract");
        assertThat(actual)
                .containsEntry("id", expected.get("id"))
                .containsEntry("version", expected.get("version"));
    }

    private void assertRequiredDeploymentFields(Map<String, Object> schema, Map<String, Object> contract) {
        Map<String, Object> deployment = map(contract, "deployment");
        strings(schema, "required_deployment_fields")
                .forEach(field -> assertThat(deployment.get(field)).as(field).isNotNull());
    }

    private void assertRequiredRuntimeEnv(Map<String, Object> schema, Map<String, Object> contract) {
        Map<String, Object> runtimeEnv = map(contract, "runtime_env");
        map(schema, "required_runtime_env")
                .forEach((name, expectedValue) -> assertThat(runtimeEnv).containsEntry(name, expectedValue));
    }

    private void assertSecretBindings(
            Map<String, Object> schema,
            Map<String, Object> contract,
            String sectionName,
            String schemaSectionName
    ) {
        Map<String, Object> secretEnv = map(contract, sectionName);
        assertThat(secretEnv.keySet()).containsAll(strings(schema, schemaSectionName));
        strings(schema, schemaSectionName).forEach(name -> {
            Map<String, Object> binding = map(secretEnv, name);
            strings(map(schema, "secret_binding_shape"), "required_fields")
                    .forEach(field -> assertThat(binding.get(field)).as(name + "." + field).isNotNull());
        });
    }

    private void assertAuditBackend(Map<String, Object> schema, Map<String, Object> contract) {
        Map<String, Object> schemaAuditBackend = map(schema, "audit_backend");
        Map<String, Object> auditBackend = map(contract, "audit_backend");
        assertThat(auditBackend).containsEntry("selected", schemaAuditBackend.get("required_selected"));
        Map<String, Object> jdbc = map(auditBackend, "jdbc");
        strings(schemaAuditBackend, "required_jdbc_fields")
                .forEach(field -> assertThat(jdbc.get(field)).as(field).isNotNull());
        Map<String, Object> backups = map(jdbc, "backups");
        strings(schemaAuditBackend, "required_backup_fields")
                .forEach(field -> assertThat(backups.get(field)).as(field).isNotNull());
        assertThat(jdbc).containsEntry("table_prefix", "trading_audit_pause_governance_");
        assertThat(jdbc).containsEntry("retention_days", 180);
        assertThat(backups)
                .containsEntry("enabled", true)
                .containsEntry("minimum_recovery_days", 7)
                .containsEntry("restore_test_interval_days", 90);
    }

    private void assertNoInlineSecretValues(Map<String, Object> schema, String contractPath) throws IOException {
        String content = Files.readString(resolve(contractPath));
        strings(map(schema, "secret_binding_shape"), "forbidden_inline_patterns")
                .forEach(pattern -> assertThat(content).doesNotContain(pattern));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String path) throws IOException {
        try (var reader = Files.newBufferedReader(resolve(path))) {
            return new Yaml().loadAs(reader, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Set<String> strings(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as(key).isInstanceOf(List.class);
        return Set.copyOf((List<String>) value);
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
