package io.github.manu.config;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.runtime.RuntimeDescriptor;
import io.github.manu.runtime.RuntimeIdentityService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component("configBootstrap")
public class ConfigBootstrap {

    private final ConfigLoader configLoader;
    private final ConfigValidator configValidator;
    private final ConfigManager configManager;
    private final ActiveProfileProvider profileProvider;
    private final RuntimeIdentityService runtimeIdentityService;
    private final AuditLogger auditLogger;

    public ConfigBootstrap(ConfigLoader configLoader,
                           ConfigValidator configValidator,
                           ConfigManager configManager,
                           ActiveProfileProvider profileProvider,
                           RuntimeIdentityService runtimeIdentityService,
                           AuditLogger auditLogger) {
        this.configLoader = configLoader;
        this.configValidator = configValidator;
        this.configManager = configManager;
        this.profileProvider = profileProvider;
        this.runtimeIdentityService = runtimeIdentityService;
        this.auditLogger = auditLogger;
    }

    @PostConstruct
    void initialize() {
        TradingBotProperties config = configLoader.loadBaseline(profileProvider.activeProfile());
        configValidator.validate(config);
        configManager.setConfig(config);
        RuntimeDescriptor descriptor = runtimeIdentityService.apply(config);
        auditLogger.runtimeBootstrapped(descriptor);
    }
}
