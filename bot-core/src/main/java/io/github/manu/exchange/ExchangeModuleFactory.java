package io.github.manu.exchange;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExchangeModuleFactory {
    private final Map<String, ExchangeModule> modules = new HashMap<>();

    public ExchangeModuleFactory(List<ExchangeModule> moduleList) {
        for (ExchangeModule m : moduleList) {
            modules.put(m.provider().toLowerCase(), m);
        }
    }

    public ExchangeModule get(String name) {
        ExchangeModule mod = modules.get(name.toLowerCase());
        if (mod == null) {
            throw new IllegalArgumentException("No module registered for exchange: " + name);
        }
        return mod;
    }
}
