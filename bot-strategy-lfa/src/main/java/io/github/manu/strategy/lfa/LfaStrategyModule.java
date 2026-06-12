package io.github.manu.strategy.lfa;

import io.github.manu.strategy.StrategyModule;
import org.springframework.stereotype.Component;

@Component
public class LfaStrategyModule implements StrategyModule {

    private final LfaMarketSignalAnalyzer analyzer;

    public LfaStrategyModule(LfaMarketSignalAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public String id() {
        return "lfa";
    }

    public LfaMarketSignalAnalyzer analyzer() {
        return analyzer;
    }
}
