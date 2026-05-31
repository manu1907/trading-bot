package io.github.manu.exchange.binance;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BinanceExchangeInfoParser {

    BinanceExchangeMetadata parse(String restBaseUrl, JsonNode response, Instant fetchedAt) {
        return new BinanceExchangeMetadata(
                fetchedAt,
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
                    longOrNull(item.path("deliveryDate")),
                    longOrNull(item.path("onboardDate")),
                    item.path("status").asString(),
                    item.path("baseAsset").asString(),
                    item.path("quoteAsset").asString(),
                    item.path("marginAsset").asString(),
                    intOrNull(item.path("pricePrecision")),
                    intOrNull(item.path("quantityPrecision")),
                    intOrNull(item.path("baseAssetPrecision")),
                    intOrNull(item.path("quotePrecision")),
                    textOrNull(item.path("underlyingType")),
                    parseStringArray(item.path("underlyingSubType")),
                    textOrNull(item.path("triggerProtect")),
                    textOrNull(item.path("liquidationFee")),
                    textOrNull(item.path("marketTakeBound")),
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
                    textOrNull(item.path("minNotional")),
                    textOrNull(item.path("maxNotional")),
                    optionalBoolean(item.path("applyToMarket")).orElse(null),
                    optionalBoolean(item.path("applyMinToMarket")).orElse(null),
                    optionalBoolean(item.path("applyMaxToMarket")).orElse(null),
                    textOrNull(item.path("multiplierUp")),
                    textOrNull(item.path("multiplierDown")),
                    textOrNull(item.path("multiplierDecimal")),
                    intOrNull(item.path("avgPriceMins")),
                    textOrNull(item.path("bidMultiplierUp")),
                    textOrNull(item.path("bidMultiplierDown")),
                    textOrNull(item.path("askMultiplierUp")),
                    textOrNull(item.path("askMultiplierDown")),
                    intOrNull(item.path("limit"))
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

    private Long longOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asLong();
    }

    private Integer intOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private Optional<Boolean> optionalBoolean(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.asBoolean());
    }
}
