package io.github.manu.strategy.lfa;

import io.github.manu.strategy.StrategyModule;
import org.springframework.stereotype.Component;

@Component
public class LfaStrategyModule implements StrategyModule {

    @Override
    public String id() {
        return "lfa";
    }
}
