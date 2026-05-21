package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeSectionProperties {

    @Valid
    @NotNull
    private ExchangeProperties active;

    @Valid
    @NotNull
    private ProviderCatalogProperties providers;

    public ExchangeSectionProperties() {
    }

    public ExchangeSectionProperties(ExchangeSectionProperties other) {
        this.active = other.active;
        this.providers = new ProviderCatalogProperties(other.providers);
    }

    public ExchangeProperties getActive() {
        return active;
    }

    public void setActive(ExchangeProperties active) {
        this.active = active;
    }

    public ProviderCatalogProperties getProviders() {
        return new ProviderCatalogProperties(providers);
    }

    public void setProviders(ProviderCatalogProperties providers) {
        this.providers = new ProviderCatalogProperties(providers);
    }

    @JsonIgnore
    public ProvidersProperties resolveProviders() {
        JsonNode activeProviderConfig = providers.get(active.provider());
        if (activeProviderConfig == null) {
            throw new IllegalArgumentException("Missing provider configuration: " + active.provider());
        }
        return new ProvidersProperties(activeProviderConfig);
    }
}
