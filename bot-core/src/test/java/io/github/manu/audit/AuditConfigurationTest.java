package io.github.manu.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

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
}
