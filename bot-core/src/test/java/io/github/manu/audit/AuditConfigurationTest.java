package io.github.manu.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConfigurationTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void does_not_register_file_store_by_default() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(PauseGovernanceAuditStore.class));
    }

    @Test
    void registers_file_store_when_enabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditConfiguration.class)
                .withPropertyValues(
                        "trading.audit.pause-governance.file-store.enabled=true",
                        "trading.audit.pause-governance.file-store.path="
                                + temporaryDirectory.resolve("pause-governance.jsonl")
                )
                .run(context -> assertThat(context).hasSingleBean(PauseGovernanceAuditStore.class));
    }

    @Test
    void registers_jdbc_store_when_enabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditConfiguration.class)
                .withPropertyValues(
                        "trading.audit.pause-governance.jdbc-store.enabled=true",
                        "trading.audit.pause-governance.jdbc-store.url=" + url(),
                        "trading.audit.pause-governance.jdbc-store.table-prefix=trading_audit_test_",
                        "trading.audit.pause-governance.jdbc-store.initialize-schema=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PauseGovernanceAuditStore.class);
                    assertThat(context.getBean(PauseGovernanceAuditStore.class).storeName()).isEqualTo("jdbc");
                });
    }

    private String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    }
}
