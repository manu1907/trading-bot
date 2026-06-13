package io.github.manu.execution;

import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exchange.ExchangeMetadata;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.InstrumentExchangeMetadata;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.events.v1.OrderCommandType;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class StrategyInstrumentUniverseResolver {

    private final ExchangeMetadataService exchangeMetadataService;
    private final Supplier<TradingBotProperties> configSupplier;

    public StrategyInstrumentUniverseResolver(ExchangeMetadataService exchangeMetadataService, ConfigManager configManager) {
        this(exchangeMetadataService, configManager == null ? () -> null : configManager::getConfig);
    }

    StrategyInstrumentUniverseResolver(
            ExchangeMetadataService exchangeMetadataService,
            Supplier<TradingBotProperties> configSupplier
    ) {
        this.exchangeMetadataService = Objects.requireNonNull(exchangeMetadataService, "exchangeMetadataService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public boolean eligible(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            OrderCommandType orderType
    ) {
        return eligibleSymbols(universe, provider, environment, account, market, orderType).contains(normalize(symbol));
    }

    public List<String> eligibleSymbols(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            OrderCommandType orderType
    ) {
        Objects.requireNonNull(universe, "universe");
        if (!universe.enabled()) {
            return List.of();
        }
        Optional<InstrumentExchangeMetadata> metadata =
                instrumentMetadata(universe, provider, environment, account, market);
        if (metadata.isEmpty()) {
            return universe.requireExchangeMetadata() ? List.of() : staticCandidateSymbols(universe);
        }
        List<String> eligible = metadata.get().instruments().stream()
                .filter(instrument -> eligibleInstrument(universe, instrument, orderType))
                .map(instrument -> normalize(instrument.symbol()))
                .filter(symbol -> !universe.excludedSymbols().contains(symbol))
                .filter(symbol -> !universe.requireIncludedSymbol() || universe.includedSymbols().contains(symbol))
                .distinct()
                .toList();
        if (universe.maxEligibleSymbols() == null || eligible.size() <= universe.maxEligibleSymbols().intValue()) {
            return eligible;
        }
        return List.copyOf(eligible.subList(0, universe.maxEligibleSymbols().intValue()));
    }

    public Map<String, BigDecimal> providerCapabilityScores(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            OrderCommandType orderType
    ) {
        Objects.requireNonNull(universe, "universe");
        if (!universe.enabled()) {
            return Map.of();
        }
        Optional<InstrumentExchangeMetadata> metadata =
                instrumentMetadata(universe, provider, environment, account, market, false);
        if (metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        metadata.get().instruments().stream()
                .filter(instrument -> eligibleInstrument(universe, instrument, orderType))
                .forEach(instrument -> {
                    String symbol = normalize(instrument.symbol());
                    if (symbol.isBlank() || universe.excludedSymbols().contains(symbol)) {
                        return;
                    }
                    if (universe.requireIncludedSymbol() && !universe.includedSymbols().contains(symbol)) {
                        return;
                    }
                    scores.putIfAbsent(symbol, providerCapabilityScore(
                            universe,
                            provider,
                            environment,
                            account,
                            market,
                            instrument,
                            orderType
                    ));
                });
        return Map.copyOf(scores);
    }

    private Optional<InstrumentExchangeMetadata> instrumentMetadata(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return instrumentMetadata(universe, provider, environment, account, market, true);
    }

    private Optional<InstrumentExchangeMetadata> instrumentMetadata(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            boolean refreshAllowed
    ) {
        if (refreshAllowed && universe.refreshExchangeMetadataBeforePlanning()) {
            refresh(provider, environment, account, market);
        }
        Optional<? extends ExchangeMetadata> metadata = exchangeMetadataService.current(provider);
        if (metadata.isPresent() && metadata.get() instanceof InstrumentExchangeMetadata instruments) {
            return Optional.of(instruments);
        }
        return Optional.empty();
    }

    private void refresh(String provider, String environment, String account, String market) {
        TradingBotProperties config = configSupplier.get();
        if (config == null) {
            return;
        }
        if (!matches(config, provider, environment, account, market)) {
            return;
        }
        exchangeMetadataService.refresh(ResolvedExchangeConfig.from(config));
    }

    private boolean matches(
            TradingBotProperties config,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return equalsIgnoreCase(config.getExchange().provider(), provider)
                && equalsIgnoreCase(config.getExchange().environment(), environment)
                && equalsIgnoreCase(config.getExchange().account(), account)
                && equalsIgnoreCase(config.getExchange().market(), market);
    }

    private boolean eligibleInstrument(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            InstrumentExchangeMetadata.Instrument instrument,
            OrderCommandType orderType
    ) {
        if (instrument.symbol() == null || instrument.symbol().isBlank()) {
            return false;
        }
        if (universe.requiredStatus() != null && !equalsIgnoreCase(universe.requiredStatus(), instrument.status())) {
            return false;
        }
        if (!universe.allowedQuoteAssets().isEmpty()
                && !universe.allowedQuoteAssets().contains(normalize(instrument.quoteAsset()))) {
            return false;
        }
        if (!universe.allowedContractTypes().isEmpty()
                && !universe.allowedContractTypes().contains(normalize(instrument.contractType()))) {
            return false;
        }
        String requiredOrderType = universe.requiredOrderType() == null
                ? orderType.name()
                : universe.requiredOrderType();
        return instrument.orderTypes().stream()
                .map(this::normalize)
                .anyMatch(requiredOrderType::equals);
    }

    private List<String> staticCandidateSymbols(ExecutionProperties.SignalPlanner.InstrumentUniverse universe) {
        if (universe.requireIncludedSymbol()) {
            return universe.includedSymbols().stream()
                    .filter(symbol -> !universe.excludedSymbols().contains(symbol))
                    .toList();
        }
        return List.of();
    }

    private BigDecimal providerCapabilityScore(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            InstrumentExchangeMetadata.Instrument instrument,
            OrderCommandType orderType
    ) {
        BigDecimal score = BigDecimal.ONE;
        if (equalsIgnoreCase(universe.requiredStatus(), instrument.status())) {
            score = score.add(BigDecimal.ONE);
        }
        String requiredOrderType = universe.requiredOrderType() == null
                ? orderType.name()
                : universe.requiredOrderType();
        if (supportsOrderType(instrument, requiredOrderType)) {
            score = score.add(BigDecimal.ONE);
        }
        if (!universe.allowedQuoteAssets().isEmpty()
                && universe.allowedQuoteAssets().contains(normalize(instrument.quoteAsset()))) {
            score = score.add(new BigDecimal("0.50"));
        }
        if (!universe.allowedContractTypes().isEmpty()
                && universe.allowedContractTypes().contains(normalize(instrument.contractType()))) {
            score = score.add(new BigDecimal("0.50"));
        }
        long orderTypeCount = instrument.orderTypes().stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        score = score.add(new BigDecimal("0.25").multiply(BigDecimal.valueOf(Math.min(orderTypeCount, 6L))));
        ExecutionProperties.SignalPlanner.SymbolPolicy policy =
                selectedSymbolPolicy(universe, provider, environment, account, market, normalize(instrument.symbol()));
        if (policy != null) {
            if (Boolean.TRUE.equals(policy.enabled())) {
                score = score.add(new BigDecimal("0.50"));
            }
            if (Boolean.TRUE.equals(policy.promotionReady())) {
                score = score.add(new BigDecimal("0.50"));
            }
        }
        return score.stripTrailingZeros();
    }

    private boolean supportsOrderType(InstrumentExchangeMetadata.Instrument instrument, String requiredOrderType) {
        return instrument.orderTypes().stream()
                .map(this::normalize)
                .anyMatch(requiredOrderType::equals);
    }

    private ExecutionProperties.SignalPlanner.SymbolPolicy selectedSymbolPolicy(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        String normalizedSymbol = normalize(symbol);
        return universe.symbolPolicies().stream()
                .filter(policy -> normalizedSymbol.equals(policy.symbol()))
                .filter(policy -> targetMatches(policy.provider(), provider))
                .filter(policy -> targetMatches(policy.environment(), environment))
                .filter(policy -> targetMatches(policy.account(), account))
                .filter(policy -> targetMatches(policy.market(), market))
                .findFirst()
                .orElse(null);
    }

    private boolean targetMatches(String configured, String actual) {
        return configured == null || configured.isBlank() || equalsIgnoreCase(configured, actual);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
