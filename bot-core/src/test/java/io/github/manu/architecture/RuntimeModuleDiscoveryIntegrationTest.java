package io.github.manu.architecture;

import io.github.manu.exchange.ExchangeModuleFactory;
import io.github.manu.http.WebClientConfiguration;
import io.github.manu.strategy.StrategyModuleFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = RuntimeModuleDiscoveryIntegrationTest.ModuleDiscoveryTestConfiguration.class)
class RuntimeModuleDiscoveryIntegrationTest {

    @Autowired
    private ExchangeModuleFactory exchangeModuleFactory;

    @Autowired
    private StrategyModuleFactory strategyModuleFactory;

    @Test
    void discovers_provider_and_strategy_modules_from_runtime_modules() {
        assertThat(exchangeModuleFactory.get("binance").provider()).isEqualTo("binance");
        assertThat(strategyModuleFactory.get("lfa").id()).isEqualTo("lfa");
    }

    @Configuration
    @Import({
            ExchangeModuleFactory.class,
            StrategyModuleFactory.class,
            WebClientConfiguration.class
    })
    @ComponentScan(basePackages = {
            "io.github.manu.exchange.binance",
            "io.github.manu.strategy.lfa"
    })
    static class ModuleDiscoveryTestConfiguration {
    }
}
