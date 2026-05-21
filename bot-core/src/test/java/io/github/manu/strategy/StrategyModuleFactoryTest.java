package io.github.manu.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyModuleFactoryTest {

    @Test
    void resolves_modules_case_insensitively_by_id() {
        StrategyModule lfa = () -> "lfa";
        StrategyModuleFactory factory = new StrategyModuleFactory(List.of(lfa));

        assertThat(factory.get("LFA")).isSameAs(lfa);
    }

    @Test
    void rejects_unknown_strategy() {
        StrategyModuleFactory factory = new StrategyModuleFactory(List.of(() -> "lfa"));

        assertThatThrownBy(() -> factory.get("rsi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No strategy module registered: rsi");
    }
}
