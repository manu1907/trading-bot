package io.github.manu.messaging;

import java.util.Objects;

public final class TradingEventReplayConsumerFactory {

    private final String bootstrapServers;
    private final String clientIdPrefix;
    private final SchemaRegistryTradingEventCodec codec;

    public TradingEventReplayConsumerFactory(
            String bootstrapServers,
            String clientIdPrefix,
            SchemaRegistryTradingEventCodec codec
    ) {
        this.bootstrapServers = requireText(bootstrapServers, "bootstrapServers");
        this.clientIdPrefix = requireText(clientIdPrefix, "clientIdPrefix");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public KafkaTradingEventReplayConsumer create(String groupIdSuffix) {
        return new KafkaTradingEventReplayConsumer(
                bootstrapServers,
                clientIdPrefix + "-" + requireText(groupIdSuffix, "groupIdSuffix"),
                codec
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
