package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class RedpandaTradingEventBusTest {

    @Test
    void publishes_typed_event_envelopes_through_the_primary_publisher() {
        CapturingTradingEventPublisher publisher = new CapturingTradingEventPublisher();
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        RedpandaTradingEventBus bus = new RedpandaTradingEventBus(publisher, deadLetterPublisher);
        TradingEventEnvelope<StrategySignalEvent> envelope = strategySignalEnvelope();

        PublishedTradingEvent published = bus.publish(envelope).join();

        assertThat(publisher.envelope()).isSameAs(envelope);
        assertThat(published.eventType()).isEqualTo(TradingEventType.STRATEGY_SIGNAL);
        assertThat(published.topic()).isEqualTo(TradingEventType.STRATEGY_SIGNAL.route().topic());
        assertThat(deadLetterPublisher.event()).isNull();
    }

    @Test
    void publishes_dead_letter_events_through_the_dead_letter_publisher() {
        CapturingTradingEventPublisher publisher = new CapturingTradingEventPublisher();
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        RedpandaTradingEventBus bus = new RedpandaTradingEventBus(publisher, deadLetterPublisher);
        DeadLetterTradingEvent event = new DeadLetterTradingEvent(
                TradingEventType.STRATEGY_SIGNAL,
                TradingEventType.STRATEGY_SIGNAL.route(),
                new byte[] { 1 },
                new byte[] { 2 },
                "handler failure",
                Instant.parse("2026-05-23T12:00:00Z")
        );

        PublishedTradingEvent published = bus.publishDeadLetter(event).join();

        assertThat(deadLetterPublisher.event()).isSameAs(event);
        assertThat(published.topic()).isEqualTo(TradingEventType.STRATEGY_SIGNAL.route().deadLetterTopic());
        assertThat(publisher.envelope()).isNull();
    }

    private static TradingEventEnvelope<StrategySignalEvent> strategySignalEnvelope() {
        TradingEventKey key = TradingEventKeys.strategy(TradingEventType.STRATEGY_SIGNAL, "lfa");
        StrategySignalEvent signal = StrategySignalEvent.newBuilder()
                .setEventId("evt-signal")
                .setSchemaVersion(1)
                .setSignalId("sig-001")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setSymbol("BTCUSDT")
                .setSignalType(StrategySignalType.ENTER_LONG)
                .setConfidence(0.82)
                .setTargetQuantity("0.001")
                .setTargetNotional("50.00")
                .setLimitPrice("50000.00")
                .setStopPrice(null)
                .setEmittedAtMicros(Instant.parse("2026-05-23T11:30:00Z"))
                .setFeatures(Map.of("fragility", "0.82"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.STRATEGY_SIGNAL, key, signal);
    }

    private static final class CapturingTradingEventPublisher implements TradingEventPublisher {

        private TradingEventEnvelope<? extends SpecificRecord> envelope;

        @Override
        public CompletableFuture<PublishedTradingEvent> publishAsync(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            this.envelope = envelope;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    7
            ));
        }

        private TradingEventEnvelope<? extends SpecificRecord> envelope() {
            return envelope;
        }
    }

    private static final class CapturingDeadLetterPublisher implements DeadLetterPublisher {

        private DeadLetterTradingEvent event;

        @Override
        public CompletableFuture<PublishedTradingEvent> publishAsync(DeadLetterTradingEvent event) {
            this.event = event;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    event.eventType(),
                    event.route().deadLetterTopic(),
                    0,
                    9
            ));
        }

        private DeadLetterTradingEvent event() {
            return event;
        }
    }
}
