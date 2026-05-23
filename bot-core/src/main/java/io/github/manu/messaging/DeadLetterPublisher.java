package io.github.manu.messaging;

import java.util.concurrent.CompletableFuture;

public interface DeadLetterPublisher {

    CompletableFuture<PublishedTradingEvent> publishAsync(DeadLetterTradingEvent event);
}
