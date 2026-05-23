package io.github.manu.messaging;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TradingEventTopicAdmin implements AutoCloseable {

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);

    private final AdminClient adminClient;
    private final short replicationFactor;

    public TradingEventTopicAdmin(String bootstrapServers, short replicationFactor) {
        this(AdminClient.create(adminProperties(bootstrapServers)), replicationFactor);
    }

    TradingEventTopicAdmin(AdminClient adminClient, short replicationFactor) {
        this.adminClient = Objects.requireNonNull(adminClient, "adminClient");
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor must be positive");
        }
        this.replicationFactor = replicationFactor;
    }

    public void createMissingTopics(Collection<TradingEventTopicSpec> topics) {
        Objects.requireNonNull(topics, "topics");
        if (topics.isEmpty()) {
            return;
        }

        try {
            Collection<String> existing = adminClient.listTopics()
                    .names()
                    .get(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            List<NewTopic> missing = topics.stream()
                    .filter(topic -> !existing.contains(topic.name()))
                    .map(topic -> new NewTopic(topic.name(), topic.partitions(), replicationFactor))
                    .toList();
            if (!missing.isEmpty()) {
                adminClient.createTopics(missing).all().get(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while creating Redpanda topics", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new MessagingException("Failed to create Redpanda topics", ex);
        }
    }

    @Override
    public void close() {
        adminClient.close();
    }

    private static Properties adminProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", requireText(bootstrapServers, "bootstrapServers"));
        return properties;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
