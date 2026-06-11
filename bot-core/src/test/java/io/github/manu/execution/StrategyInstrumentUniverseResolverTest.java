package io.github.manu.execution;

import io.github.manu.exchange.ExchangeMetadata;
import io.github.manu.exchange.ExchangeMetadataFetcher;
import io.github.manu.exchange.ExchangeMetadataService;
import io.github.manu.exchange.InstrumentExchangeMetadata;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.events.v1.OrderCommandType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyInstrumentUniverseResolverTest {

    @Test
    void resolves_exchange_polled_eligible_symbols_from_candidate_universe() {
        StrategyInstrumentUniverseResolver resolver = new StrategyInstrumentUniverseResolver(
                new ExchangeMetadataService(List.of(new StaticFetcher(new Metadata(List.of(
                        instrument("BTCUSDT", "TRADING", "USDT", "PERPETUAL", List.of("LIMIT", "MARKET")),
                        instrument("ETHUSDT", "TRADING", "USDT", "PERPETUAL", List.of("LIMIT")),
                        instrument("SOLUSDT", "BREAK", "USDT", "PERPETUAL", List.of("LIMIT")),
                        instrument("BTCBUSD", "TRADING", "BUSD", "PERPETUAL", List.of("LIMIT")),
                        instrument("BNBUSDT", "TRADING", "USDT", "CURRENT_QUARTER", List.of("LIMIT")),
                        instrument("XRPUSDT", "TRADING", "USDT", "PERPETUAL", List.of("MARKET"))
                ))))),
                (ConfigManager) null
        );
        ExecutionProperties.SignalPlanner.InstrumentUniverse universe =
                new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT"),
                        List.of("ETHUSDT"),
                        false,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        List.of("USDT"),
                        List.of("PERPETUAL"),
                        2,
                        List.of()
                );

        List<String> eligible = resolver.eligibleSymbols(
                universe,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                OrderCommandType.LIMIT
        );

        assertThat(eligible).containsExactly("BTCUSDT");
        assertThat(resolver.eligible(
                universe,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                OrderCommandType.LIMIT
        )).isTrue();
        assertThat(resolver.eligible(
                universe,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "XRPUSDT",
                OrderCommandType.LIMIT
        )).isFalse();
    }

    @Test
    void returns_no_symbols_when_metadata_is_required_but_unavailable() {
        StrategyInstrumentUniverseResolver resolver = new StrategyInstrumentUniverseResolver(
                new ExchangeMetadataService(List.of(new EmptyFetcher())),
                (ConfigManager) null
        );
        ExecutionProperties.SignalPlanner.InstrumentUniverse universe =
                new ExecutionProperties.SignalPlanner.InstrumentUniverse(
                        true,
                        List.of("BTCUSDT"),
                        List.of(),
                        false,
                        true,
                        true,
                        true,
                        false,
                        "TRADING",
                        null,
                        List.of("USDT"),
                        List.of("PERPETUAL"),
                        null,
                        List.of()
                );

        assertThat(resolver.eligibleSymbols(
                universe,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                OrderCommandType.LIMIT
        )).isEmpty();
    }

    private InstrumentExchangeMetadata.Instrument instrument(
            String symbol,
            String status,
            String quoteAsset,
            String contractType,
            List<String> orderTypes
    ) {
        return new InstrumentExchangeMetadata.Instrument(symbol, status, symbol.replace(quoteAsset, ""), quoteAsset, contractType, orderTypes);
    }

    private record Metadata(List<InstrumentExchangeMetadata.Instrument> instruments) implements InstrumentExchangeMetadata {

        private Metadata {
            instruments = List.copyOf(instruments);
        }

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Instant fetchedAt() {
            return Instant.parse("2026-06-11T00:00:00Z");
        }
    }

    private record StaticFetcher(Metadata metadata) implements ExchangeMetadataFetcher {

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Optional<? extends ExchangeMetadata> current() {
            return Optional.of(metadata);
        }

        @Override
        public Optional<? extends ExchangeMetadata> refresh(ResolvedExchangeConfig config) {
            return current();
        }
    }

    private static final class EmptyFetcher implements ExchangeMetadataFetcher {

        @Override
        public String provider() {
            return "binance";
        }

        @Override
        public Optional<? extends ExchangeMetadata> current() {
            return Optional.empty();
        }

        @Override
        public Optional<? extends ExchangeMetadata> refresh(ResolvedExchangeConfig config) {
            return Optional.empty();
        }
    }
}
