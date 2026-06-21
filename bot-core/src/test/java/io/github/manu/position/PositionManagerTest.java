package io.github.manu.position;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PositionManagerTest {

    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usdm_futures";
    private static final String SYMBOL = "BTCUSDT";
    private static final Instant NOW = Instant.parse("2026-06-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabled_lifecycle_does_not_publish() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projection(position("-0.010", "-5")), disabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason()).isEqualTo("position_lifecycle:disabled");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_when_reconciliation_is_not_confident() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projection(position("-0.010", "-5")), enabledProperties(), eventBus, new ReconciliationConfidenceTracker(CLOCK));

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:target_blocked");
        assertThat(result.blockers()).contains("reconciliation_not_confident:no_observations");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void publishes_reduce_signal_when_loss_crosses_reduce_threshold() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projection(position("0.010", "-6")), enabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:published");
        assertThat(result.evaluatedPositions()).isEqualTo(1);
        assertThat(result.publishedSignals()).isEqualTo(1);
        StrategySignalEvent signal = publishedSignal(eventBus);
        assertThat(signal.getSignalType()).isEqualTo(StrategySignalType.REDUCE_LONG);
        assertThat(signal.getTargetQuantity()).hasToString("0.005");
        assertThat(signal.getFeatures())
                .containsEntry("order_type", "MARKET")
                .containsEntry("position_side", "BOTH");
        assertThat(signal.getAttributes())
                .containsEntry("source", "position_lifecycle")
                .containsEntry("position_lifecycle_action", "reduce")
                .containsEntry("position_lifecycle_reason", "unrealized_loss_reduce_threshold");
    }

    @Test
    void blocks_when_governed_strategy_lifecycle_is_missing() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projectionWithoutLifecycle(position("0.010", "-6")), enabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:target_blocked");
        assertThat(result.blockers()).contains("strategy_lifecycle_missing:lfa");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void blocks_when_governed_strategy_lifecycle_is_not_active() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projection(position("0.010", "-6"), null, "PAUSED"), enabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:target_blocked");
        assertThat(result.blockers()).contains("strategy_lifecycle_not_allowed:lfa:PAUSED");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    @Test
    void publishes_full_close_signal_when_loss_crosses_close_threshold() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        PositionManager manager = manager(projection(position("-0.020", "-15")), enabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:published");
        StrategySignalEvent signal = publishedSignal(eventBus);
        assertThat(signal.getSignalType()).isEqualTo(StrategySignalType.EXIT_SHORT);
        assertThat(signal.getTargetQuantity()).isNull();
        assertThat(signal.getFeatures())
                .containsEntry("close_position", "true")
                .containsEntry("order_type", "MARKET");
        assertThat(signal.getAttributes()).containsEntry("position_lifecycle_action", "close");
    }

    @Test
    void blocks_position_when_open_order_exists_for_symbol() {
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        TradingStateProjection projection = projection(position("0.010", "-6"), openOrder());
        PositionManager manager = manager(projection, enabledProperties(), eventBus, confidentTracker());

        PositionManager.PositionLifecycleRunResult result = manager.runOnce();

        assertThat(result.reason()).isEqualTo("position_lifecycle:no_action");
        assertThat(result.blockers()).contains("open_order_exists:BTCUSDT");
        assertThat(eventBus.envelopes()).isEmpty();
    }

    private PositionManager manager(
            TradingStateProjection projection,
            PositionProperties properties,
            TradingEventBus eventBus,
            ReconciliationConfidenceTracker tracker
    ) {
        return new PositionManager(projection, properties, eventBus, tracker, CLOCK);
    }

    private PositionProperties enabledProperties() {
        return new PositionProperties(new PositionProperties.Lifecycle(
                true,
                30_000L,
                1_000L,
                "position-lifecycle",
                "lfa",
                true,
                List.of("ACTIVE"),
                new PositionProperties.Target(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET, List.of()),
                true,
                true,
                30_000L,
                true,
                true,
                true,
                "5",
                "0.50",
                "10",
                "MARKET",
                null
        ));
    }

    private PositionProperties disabledProperties() {
        return new PositionProperties(null);
    }

    private ReconciliationConfidenceTracker confidentTracker() {
        ReconciliationConfidenceTracker tracker = new ReconciliationConfidenceTracker(CLOCK);
        tracker.record(new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.POSITION_UPDATE,
                SYMBOL,
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        return tracker;
    }

    private TradingStateProjection projection(TradingStateProjection.PositionState position) {
        return projection(position, null);
    }

    private TradingStateProjection projectionWithoutLifecycle(TradingStateProjection.PositionState position) {
        return projection(position, null, null);
    }

    private TradingStateProjection projection(
            TradingStateProjection.PositionState position,
            TradingStateProjection.OrderState order
    ) {
        return projection(position, order, "ACTIVE");
    }

    private TradingStateProjection projection(
            TradingStateProjection.PositionState position,
            TradingStateProjection.OrderState order,
            String lifecycleState
    ) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(position),
                order == null ? List.of() : List.of(order),
                List.of(),
                List.of(marketData()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                lifecycleState == null ? List.of() : List.of(strategyLifecycle(lifecycleState)),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection.PositionState position(String amount, String unrealizedPnl) {
        return new TradingStateProjection.PositionState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL,
                "BOTH",
                "ONE_WAY",
                amount,
                "100",
                "101",
                unrealizedPnl,
                "3",
                "cross",
                null,
                "REST_SNAPSHOT",
                false,
                null,
                NOW.minusSeconds(1),
                "position-event-1"
        );
    }

    private TradingStateProjection.MarketDataState marketData() {
        return new TradingStateProjection.MarketDataState(
                PROVIDER,
                ENVIRONMENT,
                MARKET,
                SYMBOL,
                "bookTicker",
                "100",
                "1",
                "101",
                "1",
                NOW.minusSeconds(1),
                null,
                null,
                null,
                null,
                Map.of(),
                NOW.minusSeconds(1),
                "market-event-1"
        );
    }

    private TradingStateProjection.StrategyLifecycleState strategyLifecycle(String lifecycleState) {
        return new TradingStateProjection.StrategyLifecycleState(
                "lfa:" + PROVIDER + ":" + ENVIRONMENT + ":" + ACCOUNT + ":" + MARKET,
                "lfa",
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                null,
                lifecycleState,
                "test",
                "test lifecycle state",
                Map.of(),
                NOW.minusSeconds(1),
                "strategy-lifecycle-event-1"
        );
    }

    private TradingStateProjection.OrderState openOrder() {
        return new TradingStateProjection.OrderState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL,
                "command-1",
                "client-1",
                "exchange-1",
                "ACCEPTED",
                "NEW",
                "BUY",
                "LIMIT",
                "100",
                "0.010",
                "0",
                null,
                null,
                "ORDER_COMMAND",
                "NEW",
                true,
                false,
                null,
                NOW.minusSeconds(1),
                "order-event-1"
        );
    }

    private StrategySignalEvent publishedSignal(CapturingTradingEventBus eventBus) {
        assertThat(eventBus.envelopes()).hasSize(1);
        assertThat(eventBus.envelopes().getFirst().eventType()).isEqualTo(TradingEventType.STRATEGY_SIGNAL);
        return (StrategySignalEvent) eventBus.envelopes().getFirst().value();
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size() - 1L
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }

        private List<TradingEventEnvelope<? extends SpecificRecord>> envelopes() {
            return List.copyOf(envelopes);
        }
    }
}
