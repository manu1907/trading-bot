package io.github.manu.audit;

import io.github.manu.config.JsonMapperFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {

    @Bean
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
}
