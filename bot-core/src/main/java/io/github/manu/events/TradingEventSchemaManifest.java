package io.github.manu.events;

import java.util.Arrays;
import java.util.List;

public final class TradingEventSchemaManifest {

    private TradingEventSchemaManifest() {
    }

    public static List<TradingEventSchemaDescriptor> descriptors() {
        return Arrays.stream(TradingEventType.values())
                .map(TradingEventSchemaDescriptor::from)
                .toList();
    }
}
