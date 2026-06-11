package io.github.manu.execution;

import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.exchange.ExchangeMetadata;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.InstrumentExchangeMetadata;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.events.v1.OrderCommandType;

import java.util.List;
import java.util.Locale;
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

    private Optional<InstrumentExchangeMetadata> instrumentMetadata(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market
    ) {
        if (universe.refreshExchangeMetadataBeforePlanning()) {
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

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
