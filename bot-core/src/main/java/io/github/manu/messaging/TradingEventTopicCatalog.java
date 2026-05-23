package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.Arrays;
import java.util.List;

public final class TradingEventTopicCatalog {

    private TradingEventTopicCatalog() {
    }

    public static List<TradingEventTopicSpec> allTopics() {
        return Arrays.stream(TradingEventType.values())
                .flatMap(type -> List.of(topic(type), deadLetterTopic(type)).stream())
                .toList();
    }

    public static TradingEventTopicSpec topic(TradingEventType type) {
        return new TradingEventTopicSpec(type.route().topic(), partitions(type));
    }

    public static TradingEventTopicSpec deadLetterTopic(TradingEventType type) {
        return new TradingEventTopicSpec(type.route().deadLetterTopic(), 1);
    }

    private static int partitions(TradingEventType type) {
        return switch (type) {
            case MARKET_DATA -> 12;
            case CONFIG_CHANGE -> 3;
            default -> 6;
        };
    }
}
