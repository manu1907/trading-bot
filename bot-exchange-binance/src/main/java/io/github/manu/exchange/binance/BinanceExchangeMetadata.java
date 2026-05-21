package io.github.manu.exchange.binance;

import io.github.manu.exchange.ExchangeMetadata;

import java.time.Instant;
import java.util.List;

public record BinanceExchangeMetadata(
        Instant fetchedAt,
        String restBaseUrl,
        String timezone,
        List<RateLimit> rateLimits,
        List<AssetInfo> assets,
        List<SymbolInfo> symbols
) implements ExchangeMetadata {
    public BinanceExchangeMetadata {
        rateLimits = List.copyOf(rateLimits);
        assets = List.copyOf(assets);
        symbols = List.copyOf(symbols);
    }

    @Override
    public String provider() {
        return "binance";
    }

    public record RateLimit(
            String rateLimitType,
            String interval,
            int intervalNum,
            int limit
    ) {
    }

    public record AssetInfo(
            String asset,
            boolean marginAvailable,
            String autoAssetExchange
    ) {
    }

    public record SymbolInfo(
            String symbol,
            String pair,
            String contractType,
            String status,
            String baseAsset,
            String quoteAsset,
            String marginAsset,
            List<String> orderTypes,
            List<String> timeInForce,
            List<Filter> filters
    ) {
        public SymbolInfo {
            orderTypes = List.copyOf(orderTypes);
            timeInForce = List.copyOf(timeInForce);
            filters = List.copyOf(filters);
        }
    }

    public record Filter(
            String filterType,
            String minPrice,
            String maxPrice,
            String tickSize,
            String minQty,
            String maxQty,
            String stepSize,
            String notional,
            Integer limit
    ) {
    }
}
