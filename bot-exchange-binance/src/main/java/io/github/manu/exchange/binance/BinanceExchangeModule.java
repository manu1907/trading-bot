package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class BinanceExchangeModule implements ExchangeModule {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeModule.class);

    private volatile ResolvedExchangeConfig config;
    private volatile boolean connected;

    @Override
    public String provider() {
        return "binance";
    }

    @Override
    public void validateConfig(ResolvedExchangeConfig config) {
        requireBinance(config);
    }

    @Override
    public void configure(ResolvedExchangeConfig config) {
        BinanceProperties binance = requireBinance(config);
        this.config = config;
        log.info(
                "Configured Binance exchange module: environment={}, account={}, market={}, restBaseUrl={}, websocketBaseUrl={}",
                config.target().environment(),
                config.target().account(),
                config.target().market(),
                binance.rest().baseUrl(),
                binance.websocket().baseUrl()
        );
    }

    @Override
    public CompletableFuture<Void> connect() {
        ensureConfigured();
        connected = true;
        log.info("Binance exchange module connected for target {}", config.target());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        log.info("Binance exchange module disconnected");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> applyMutableConfig(ResolvedExchangeConfig config) {
        configure(config);
        if (connected) {
            log.info("Applied Binance runtime configuration update for target {}", config.target());
        }
        return CompletableFuture.completedFuture(null);
    }

    private BinanceProperties requireBinance(ResolvedExchangeConfig config) {
        if (!provider().equalsIgnoreCase(config.provider())) {
            throw new IllegalArgumentException("Binance module cannot handle provider: " + config.provider());
        }
        return config.providerConfig(BinanceProviderProperties.class).resolve(config.target());
    }

    private void ensureConfigured() {
        if (config == null) {
            throw new IllegalStateException("Binance exchange module is not configured");
        }
    }
}
