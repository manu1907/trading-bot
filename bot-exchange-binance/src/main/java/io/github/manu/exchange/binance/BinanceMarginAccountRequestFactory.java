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

    BinanceSignedRequest transferHistory(BinanceMarginTransferHistoryQuery query, String privateCredential) {
        BinanceMarginTransferHistoryQuery safeQuery = query == null
                ? new BinanceMarginTransferHistoryQuery(null, null, null, null, null, null, null)
                : query;
        validateTransferHistory(safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "asset", safeQuery.asset());
        add(parameters, "type", safeQuery.type());
        add(parameters, "startTime", safeQuery.startTime());
        add(parameters, "endTime", safeQuery.endTime());
        add(parameters, "current", safeQuery.current());
        add(parameters, "size", safeQuery.size());
        add(parameters, "isolatedSymbol", safeQuery.isolatedSymbol());
        return restRequestFactory.signedUri(account.transferHistoryPath(), parameters, privateCredential);
    }

    BinanceSignedRequest maxTransferable(String asset, String isolatedSymbol, String privateCredential) {
        requireText("asset", asset);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "asset", asset);
        add(parameters, "isolatedSymbol", isolatedSymbol);
        return restRequestFactory.signedUri(account.maxTransferablePath(), parameters, privateCredential);
    }

    BinanceSignedRequest crossAccount(String privateCredential) {
        return restRequestFactory.signedUri(account.crossAccountPath(), List.of(), privateCredential);
    }

    BinanceSignedRequest isolatedAccount(BinanceIsolatedMarginAccountQuery query, String privateCredential) {
        BinanceIsolatedMarginAccountQuery safeQuery = query == null
                ? new BinanceIsolatedMarginAccountQuery(List.of())
                : query;
        validateIsolatedAccountQuery(safeQuery);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        if (!safeQuery.symbols().isEmpty()) {
            add(parameters, "symbols", String.join(",", safeQuery.symbols()));
        }
        return restRequestFactory.signedUri(account.isolatedAccountPath(), parameters, privateCredential);
    }

    BinanceSignedRequest isolatedAccountLimit(String privateCredential) {
        return restRequestFactory.signedUri(account.isolatedAccountLimitPath(), List.of(), privateCredential);
    }

    BinanceSignedRequest tradeCoeff(String privateCredential) {
        return restRequestFactory.signedUri(account.tradeCoeffPath(), List.of(), privateCredential);
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

    private void validateTransferHistory(BinanceMarginTransferHistoryQuery query) {
        requireOneOfOptional("type", query.type(), account.supportedTransferHistoryTypes());
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requirePositive("current", query.current());
        requirePositive("size", query.size());
        if (query.size() != null && account.maxTransferHistorySize() != null
                && query.size() > account.maxTransferHistorySize()) {
            throw new IllegalArgumentException("size must be at most " + account.maxTransferHistorySize());
        }
        requireOrderedTimes(query.startTime(), query.endTime());
        requireMaxInterval(query.startTime(), query.endTime());
    }

    private void validateIsolatedAccountQuery(BinanceIsolatedMarginAccountQuery query) {
        for (String symbol : query.symbols()) {
            requireText("symbols", symbol);
        }
        if (account.maxIsolatedAccountSymbols() != null
                && query.symbols().size() > account.maxIsolatedAccountSymbols()) {
            throw new IllegalArgumentException("symbols size must be at most " + account.maxIsolatedAccountSymbols());
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

    private void requireOneOfOptional(String field, String value, List<String> allowed) {
        if (hasText(value) && !allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
    }

    private void requirePositive(String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void requirePositive(String field, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive when configured");
        }
    }

    private void requireOrderedTimes(Long startTime, Long endTime) {
        if (startTime != null && endTime != null && startTime > endTime) {
            throw new IllegalArgumentException("startTime must be less than or equal to endTime");
        }
    }

    private void requireMaxInterval(Long startTime, Long endTime) {
        if (startTime == null || endTime == null || account.maxTransferHistoryDays() == null) {
            return;
        }
        long maxIntervalMillis = account.maxTransferHistoryDays() * 24L * 60L * 60L * 1000L;
        if (endTime - startTime > maxIntervalMillis) {
            throw new IllegalArgumentException("startTime and endTime interval must be at most "
                    + account.maxTransferHistoryDays() + " days");
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

    private void add(List<BinanceRequestParameter> parameters, String name, Long value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, Long.toString(value)));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
