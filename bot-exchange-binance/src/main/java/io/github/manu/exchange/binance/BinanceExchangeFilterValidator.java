package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class BinanceExchangeFilterValidator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    void validate(BinanceOrderCommand command, BinanceExchangeMetadata metadata) {
        List<String> errors = new ArrayList<>();
        BinanceExchangeMetadata.SymbolInfo symbol = symbol(command, metadata)
                .orElse(null);
        if (symbol == null) {
            throw new IllegalArgumentException("exchangeInfo has no symbol metadata for " + command.symbol());
        }

        validateSymbolMetadata(command, symbol, errors);
        validatePriceFilter(command, symbol, errors);
        validateLotSize(command, symbol, errors);
        validateNotional(command, symbol, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private Optional<BinanceExchangeMetadata.SymbolInfo> symbol(
            BinanceOrderCommand command,
            BinanceExchangeMetadata metadata
    ) {
        String symbol = command.symbol();
        return metadata.symbols().stream()
                .filter(candidate -> candidate.symbol().equalsIgnoreCase(symbol))
                .findFirst();
    }

    private void validateSymbolMetadata(
            BinanceOrderCommand command,
            BinanceExchangeMetadata.SymbolInfo symbol,
            List<String> errors
    ) {
        if (hasText(symbol.status()) && !"TRADING".equalsIgnoreCase(symbol.status())) {
            errors.add("symbol " + symbol.symbol() + " is not trading; exchangeInfo status is " + symbol.status());
        }
        if (!symbol.orderTypes().isEmpty()
                && hasText(command.type())
                && !containsIgnoreCase(symbol.orderTypes(), command.type())) {
            errors.add("order type " + command.type() + " is not allowed by exchangeInfo for " + symbol.symbol());
        }
        if (!symbol.timeInForce().isEmpty()
                && hasText(command.timeInForce())
                && !containsIgnoreCase(symbol.timeInForce(), command.timeInForce())) {
            errors.add("timeInForce " + command.timeInForce() + " is not allowed by exchangeInfo for " + symbol.symbol());
        }
    }

    private void validatePriceFilter(
            BinanceOrderCommand command,
            BinanceExchangeMetadata.SymbolInfo symbol,
            List<String> errors
    ) {
        filter(symbol, "PRICE_FILTER").ifPresent(filter -> {
            validatePriceValue("price", command.price(), filter, errors);
            validatePriceValue("stopPrice", command.stopPrice(), filter, errors);
        });
    }

    private void validatePriceValue(
            String field,
            BigDecimal value,
            BinanceExchangeMetadata.Filter filter,
            List<String> errors
    ) {
        if (value == null) {
            return;
        }
        validateRange(field, value, decimal(filter.minPrice()), decimal(filter.maxPrice()), errors);
        validateStep(field, value, decimal(filter.minPrice()), decimal(filter.tickSize()), errors);
    }

    private void validateLotSize(
            BinanceOrderCommand command,
            BinanceExchangeMetadata.SymbolInfo symbol,
            List<String> errors
    ) {
        Optional<BinanceExchangeMetadata.Filter> lotSize = filter(symbol, "LOT_SIZE");
        Optional<BinanceExchangeMetadata.Filter> marketLotSize = filter(symbol, "MARKET_LOT_SIZE");
        BinanceExchangeMetadata.Filter quantityFilter = isMarket(command)
                ? marketLotSize.or(() -> lotSize).orElse(null)
                : lotSize.orElse(null);
        if (quantityFilter != null) {
            validateQuantityValue("quantity", command.quantity(), quantityFilter, errors);
        }
        lotSize.ifPresent(filter -> validateQuantityValue("icebergQty", command.icebergQty(), filter, errors));
    }

    private void validateQuantityValue(
            String field,
            BigDecimal value,
            BinanceExchangeMetadata.Filter filter,
            List<String> errors
    ) {
        if (value == null) {
            return;
        }
        validateRange(field, value, decimal(filter.minQty()), decimal(filter.maxQty()), errors);
        validateStep(field, value, decimal(filter.minQty()), decimal(filter.stepSize()), errors);
    }

    private void validateNotional(
            BinanceOrderCommand command,
            BinanceExchangeMetadata.SymbolInfo symbol,
            List<String> errors
    ) {
        BigDecimal notional = notional(command);
        if (notional == null) {
            return;
        }
        filter(symbol, "MIN_NOTIONAL").ifPresent(filter -> {
            if (!isMarket(command) || !Boolean.FALSE.equals(filter.applyToMarket())) {
                validateMin("notional", notional, minNotional(filter), errors);
            }
        });
        filter(symbol, "NOTIONAL").ifPresent(filter -> {
            if (!isMarket(command) || !Boolean.FALSE.equals(filter.applyMinToMarket())) {
                validateMin("notional", notional, minNotional(filter), errors);
            }
            if (!isMarket(command) || !Boolean.FALSE.equals(filter.applyMaxToMarket())) {
                validateMax("notional", notional, decimal(filter.maxNotional()), errors);
            }
        });
    }

    private BigDecimal notional(BinanceOrderCommand command) {
        if (command.price() != null && command.quantity() != null) {
            return command.price().multiply(command.quantity());
        }
        return command.quoteOrderQty();
    }

    private BigDecimal minNotional(BinanceExchangeMetadata.Filter filter) {
        BigDecimal spotValue = decimal(filter.minNotional());
        return spotValue == null ? decimal(filter.notional()) : spotValue;
    }

    private void validateRange(
            String field,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            List<String> errors
    ) {
        validateMin(field, value, min, errors);
        validateMax(field, value, max, errors);
    }

    private void validateMin(String field, BigDecimal value, BigDecimal min, List<String> errors) {
        if (enabled(min) && value.compareTo(min) < 0) {
            errors.add(field + " " + value.toPlainString() + " is below exchangeInfo minimum " + min.toPlainString());
        }
    }

    private void validateMax(String field, BigDecimal value, BigDecimal max, List<String> errors) {
        if (enabled(max) && value.compareTo(max) > 0) {
            errors.add(field + " " + value.toPlainString() + " is above exchangeInfo maximum " + max.toPlainString());
        }
    }

    private void validateStep(
            String field,
            BigDecimal value,
            BigDecimal min,
            BigDecimal step,
            List<String> errors
    ) {
        if (!enabled(step)) {
            return;
        }
        BigDecimal base = enabled(min) ? min : ZERO;
        BigDecimal remainder = value.subtract(base).remainder(step).abs();
        if (remainder.compareTo(ZERO) != 0) {
            errors.add(field + " " + value.toPlainString()
                    + " does not align with exchangeInfo step " + step.toPlainString());
        }
    }

    private Optional<BinanceExchangeMetadata.Filter> filter(BinanceExchangeMetadata.SymbolInfo symbol, String type) {
        String normalizedType = type.toUpperCase(Locale.ROOT);
        return symbol.filters().stream()
                .filter(candidate -> normalizedType.equalsIgnoreCase(candidate.filterType()))
                .findFirst();
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(candidate));
    }

    private boolean isMarket(BinanceOrderCommand command) {
        return "MARKET".equalsIgnoreCase(command.type());
    }

    private BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return null;
        }
        return new BigDecimal(value);
    }

    private boolean enabled(BigDecimal value) {
        return value != null && value.compareTo(ZERO) != 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
