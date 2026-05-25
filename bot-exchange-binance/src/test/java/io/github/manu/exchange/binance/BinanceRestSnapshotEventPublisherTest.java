package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceRestSnapshotEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-05-25T13:15:00Z");
    private static final BinanceRestSnapshotEventPublisher.Context CONTEXT =
            new BinanceRestSnapshotEventPublisher.Context("binance", "demo", "main", "usd_m_futures");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final BinanceRestSnapshotEventPublisher publisher = new BinanceRestSnapshotEventPublisher(
            new BinanceRestSnapshotEventMapper(),
            CONTEXT,
            eventBus,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void publishes_open_order_snapshot_events_to_core_bus() {
        List<PublishedTradingEvent> published = publisher.publishOpenOrders(List.of(order())).join();

        assertThat(published).hasSize(1);
        assertThat(eventBus.envelopes).hasSize(1);
        TradingEventEnvelope<?> envelope = eventBus.envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.ORDER_RESULT);
        assertThat(envelope.value()).isInstanceOf(OrderResultEvent.class);
        OrderResultEvent value = (OrderResultEvent) envelope.value();
        assertThat(value.getCommandId()).hasToString("reconciliation:tb-1");
        assertThat(value.getObservedAtMicros()).isEqualTo(NOW);
    }

    @Test
    void publishes_mixed_futures_account_snapshot_events_in_mapper_order() {
        List<PublishedTradingEvent> published = publisher.publishFuturesAccount(futuresAccount()).join();

        assertThat(published).hasSize(2);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.BALANCE_UPDATE, TradingEventType.POSITION_UPDATE);
        assertThat(eventBus.envelopes.getFirst().value()).isInstanceOf(BalanceUpdateEvent.class);
        assertThat(eventBus.envelopes.get(1).value()).isInstanceOf(PositionUpdateEvent.class);
        assertThat(((BalanceUpdateEvent) eventBus.envelopes.getFirst().value()).getEventTimeMicros())
                .isEqualTo(Instant.ofEpochMilli(1_772_000_000_002L));
    }

    @Test
    void returns_failed_future_when_event_bus_publish_fails() {
        RuntimeException failure = new RuntimeException("publish failed");
        eventBus.nextFailure = failure;

        CompletableFuture<List<PublishedTradingEvent>> result = publisher.publishOpenOrders(List.of(order()));

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        assertThat(eventBus.envelopes).hasSize(1);
    }

    @Test
    void validates_context_before_publishing() {
        assertThatThrownBy(() -> new BinanceRestSnapshotEventPublisher(
                new BinanceRestSnapshotEventMapper(),
                new BinanceRestSnapshotEventPublisher.Context("binance", "demo", " ", "spot"),
                eventBus,
                Clock.fixed(NOW, ZoneOffset.UTC)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account is required");
    }

    private BinanceOrderResult order() {
        return new BinanceOrderResult(
                "BTCUSDT",
                12345L,
                "tb-1",
                "NEW",
                "BUY",
                "LIMIT",
                "BOTH",
                decimal("50000.00"),
                decimal("0.010"),
                decimal("0"),
                null,
                decimal("0"),
                1_772_000_000_001L
        );
    }

    private BinanceFuturesAccountSnapshot futuresAccount() {
        return new BinanceFuturesAccountSnapshot(
                decimal("10"),
                decimal("2"),
                decimal("1000"),
                decimal("12.5"),
                decimal("1012.5"),
                decimal("8"),
                decimal("1"),
                decimal("900"),
                decimal("12.5"),
                decimal("700"),
                decimal("650"),
                true,
                true,
                true,
                0,
                1_772_000_000_000L,
                List.of(new BinanceFuturesAssetSnapshot(
                        "USDT",
                        decimal("1000.000"),
                        decimal("12.5"),
                        decimal("1012.5"),
                        decimal("2"),
                        decimal("10"),
                        decimal("8"),
                        decimal("1"),
                        decimal("900"),
                        decimal("12.5"),
                        decimal("700"),
                        decimal("650"),
                        1_772_000_000_002L
                )),
                List.of(position())
        );
    }

    private BinanceFuturesPositionSnapshot position() {
        return new BinanceFuturesPositionSnapshot(
                "BTCUSDT",
                "LONG",
                decimal("0.250"),
                decimal("50000.00"),
                decimal("50001.00"),
                decimal("50100.00"),
                decimal("25.00"),
                decimal("45000.00"),
                20,
                decimal("10"),
                "isolated",
                true,
                false,
                decimal("1000"),
                decimal("900"),
                decimal("12525"),
                "USDT",
                decimal("10"),
                decimal("2"),
                decimal("8"),
                decimal("1"),
                2,
                decimal("0"),
                decimal("0"),
                1_772_000_000_003L
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();
        private RuntimeException nextFailure;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            if (nextFailure != null) {
                CompletableFuture<PublishedTradingEvent> failed = new CompletableFuture<>();
                failed.completeExceptionally(nextFailure);
                nextFailure = null;
                return failed;
            }
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this publisher");
        }
    }
}
