package io.github.manu.strategy;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyModuleFactory {

    private final Map<String, StrategyModule> modules = new HashMap<>();

    public StrategyModuleFactory(List<StrategyModule> moduleList) {
        for (StrategyModule module : moduleList) {
            modules.put(module.id().toLowerCase(), module);
        }
    }

    public StrategyModule get(String id) {
        StrategyModule module = modules.get(id.toLowerCase());
        if (module == null) {
            throw new IllegalArgumentException("No strategy module registered: " + id);
        }
        return module;
    }
}
