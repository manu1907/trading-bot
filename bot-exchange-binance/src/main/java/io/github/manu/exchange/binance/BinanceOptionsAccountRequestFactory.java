package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceOptionsAccountRequestFactory {

    private final BinanceProperties.OptionsAccount account;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceOptionsAccountRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        Objects.requireNonNull(binance, "binance");
        if (binance.optionsAccount() == null) {
            throw new IllegalArgumentException("Binance options account config is required");
        }
        account = binance.optionsAccount();
        restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest marginAccount(String privateCredential) {
        return restRequestFactory.signedUri(account.marginAccountPath(), List.of(), privateCredential);
    }

    BinanceSignedRequest positions(String symbol, String privateCredential) {
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", symbol);
        return restRequestFactory.signedUri(account.positionPath(), parameters, privateCredential);
    }

    BinanceSignedRequest marketMakerProtection(String underlying, String privateCredential) {
        requireText("underlying", underlying);
        return restRequestFactory.signedUri(account.marketMakerProtectionPath(), List.of(
                BinanceRequestParameter.of("underlying", underlying)
        ), privateCredential);
    }

    BinanceSignedRequest setMarketMakerProtection(BinanceOptionsMmpConfigCommand command, String privateCredential) {
        requireMmpMutationsEnabled();
        validateMarketMakerProtection(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "underlying", command.underlying());
        add(parameters, "windowTimeInMilliseconds", command.windowTimeInMilliseconds());
        add(parameters, "frozenTimeInMilliseconds", command.frozenTimeInMilliseconds());
        add(parameters, "qtyLimit", command.quantityLimit());
        add(parameters, "deltaLimit", command.deltaLimit());
        return restRequestFactory.signedUri(account.marketMakerProtectionSetPath(), parameters, privateCredential);
    }

    BinanceSignedRequest resetMarketMakerProtection(String underlying, String privateCredential) {
        requireMmpMutationsEnabled();
        requireText("underlying", underlying);
        return restRequestFactory.signedUri(account.marketMakerProtectionResetPath(), List.of(
                BinanceRequestParameter.of("underlying", underlying)
        ), privateCredential);
    }

    private void validateMarketMakerProtection(BinanceOptionsMmpConfigCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Binance options MMP config command is required");
        }
        requireText("underlying", command.underlying());
        requirePositive("windowTimeInMilliseconds", command.windowTimeInMilliseconds());
        if (command.windowTimeInMilliseconds() > account.maxMarketMakerProtectionWindowMillis()) {
            throw new IllegalArgumentException("windowTimeInMilliseconds must be less than or equal to "
                    + account.maxMarketMakerProtectionWindowMillis());
        }
        requireNonNegative("frozenTimeInMilliseconds", command.frozenTimeInMilliseconds());
        requirePositive("qtyLimit", command.quantityLimit());
        requirePositive("deltaLimit", command.deltaLimit());
    }

    private void requireMmpMutationsEnabled() {
        if (!account.marketMakerProtectionMutationsEnabled()) {
            throw new IllegalArgumentException("Binance options MMP mutations are disabled by config");
        }
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private void requirePositive(String field, Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void requireNonNegative(String field, Long value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(field + " must be zero or positive");
        }
    }

    private void requirePositive(String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.add(BinanceRequestParameter.of(name, value));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, Long value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, BigDecimal value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.stripTrailingZeros().toPlainString()));
        }
    }
}
