package io.github.manu.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "trading.messaging")
public record MessagingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("localhost:19092") String bootstrapServers,
        @DefaultValue("http://localhost:18081") String schemaRegistryUrl,
        @DefaultValue("trading-bot") String clientIdPrefix,
        @DefaultValue Topics topics,
        @DefaultValue Consumers consumers
) {

    public MessagingProperties {
        requireText(bootstrapServers, "bootstrapServers");
        requireText(schemaRegistryUrl, "schemaRegistryUrl");
        requireText(clientIdPrefix, "clientIdPrefix");
        if (topics == null) {
            topics = new Topics(false, (short) 1);
        }
        if (consumers == null) {
            consumers = new Consumers(false, "dispatcher", 250);
        }
    }

    public record Topics(
            @DefaultValue("false") boolean autoCreate,
            @DefaultValue("1") short replicationFactor
    ) {

        public Topics {
            if (replicationFactor <= 0) {
                throw new IllegalArgumentException("replicationFactor must be positive");
            }
        }
    }

    public record Consumers(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("dispatcher") String groupIdSuffix,
            @DefaultValue("250") int pollTimeoutMillis
    ) {

        public Consumers {
            requireText(groupIdSuffix, "groupIdSuffix");
            if (pollTimeoutMillis <= 0) {
                throw new IllegalArgumentException("pollTimeoutMillis must be positive");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
