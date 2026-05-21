package io.github.manu.config.service;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.ConfigLoader;
import io.github.manu.config.ConfigReloadPolicy;
import io.github.manu.config.ConfigValidator;
import io.github.manu.runtime.RuntimeDescriptor;
import io.github.manu.runtime.RuntimeIdentityService;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exchange.runtime.ExchangeManager;
import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.TradingBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/// Reloads the external configuration from disk when the active
/// runtime config files change. Applies only mutable changes and
/// rejects immutable runtime identity/session changes.
@Service
public class ConfigReloadService {

    private static final Logger log = LoggerFactory.getLogger(ConfigReloadService.class);

    private final ConfigManager configManager;
    private final ExchangeManager exchangeManager;
    private final ConfigValidator validator;
    private final ConfigLoader configLoader;
    private final ActiveProfileProvider profileProvider;
    private final RuntimeIdentityService runtimeIdentityService;
    private final AuditLogger auditLogger;
    private final ConfigReloadPolicy reloadPolicy;

    public ConfigReloadService(ConfigManager configManager,
                               ExchangeManager exchangeManager,
                               ConfigValidator validator,
                               ConfigLoader configLoader,
                               ActiveProfileProvider profileProvider,
                               RuntimeIdentityService runtimeIdentityService,
                               AuditLogger auditLogger,
                               ConfigReloadPolicy reloadPolicy) {
        this.configManager = configManager;
        this.exchangeManager = exchangeManager;
        this.validator = validator;
        this.configLoader = configLoader;
        this.profileProvider = profileProvider;
        this.runtimeIdentityService = runtimeIdentityService;
        this.auditLogger = auditLogger;
        this.reloadPolicy = reloadPolicy;
    }

    /// Called by the ProfileFileWatcher when the active config files change.
    /// Reads the resolved config, validates it, and applies only mutable changes.
    public void reloadFromProfileFile() {
        try {
            RuntimeProfile activeProfile = profileProvider.activeProfile();

            TradingBotProperties freshConfig = configLoader.loadBaseline(activeProfile);

            // Validate it
            validator.validate(freshConfig);

            TradingBotProperties current = configManager.getConfig();
            ConfigReloadPolicy.ReloadDecision decision = reloadPolicy.assess(current, freshConfig);
            RuntimeDescriptor descriptor = runtimeIdentityService.apply(current);

            if (decision.restartRequired()) {
                auditLogger.configurationReloadRejected(descriptor, decision.reason());
                log.warn("Configuration reload rejected; restart required: {}", decision.reason());
                return;
            }

            exchangeManager.reconfigureActiveRuntime(freshConfig);
            configManager.setConfig(freshConfig);
            descriptor = runtimeIdentityService.apply(freshConfig);
            auditLogger.configurationReloaded(descriptor);

            log.info("Configuration reloaded and applied.");

        } catch (Exception e) {
            log.error("Failed to reload configuration from profile file: {}", e.getMessage());
            // Old config stays active.
        }
    }
}
