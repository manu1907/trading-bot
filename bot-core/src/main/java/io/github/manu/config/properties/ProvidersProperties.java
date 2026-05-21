package io.github.manu.config.properties;

import tools.jackson.databind.JsonNode;

public record ProvidersProperties(
        JsonNode active
) {
    public ProvidersProperties {
        active = active == null ? null : active.deepCopy();
    }

    public JsonNode requiredActive(String provider) {
        if (active == null || active.isNull() || active.isMissingNode()) {
            throw new IllegalArgumentException("Missing provider configuration for " + provider);
        }
        return active.deepCopy();
    }
}
