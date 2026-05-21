package io.github.manu.config;

import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.exception.ConfigValidationException;
import io.github.manu.exchange.ExchangeModuleFactory;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ConfigValidator {

    private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private final Validator beanValidator = validatorFactory.getValidator();
    private final ExchangeModuleFactory moduleFactory;  // for checking module existence

    public ConfigValidator(ExchangeModuleFactory moduleFactory) {
        this.moduleFactory = moduleFactory;
    }

    /// Validates the full configuration after merging.
    /// Throws ConfigValidationException if any rule is violated.
    ///
    /// This method is called both at startup and during runtime reloads.
    public void validate(TradingBotProperties config) {
        // 1. Bean validation (field constraints from annotations)
        Set<ConstraintViolation<TradingBotProperties>> violations = beanValidator.validate(config);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new ConfigValidationException(msg);
        }

        // 2. Custom cross‑field rules
        crossValidate(config);
    }

    @PreDestroy
    void close() {
        validatorFactory.close();
    }

    private void crossValidate(TradingBotProperties config) {
        String exchangeName = config.getExchange().provider();
        try {
            moduleFactory.get(exchangeName);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException("No module registered for exchange '%s'".formatted(exchangeName));
        }
    }
}
