package io.github.manu.config.properties;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

@Validated
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradingBotProperties {

    @NotNull
    private Integer version;

    @Valid
    @NotNull
    private ConfigSchemaProperties schema;

    @Valid
    @NotNull
    private BotProperties bot;

    @Valid
    @NotNull
    @JsonProperty("exchange")
    private ExchangeSectionProperties exchangeSection;

    @JsonProperty("exchange")
    public ExchangeSectionProperties getExchangeSection() {
        return new ExchangeSectionProperties(exchangeSection);
    }

    @JsonProperty("exchange")
    public void setExchangeSection(ExchangeSectionProperties exchangeSection) {
        this.exchangeSection = new ExchangeSectionProperties(exchangeSection);
    }

    @JsonIgnore
    public ExchangeProperties getExchange() {
        return exchangeSection.getActive();
    }

    @JsonIgnore
    public ProvidersProperties getProviders() {
        return exchangeSection.resolveProviders();
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public ConfigSchemaProperties getSchema() {
        return schema;
    }

    public void setSchema(ConfigSchemaProperties schema) {
        this.schema = schema;
    }

    public BotProperties getBot() {
        return bot;
    }

    public void setBot(BotProperties bot) {
        this.bot = bot;
    }

    // later you'll add risk, execution, strategies, portfolio, etc.
}
