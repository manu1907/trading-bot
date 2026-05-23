package io.github.manu.events;

public record TradingEventRoute(String topic, String keySubject, String valueSubject, String deadLetterTopic) {

    public TradingEventRoute {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (keySubject == null || keySubject.isBlank()) {
            throw new IllegalArgumentException("keySubject must not be blank");
        }
        if (valueSubject == null || valueSubject.isBlank()) {
            throw new IllegalArgumentException("valueSubject must not be blank");
        }
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            throw new IllegalArgumentException("deadLetterTopic must not be blank");
        }
    }
}
