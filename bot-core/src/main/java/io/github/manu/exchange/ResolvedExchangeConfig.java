package io.github.manu.exchange;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.ProvidersProperties;
import io.github.manu.config.properties.TradingBotProperties;
import tools.jackson.databind.JsonNode;

public record ResolvedExchangeConfig(
        ExchangeProperties target,
        ProvidersProperties providers
) {
    public static ResolvedExchangeConfig from(TradingBotProperties properties) {
        return new ResolvedExchangeConfig(properties.getExchange(), properties.getProviders());
    }

    public String provider() {
        return target.provider();
    }

    public JsonNode providerConfig() {
        return providers.requiredActive(provider());
    }

    public <T> T providerConfig(Class<T> type) {
        try {
            return JsonMapperFactory.create().treeToValue(providerConfig(), type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to bind provider config for " + provider(), e);
        }
    }
}
