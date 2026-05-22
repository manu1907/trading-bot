package io.github.manu.config;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exception.ConfigValidationException;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ExchangeModuleFactory;
import io.github.manu.exchange.ResolvedExchangeConfig;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ConfigValidator {

    private static final String SUPPORTED_SCHEMA_ID = "io.github.manu.trading-bot.config";
    private static final Integer SUPPORTED_CONFIG_VERSION = 1;
    private static final String SUPPORTED_MIGRATION_POLICY = "fail_fast";

    private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private final Validator beanValidator = validatorFactory.getValidator();
    private final ExchangeModuleFactory moduleFactory;

    public ConfigValidator(ExchangeModuleFactory moduleFactory) {
        this.moduleFactory = moduleFactory;
    }

    /// Validates the full configuration after merging.
    /// Throws ConfigValidationException if any rule is violated.
    ///
    /// This method is called both at startup and during runtime reloads.
    public void validate(TradingBotProperties config) {
        Set<ConstraintViolation<TradingBotProperties>> violations = beanValidator.validate(config);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new ConfigValidationException(msg);
        }

        crossValidate(config);
    }

    @PreDestroy
    void close() {
        validatorFactory.close();
    }

    private void crossValidate(TradingBotProperties config) {
        if (!SUPPORTED_SCHEMA_ID.equals(config.getSchema().id())) {
            throw new ConfigValidationException(
                    "Unsupported config schema '%s'; expected '%s'"
                            .formatted(config.getSchema().id(), SUPPORTED_SCHEMA_ID)
            );
        }

        if (!SUPPORTED_CONFIG_VERSION.equals(config.getSchema().version())) {
            throw new ConfigValidationException(
                    "Unsupported schema version %s; expected %s"
                            .formatted(config.getSchema().version(), SUPPORTED_CONFIG_VERSION)
            );
        }

        if (!SUPPORTED_MIGRATION_POLICY.equals(config.getSchema().migrationPolicy())) {
            throw new ConfigValidationException(
                    "Unsupported migration policy '%s'; expected '%s'"
                            .formatted(config.getSchema().migrationPolicy(), SUPPORTED_MIGRATION_POLICY)
            );
        }

        if (!SUPPORTED_CONFIG_VERSION.equals(config.getVersion())) {
            throw new ConfigValidationException(
                    "Unsupported config version %s; expected %s"
                            .formatted(config.getVersion(), SUPPORTED_CONFIG_VERSION)
            );
        }

        ExchangeProperties active = config.getExchange();
        ExchangeModule module = resolveModule(active.provider());
        JsonNode providerNode = resolveActiveProviderConfig(config, active.provider());
        validateActiveProviderPath(active, providerNode);

        try {
            module.validateConfig(ResolvedExchangeConfig.from(config));
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(
                    "Invalid provider config for exchange '%s': %s".formatted(active.provider(), e.getMessage())
            );
        }
    }

    private ExchangeModule resolveModule(String exchangeName) {
        try {
            return moduleFactory.get(exchangeName);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException("No module registered for exchange '%s'".formatted(exchangeName));
        }
    }

    private JsonNode resolveActiveProviderConfig(TradingBotProperties config, String provider) {
        try {
            return config.getProviders().requiredActive(provider);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(e.getMessage());
        }
    }

    private void validateActiveProviderPath(ExchangeProperties active, JsonNode providerNode) {
        requireEnabled(providerNode, "exchange.providers." + active.provider());
        JsonNode environmentNode = requireObject(
                requireObject(providerNode, "environments", "exchange.providers." + active.provider()),
                active.environment(),
                "exchange.providers." + active.provider() + ".environments"
        );
        requireEnabled(environmentNode, "exchange.providers.%s.environments.%s"
                .formatted(active.provider(), active.environment()));

        JsonNode accountNode = requireObject(
                requireObject(environmentNode, "accounts", "exchange.providers.%s.environments.%s"
                        .formatted(active.provider(), active.environment())),
                active.account(),
                "exchange.providers.%s.environments.%s.accounts"
                        .formatted(active.provider(), active.environment())
        );
        requireEnabled(accountNode, "exchange.providers.%s.environments.%s.accounts.%s"
                .formatted(active.provider(), active.environment(), active.account()));

        JsonNode marketNode = requireObject(
                requireObject(accountNode, "markets", "exchange.providers.%s.environments.%s.accounts.%s"
                        .formatted(active.provider(), active.environment(), active.account())),
                active.market(),
                "exchange.providers.%s.environments.%s.accounts.%s.markets"
                        .formatted(active.provider(), active.environment(), active.account())
        );
        requireEnabled(marketNode, "exchange.providers.%s.environments.%s.accounts.%s.markets.%s"
                .formatted(active.provider(), active.environment(), active.account(), active.market()));
    }

    private JsonNode requireObject(JsonNode parent, String fieldName, String parentPath) {
        JsonNode node = parent.path(fieldName);
        if (!node.isObject()) {
            throw new ConfigValidationException("Missing object config path: " + parentPath + "." + fieldName);
        }
        return node;
    }

    private void requireEnabled(JsonNode node, String path) {
        JsonNode enabled = node.path("enabled");
        if (!enabled.isBoolean()) {
            throw new ConfigValidationException("Missing boolean config path: " + path + ".enabled");
        }
        if (!enabled.asBoolean()) {
            throw new ConfigValidationException("Active config path is disabled: " + path);
        }
    }
}
