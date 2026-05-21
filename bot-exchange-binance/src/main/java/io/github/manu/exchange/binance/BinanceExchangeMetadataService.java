package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import io.github.manu.exchange.ExchangeMetadataFetcher;
import io.github.manu.exchange.ResolvedExchangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BinanceExchangeMetadataService implements ExchangeMetadataFetcher {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeMetadataService.class);

    private final WebClient webClient;
    private final AtomicReference<BinanceExchangeMetadata> currentMetadata = new AtomicReference<>();

    public BinanceExchangeMetadataService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
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
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        String url = binance.rest().baseUrl() + marketType.exchangeInfoPath();

        try {
            JsonNode response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("Empty response body");
            }

            BinanceExchangeMetadata metadata = toMetadata(binance.rest().baseUrl(), response);
            currentMetadata.set(metadata);
            log.info("Loaded Binance exchange metadata from {}: {} rate limits, {} symbols",
                    url, metadata.rateLimits().size(), metadata.symbols().size());
            return Optional.of(metadata);
        } catch (Exception e) {
            log.warn("Failed to load Binance exchange metadata from {}: {}", url, e.getMessage());
            return Optional.ofNullable(currentMetadata.get());
        }
    }

    private BinanceExchangeMetadata toMetadata(String restBaseUrl, JsonNode response) {
        return new BinanceExchangeMetadata(
                Instant.now(),
                restBaseUrl,
                response.path("timezone").asString(),
                parseRateLimits(response.path("rateLimits")),
                parseAssets(response.path("assets")),
                parseSymbols(response.path("symbols"))
        );
    }

    private List<BinanceExchangeMetadata.RateLimit> parseRateLimits(JsonNode node) {
        List<BinanceExchangeMetadata.RateLimit> rateLimits = new ArrayList<>();
        if (!node.isArray()) {
            return rateLimits;
        }

        for (JsonNode item : node) {
            rateLimits.add(new BinanceExchangeMetadata.RateLimit(
                    item.path("rateLimitType").asString(),
                    item.path("interval").asString(),
                    item.path("intervalNum").asInt(),
                    item.path("limit").asInt()
            ));
        }
        return rateLimits;
    }

    private List<BinanceExchangeMetadata.AssetInfo> parseAssets(JsonNode node) {
        List<BinanceExchangeMetadata.AssetInfo> assets = new ArrayList<>();
        if (!node.isArray()) {
            return assets;
        }

        for (JsonNode item : node) {
            assets.add(new BinanceExchangeMetadata.AssetInfo(
                    item.path("asset").asString(),
                    item.path("marginAvailable").asBoolean(),
                    textOrNull(item.path("autoAssetExchange"))
            ));
        }
        return assets;
    }

    private List<BinanceExchangeMetadata.SymbolInfo> parseSymbols(JsonNode node) {
        List<BinanceExchangeMetadata.SymbolInfo> symbols = new ArrayList<>();
        if (!node.isArray()) {
            return symbols;
        }

        for (JsonNode item : node) {
            symbols.add(new BinanceExchangeMetadata.SymbolInfo(
                    item.path("symbol").asString(),
                    item.path("pair").asString(),
                    item.path("contractType").asString(),
                    item.path("status").asString(),
                    item.path("baseAsset").asString(),
                    item.path("quoteAsset").asString(),
                    item.path("marginAsset").asString(),
                    parseStringArray(item.path("orderTypes")),
                    parseStringArray(item.path("timeInForce")),
                    parseFilters(item.path("filters"))
            ));
        }
        return symbols;
    }

    private List<BinanceExchangeMetadata.Filter> parseFilters(JsonNode node) {
        List<BinanceExchangeMetadata.Filter> filters = new ArrayList<>();
        if (!node.isArray()) {
            return filters;
        }

        for (JsonNode item : node) {
            filters.add(new BinanceExchangeMetadata.Filter(
                    item.path("filterType").asString(),
                    textOrNull(item.path("minPrice")),
                    textOrNull(item.path("maxPrice")),
                    textOrNull(item.path("tickSize")),
                    textOrNull(item.path("minQty")),
                    textOrNull(item.path("maxQty")),
                    textOrNull(item.path("stepSize")),
                    textOrNull(item.path("notional")),
                    item.hasNonNull("limit") ? item.path("limit").asInt() : null
            ));
        }
        return filters;
    }

    private List<String> parseStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }

        for (JsonNode item : node) {
            values.add(item.asString());
        }
        return values;
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asString();
    }
}
