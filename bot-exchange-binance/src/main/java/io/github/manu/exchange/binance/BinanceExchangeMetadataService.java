package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import io.github.manu.exchange.ExchangeMetadataFetcher;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.Objects;

@Service
public class BinanceExchangeMetadataService implements ExchangeMetadataFetcher {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeMetadataService.class);

    private final Function<BinanceProperties.Rest, BinanceExchangeInfoClient> clientFactory;
    private final AtomicReference<BinanceExchangeMetadata> currentMetadata = new AtomicReference<>();

    public BinanceExchangeMetadataService() {
        this(BinanceExchangeInfoClient::new);
    }

    BinanceExchangeMetadataService(Function<BinanceProperties.Rest, BinanceExchangeInfoClient> clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
    }

    @Override
    public String provider() {
        return "binance";
    }

    @Override
    public Optional<BinanceExchangeMetadata> current() {
        return Optional.ofNullable(currentMetadata.get());
    }

    @Override
    public Optional<BinanceExchangeMetadata> refresh(ResolvedExchangeConfig config) {
        if (!"binance".equalsIgnoreCase(config.provider())) {
            currentMetadata.set(null);
            return Optional.empty();
        }

        BinanceProperties binance = config.providerConfig(BinanceProviderProperties.class).resolve(config.target());
        String url = binance.rest().baseUrl() + binance.rest().exchangeInfoPath();

        try {
            BinanceExchangeMetadata metadata = clientFactory.apply(binance.rest()).fetch();
            currentMetadata.set(metadata);
            log.info("Loaded Binance exchange metadata from {}: {} rate limits, {} symbols",
                    url, metadata.rateLimits().size(), metadata.symbols().size());
            return Optional.of(metadata);
        } catch (Exception e) {
            log.warn("Failed to load Binance exchange metadata from {}: {}", url, e.getMessage());
            return Optional.ofNullable(currentMetadata.get());
        }
    }
}
