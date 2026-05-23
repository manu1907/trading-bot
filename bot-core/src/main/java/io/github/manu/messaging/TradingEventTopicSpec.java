package io.github.manu.messaging;

public record TradingEventTopicSpec(String name, int partitions) {

    public TradingEventTopicSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitions must be positive");
        }
    }
}
