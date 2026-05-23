package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournaledTradingEventBusTest {

    @Test
    void journals_typed_event_before_delegating_publish() {
        CapturingTradingEventBus delegate = new CapturingTradingEventBus();
        CapturingTradingEventJournal journal = new CapturingTradingEventJournal();
        JournaledTradingEventBus bus = new JournaledTradingEventBus(delegate, journal);
        TradingEventEnvelope<StrategySignalEvent> envelope = strategySignalEnvelope();

        PublishedTradingEvent published = bus.publish(envelope).join();

        assertThat(published.eventType()).isEqualTo(TradingEventType.STRATEGY_SIGNAL);
        assertThat(delegate.envelope()).isSameAs(envelope);
        assertThat(journal.events()).hasSize(1);
        assertSerializedEvent(journal.events().getFirst().event(), new TradingEventMessageCodec().serialize(envelope));
        assertThat(journal.appendOrder()).isEqualTo(1);
        assertThat(delegate.publishOrder()).isEqualTo(2);
    }

    @Test
    void does_not_delegate_when_journal_append_fails() {
        CapturingTradingEventBus delegate = new CapturingTradingEventBus();
        FailingTradingEventJournal journal = new FailingTradingEventJournal();
        JournaledTradingEventBus bus = new JournaledTradingEventBus(delegate, journal);
        TradingEventEnvelope<StrategySignalEvent> envelope = strategySignalEnvelope();

        CompletableFuture<PublishedTradingEvent> published = bus.publish(envelope);

        assertThatThrownBy(published::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JournalException.class)
                .hasMessageContaining("append failed");
        assertThat(delegate.envelope()).isNull();
    }

    @Test
    void forwards_dead_letters_without_journaling() {
        CapturingTradingEventBus delegate = new CapturingTradingEventBus();
        CapturingTradingEventJournal journal = new CapturingTradingEventJournal();
        JournaledTradingEventBus bus = new JournaledTradingEventBus(delegate, journal);
        DeadLetterTradingEvent event = new DeadLetterTradingEvent(
                TradingEventType.STRATEGY_SIGNAL,
                TradingEventType.STRATEGY_SIGNAL.route(),
                new byte[] { 1 },
                new byte[] { 2 },
                "handler failure",
                Instant.parse("2026-05-23T12:00:00Z")
        );

        PublishedTradingEvent published = bus.publishDeadLetter(event).join();

        assertThat(published.topic()).isEqualTo(TradingEventType.STRATEGY_SIGNAL.route().deadLetterTopic());
        assertThat(delegate.deadLetter()).isSameAs(event);
        assertThat(journal.events()).isEmpty();
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

    private static void assertSerializedEvent(SerializedTradingEvent actual, SerializedTradingEvent expected) {
        assertThat(actual.eventType()).isEqualTo(expected.eventType());
        assertThat(actual.route()).isEqualTo(expected.route());
        assertThat(actual.keyPayload()).containsExactly(expected.keyPayload());
        assertThat(actual.valuePayload()).containsExactly(expected.valuePayload());
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private int publishOrder;
        private TradingEventEnvelope<? extends SpecificRecord> envelope;
        private DeadLetterTradingEvent deadLetter;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            this.publishOrder = 2;
            this.envelope = envelope;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    7
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            this.deadLetter = event;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    event.eventType(),
                    event.route().deadLetterTopic(),
                    0,
                    9
            ));
        }

        private int publishOrder() {
            return publishOrder;
        }

        private TradingEventEnvelope<? extends SpecificRecord> envelope() {
            return envelope;
        }

        private DeadLetterTradingEvent deadLetter() {
            return deadLetter;
        }
    }

    private static final class CapturingTradingEventJournal implements TradingEventJournal {

        private final List<JournaledTradingEvent> events = new ArrayList<>();
        private int appendOrder;

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            appendOrder = 1;
            JournaledTradingEvent journaled = new JournaledTradingEvent(events.size(), event);
            events.add(journaled);
            return journaled;
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            return List.copyOf(events);
        }

        @Override
        public void close() {
        }

        private List<JournaledTradingEvent> events() {
            return List.copyOf(events);
        }

        private int appendOrder() {
            return appendOrder;
        }
    }

    private static final class FailingTradingEventJournal implements TradingEventJournal {

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new JournalException("append failed");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
