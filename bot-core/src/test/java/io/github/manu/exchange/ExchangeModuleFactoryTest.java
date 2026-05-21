package io.github.manu.exchange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeModuleFactoryTest {

    @Test
    void resolves_modules_case_insensitively_by_provider() {
        ExchangeModule binance = new StubExchangeModule("binance");
        ExchangeModuleFactory factory = new ExchangeModuleFactory(List.of(binance));

        assertThat(factory.get("BINANCE")).isSameAs(binance);
    }

    @Test
    void rejects_unknown_provider() {
        ExchangeModuleFactory factory = new ExchangeModuleFactory(List.of(new StubExchangeModule("binance")));

        assertThatThrownBy(() -> factory.get("kucoin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No module registered for exchange: kucoin");
    }

    private record StubExchangeModule(String provider) implements ExchangeModule {

        @Override
        public void configure(ResolvedExchangeConfig config) {
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
