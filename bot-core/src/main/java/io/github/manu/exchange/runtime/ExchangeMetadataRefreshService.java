package io.github.manu.exchange.runtime;

import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.ResolvedExchangeConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Profile("live")
public final class ExchangeMetadataRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeMetadataRefreshService.class);
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(15);

    private final ConfigManager configManager;
    private final ExchangeMetadataService exchangeMetadataService;
    private final Duration refreshInterval;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledTask;

    @Autowired
    public ExchangeMetadataRefreshService(ConfigManager configManager,
                                          ExchangeMetadataService exchangeMetadataService) {
        this(
                configManager,
                exchangeMetadataService,
                DEFAULT_REFRESH_INTERVAL,
                Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "exchange-metadata-refresh");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    ExchangeMetadataRefreshService(ConfigManager configManager,
                                   ExchangeMetadataService exchangeMetadataService,
                                   Duration refreshInterval,
                                   ScheduledExecutorService executor) {
        this.configManager = Objects.requireNonNull(configManager, "configManager is required");
        this.exchangeMetadataService = Objects.requireNonNull(exchangeMetadataService, "exchangeMetadataService is required");
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        if (refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("refreshInterval must be positive");
        }
    }

    @PostConstruct
    void start() {
        long intervalMillis = refreshInterval.toMillis();
        scheduledTask = executor.scheduleWithFixedDelay(
                this::refreshActiveMetadataSafely,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        log.info("Scheduled exchange metadata refresh every {}", refreshInterval);
    }

    @PreDestroy
    void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        executor.shutdownNow();
    }

    void refreshActiveMetadata() {
        TradingBotProperties config = configManager.getConfig();
        if (config == null) {
            return;
        }
        exchangeMetadataService.refresh(ResolvedExchangeConfig.from(config));
    }

    private void refreshActiveMetadataSafely() {
        try {
            refreshActiveMetadata();
        } catch (RuntimeException e) {
            log.warn("Failed to refresh exchange metadata: {}", e.getMessage());
        }
    }
}
