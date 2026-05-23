package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventTopicCatalogTest {

    @Test
    void defines_primary_and_dead_letter_topics_for_every_event_type() {
        assertThat(TradingEventTopicCatalog.allTopics())
                .hasSize(TradingEventType.values().length * 2)
                .extracting(TradingEventTopicSpec::name)
                .contains(
                        "trading.v1.market-data",
                        "trading.v1.market-data.dlq",
                        "trading.v1.order-command",
                        "trading.v1.order-command.dlq"
                );
    }

    @Test
    void uses_more_partitions_for_market_data_than_dead_letter_topics() {
        assertThat(TradingEventTopicCatalog.topic(TradingEventType.MARKET_DATA).partitions()).isEqualTo(12);
        assertThat(TradingEventTopicCatalog.deadLetterTopic(TradingEventType.MARKET_DATA).partitions()).isOne();
    }
}
