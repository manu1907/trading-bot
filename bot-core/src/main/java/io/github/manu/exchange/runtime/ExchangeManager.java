package io.github.manu.exchange.runtime;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.ExchangeModuleFactory;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.config.runtime.ConfigManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("configBootstrap")
public class ExchangeManager {

    private static final Logger log = LoggerFactory.getLogger(ExchangeManager.class);

    private final ExchangeModuleFactory moduleFactory;
    private final ExchangeMetadataService exchangeMetadataService;
    private final ConfigManager configManager;
    private volatile ExchangeModule activeModule;
    private volatile String activeExchangeName;

    public ExchangeManager(ExchangeModuleFactory moduleFactory,
                           ExchangeMetadataService exchangeMetadataService,
                           ConfigManager configManager) {
        this.moduleFactory = moduleFactory;
        this.exchangeMetadataService = exchangeMetadataService;
        this.configManager = configManager;
    }

    @PostConstruct
    void init() {
        TradingBotProperties config = configManager.getConfig();
        activate(config);
    }

    private void activate(TradingBotProperties config) {
        ResolvedExchangeConfig resolvedConfig = ResolvedExchangeConfig.from(config);
        refreshMetadata(resolvedConfig);
        ExchangeProperties props = resolvedConfig.target();
        activeExchangeName = props.provider();
        activeModule = moduleFactory.get(props.provider());
        activeModule.configure(resolvedConfig);
        activeModule.connect().join();
        log.info("Connected to exchange: {}", activeExchangeName);
    }

    /// Applies a mutable configuration update to the active runtime target.
    public void reconfigureActiveRuntime(TradingBotProperties newConfig) {
        ResolvedExchangeConfig resolvedConfig = ResolvedExchangeConfig.from(newConfig);
        ExchangeProperties newProps = resolvedConfig.target();
        if (!newProps.provider().equals(activeExchangeName)) {
            throw new IllegalStateException(
                    "Runtime target change requires restart: expected provider %s but received %s"
                            .formatted(activeExchangeName, newProps.provider())
            );
        }
        refreshMetadata(resolvedConfig);
        activeModule.applyMutableConfig(resolvedConfig).join();
        log.info("Reconfigured active runtime: {}", activeExchangeName);
    }

    private void refreshMetadata(ResolvedExchangeConfig config) {
        exchangeMetadataService.refresh(config);
    }

    public ExchangeModule getActiveModule() {
        return activeModule;
    }
}
