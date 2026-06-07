package io.github.manu.audit;

import io.github.manu.config.JsonMapperFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {

    @Bean
    @Order(100)
    @ConditionalOnProperty(
            prefix = "trading.audit.pause-governance.file-store",
            name = "enabled",
            havingValue = "true"
    )
    PauseGovernanceAuditStore filePauseGovernanceAuditStore(AuditProperties properties) {
        return new FilePauseGovernanceAuditStore(
                properties.pauseGovernance().fileStore().path(),
                JsonMapperFactory.create()
        );
    }

    @Bean
    @Order(0)
    @ConditionalOnProperty(
            prefix = "trading.audit.pause-governance.jdbc-store",
            name = "enabled",
            havingValue = "true"
    )
    PauseGovernanceAuditStore jdbcPauseGovernanceAuditStore(AuditProperties properties) {
        AuditProperties.JdbcStore jdbcStore = properties.pauseGovernance().jdbcStore();
        JdbcPauseGovernanceAuditStore store = new JdbcPauseGovernanceAuditStore(
                jdbcStore.url(),
                jdbcStore.username(),
                jdbcStore.password(),
                jdbcStore.tablePrefix(),
                JsonMapperFactory.create()
        );
        if (jdbcStore.initializeSchema()) {
            store.initializeSchema();
        }
        return store;
    }
}
