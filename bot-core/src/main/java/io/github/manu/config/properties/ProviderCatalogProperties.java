package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderCatalogProperties {

    private final Map<String, JsonNode> providers = new LinkedHashMap<>();

    public ProviderCatalogProperties() {
    }

    public ProviderCatalogProperties(ProviderCatalogProperties other) {
        other.providers.forEach((name, config) -> providers.put(name, config.deepCopy()));
    }

    @JsonAnySetter
    public void put(String name, JsonNode config) {
        providers.put(name.toLowerCase(), config.deepCopy());
    }

    @JsonAnyGetter
    public Map<String, JsonNode> providers() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        providers.forEach((name, config) -> copy.put(name, config.deepCopy()));
        return Map.copyOf(copy);
    }

    @JsonIgnore
    public JsonNode get(String name) {
        JsonNode provider = providers.get(name.toLowerCase());
        return provider == null ? null : provider.deepCopy();
    }
}
