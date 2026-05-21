package io.github.manu.config.runtime;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/// Central in‑memory configuration holder.
/// The live config is read by all trading components on every tick/signal,
/// so we use an AtomicReference for lock‑free reads (low‑latency friendly).
///
/// ULTRA‑LOW‑LATENCY NOTE:
/// getConfig() is a single volatile read; no locking, no allocation.
@Component
public final class ConfigManager {

    private final AtomicReference<TradingBotProperties> currentConfig = new AtomicReference<>();

    public TradingBotProperties getConfig() {
        return currentConfig.get();
    }

    public void setConfig(TradingBotProperties newConfig) {
        this.currentConfig.set(newConfig);
    }

    /**
     * Convenience getter for the exchange section.
     */
    public ExchangeProperties getExchange() {
        return getConfig().getExchange();
    }
}
