package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceMarginAccountRequestFactory {

    private final BinanceProperties.MarginAccount account;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceMarginAccountRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        Objects.requireNonNull(binance, "binance");
        if (binance.marginAccount() == null) {
            throw new IllegalArgumentException("Binance margin account config is required");
        }
        account = binance.marginAccount();
        restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest borrowRepay(BinanceMarginBorrowRepayCommand command, String privateCredential) {
        validateBorrowRepay(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "asset", command.asset());
        add(parameters, "isIsolated", command.isolated() ? "TRUE" : "FALSE");
        add(parameters, "symbol", command.symbol());
        add(parameters, "amount", command.amount());
        add(parameters, "type", command.type());
        return restRequestFactory.signedUri(account.borrowRepayPath(), parameters, privateCredential);
    }

    private void validateBorrowRepay(BinanceMarginBorrowRepayCommand command) {
        Objects.requireNonNull(command, "command");
        requireText("asset", command.asset());
        requirePositive("amount", command.amount());
        requireOneOf("type", command.type(), account.supportedBorrowRepayTypes());
        if (command.isolated()) {
            requireText("symbol", command.symbol());
        } else if (hasText(command.symbol())) {
            throw new IllegalArgumentException("symbol is only supported for isolated margin borrow/repay");
        }
    }

    private void requireText(String field, String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private void requireOneOf(String field, String value, List<String> allowed) {
        requireText(field, value);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
    }

    private void requirePositive(String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, String value) {
        if (hasText(value)) {
            parameters.add(BinanceRequestParameter.of(name, value));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, BigDecimal value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.stripTrailingZeros().toPlainString()));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
