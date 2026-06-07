package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandPositionSide;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandTimeInForce;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategySignalPlannerTest {

    private static final Instant NOW = Instant.parse("2026-05-31T18:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();

    @Test
    void plans_enter_long_limit_signal_into_provider_agnostic_order_command() {
        StrategySignalPlanner planner = planner();

        Optional<OrderCommandEvent> planned = planner.plan(signal(StrategySignalType.ENTER_LONG));

        assertThat(planned).hasValueSatisfying(command -> {
            assertThat(command.getCommandId()).isEqualTo("cmd:sig-001");
            assertThat(command.getProvider()).isEqualTo("binance");
            assertThat(command.getEnvironment()).isEqualTo("demo");
            assertThat(command.getAccount()).isEqualTo("main");
            assertThat(command.getMarket()).isEqualTo("usd_m_futures");
            assertThat(command.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(command.getAction()).isEqualTo(OrderCommandAction.NEW);
            assertThat(command.getTargetClientOrderId()).isNull();
            assertThat(command.getTargetExchangeOrderId()).isNull();
            assertThat(command.getSide()).isEqualTo(OrderCommandSide.BUY);
            assertThat(command.getOrderType()).isEqualTo(OrderCommandType.LIMIT);
            assertThat(command.getTimeInForce()).isEqualTo(OrderCommandTimeInForce.GTC);
            assertThat(command.getPositionSide()).isNull();
            assertThat(command.getQuantity()).isEqualTo("0.001");
            assertThat(command.getQuoteOrderQuantity()).isNull();
            assertThat(command.getPrice()).isEqualTo("50000.00");
            assertThat(command.getReduceOnly()).isFalse();
            assertThat(command.getClientOrderId()).isEqualTo("tb-sig-001");
            assertThat(command.getIdempotencyKey()).isEqualTo("signal:sig-001");
            assertThat(command.getRequestedAtMicros()).isEqualTo(NOW);
            assertThat(command.getAttributes())
                    .containsEntry("signal_id", "sig-001")
                    .containsEntry("signal_type", "ENTER_LONG")
                    .containsEntry("source", "strategy_signal_planner");
        });
    }

    @Test
    void publishes_planned_order_command_with_order_key() {
        StrategySignalPlanner planner = planner();

        planner.handleSignal(signal(StrategySignalType.ENTER_LONG)).join();

        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.ORDER_COMMAND);
            assertThat(envelope.key().getPartitionKey())
                    .isEqualTo("order_command|order|binance|demo|main|usd_m_futures|btcusdt|tb-sig-001");
            OrderCommandEvent command = (OrderCommandEvent) envelope.value();
            assertThat(command.getCommandId()).isEqualTo("cmd:sig-001");
        });
    }

    @Test
    void plans_exit_short_signal_as_reduce_only_buy() {
        StrategySignalPlanner planner = planner();
        StrategySignalEvent signal = StrategySignalEvent.newBuilder(signal(StrategySignalType.EXIT_SHORT))
                .setFeatures(Map.of("position_side", "SHORT"))
                .build();

        Optional<OrderCommandEvent> planned = planner.plan(signal);

        assertThat(planned).hasValueSatisfying(command -> {
            assertThat(command.getSide()).isEqualTo(OrderCommandSide.BUY);
            assertThat(command.getReduceOnly()).isTrue();
            assertThat(command.getPositionSide()).isEqualTo(OrderCommandPositionSide.SHORT);
        });
    }

    @Test
    void uses_signal_features_to_select_market_order_and_provider_attributes() {
        StrategySignalPlanner planner = planner();
        StrategySignalEvent signal = StrategySignalEvent.newBuilder(signal(StrategySignalType.ENTER_SHORT))
                .setLimitPrice(null)
                .setTargetQuantity(null)
                .setTargetNotional("100")
                .setFeatures(Map.of(
                        "order_type", "MARKET",
                        "client_order_id", "custom-client-id",
                        "working_type", "MARK_PRICE"
                ))
                .build();

        Optional<OrderCommandEvent> planned = planner.plan(signal);

        assertThat(planned).hasValueSatisfying(command -> {
            assertThat(command.getSide()).isEqualTo(OrderCommandSide.SELL);
            assertThat(command.getOrderType()).isEqualTo(OrderCommandType.MARKET);
            assertThat(command.getTimeInForce()).isNull();
            assertThat(command.getQuantity()).isNull();
            assertThat(command.getQuoteOrderQuantity()).isEqualTo("100");
            assertThat(command.getClientOrderId()).isEqualTo("custom-client-id");
            assertThat(command.getAttributes()).containsEntry("working_type", "MARK_PRICE");
        });
    }

    @Test
    void applies_matching_feature_profile_attributes_before_provider_mapping() {
        StrategySignalPlanner planner = new StrategySignalPlanner(
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(
                        true,
                        defaults(),
                        List.of(new ExecutionProperties.SignalPlanner.FeatureProfile(
                                "binance",
                                "demo",
                                "main",
                                "usd_m_futures",
                                "BTCUSDT",
                                "ENTER_LONG",
                                "LIMIT",
                                0.80,
                                Map.of("market_regime", "fast"),
                                Map.of(
                                        "price_match", "QUEUE",
                                        "working_type", "MARK_PRICE",
                                        "price_protect", "true",
                                        "self_trade_prevention_mode", "EXPIRE_TAKER"
                                )
                        ))
                ), null),
                eventBus,
                FIXED_CLOCK
        );
        StrategySignalEvent signal = StrategySignalEvent.newBuilder(signal(StrategySignalType.ENTER_LONG))
                .setFeatures(Map.of("market_regime", "fast"))
                .build();

        Optional<OrderCommandEvent> planned = planner.plan(signal);

        assertThat(planned).hasValueSatisfying(command -> assertThat(command.getAttributes())
                .containsEntry("price_match", "QUEUE")
                .containsEntry("working_type", "MARK_PRICE")
                .containsEntry("price_protect", "true")
                .containsEntry("self_trade_prevention_mode", "EXPIRE_TAKER")
                .containsEntry("planner_feature_profile_index", "0"));
    }

    @Test
    void keeps_explicit_signal_feature_over_matching_profile_attribute() {
        StrategySignalPlanner planner = new StrategySignalPlanner(
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(
                        true,
                        defaults(),
                        List.of(new ExecutionProperties.SignalPlanner.FeatureProfile(
                                null,
                                null,
                                null,
                                "usd_m_futures",
                                null,
                                null,
                                "LIMIT",
                                null,
                                Map.of(),
                                Map.of("working_type", "MARK_PRICE")
                        ))
                ), null),
                eventBus,
                FIXED_CLOCK
        );
        StrategySignalEvent signal = StrategySignalEvent.newBuilder(signal(StrategySignalType.ENTER_LONG))
                .setFeatures(Map.of("working_type", "CONTRACT_PRICE"))
                .build();

        Optional<OrderCommandEvent> planned = planner.plan(signal);

        assertThat(planned).hasValueSatisfying(command -> assertThat(command.getAttributes())
                .containsEntry("working_type", "CONTRACT_PRICE")
                .containsEntry("planner_feature_profile_index", "0"));
    }

    @Test
    void does_not_apply_feature_profile_below_minimum_confidence() {
        StrategySignalPlanner planner = new StrategySignalPlanner(
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(
                        true,
                        defaults(),
                        List.of(new ExecutionProperties.SignalPlanner.FeatureProfile(
                                null,
                                null,
                                null,
                                null,
                                null,
                                "ENTER_LONG",
                                null,
                                0.95,
                                Map.of(),
                                Map.of("post_only", "true")
                        ))
                ), null),
                eventBus,
                FIXED_CLOCK
        );

        Optional<OrderCommandEvent> planned = planner.plan(signal(StrategySignalType.ENTER_LONG));

        assertThat(planned).hasValueSatisfying(command ->
                assertThat(command.getAttributes()).doesNotContainKey("post_only"));
    }

    @Test
    void ignores_hold_signal_without_publishing_order_command() {
        StrategySignalPlanner planner = planner();

        planner.handleSignal(signal(StrategySignalType.HOLD)).join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void suppresses_order_command_when_symbol_is_paused_by_governance() {
        StrategySignalPlanner planner = planner(projectionWithPause("SYMBOL", "BTCUSDT", "PAUSE_SYMBOL"));

        Optional<OrderCommandEvent> planned = planner.plan(signal(StrategySignalType.ENTER_LONG));
        planner.handleSignal(signal(StrategySignalType.ENTER_LONG)).join();

        assertThat(planned).isEmpty();
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void requires_target_when_signal_and_defaults_do_not_provide_it() {
        StrategySignalPlanner planner = new StrategySignalPlanner(
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(
                        true,
                        new ExecutionProperties.SignalPlanner.Defaults(null, null, null, null, null, "GTC", "tb")
                ), null),
                eventBus,
                FIXED_CLOCK
        );

        assertThatThrownBy(() -> planner.plan(signal(StrategySignalType.ENTER_LONG)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("provider is required");
    }

    private StrategySignalPlanner planner() {
        return planner(new TradingStateProjection());
    }

    private StrategySignalPlanner planner(TradingStateProjection projection) {
        return new StrategySignalPlanner(
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(
                        true,
                        defaults()
                ), null),
                eventBus,
                projection,
                FIXED_CLOCK
        );
    }

    private TradingStateProjection projectionWithPause(String scope, String target, String action) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.PauseGovernanceState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        scope,
                        target,
                        "BTCUSDT",
                        "remediation-pause-001",
                        "POSITION",
                        action,
                        "external_position_change",
                        List.of("risk_policy"),
                        "automated_policy",
                        "pause until risk is resolved",
                        Map.of(),
                        true,
                        NOW,
                        "evt-pause"
                )),
                List.of()
        ));
        return projection;
    }

    private ExecutionProperties.SignalPlanner.Defaults defaults() {
        return new ExecutionProperties.SignalPlanner.Defaults(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "GTC",
                "tb"
        );
    }

    private StrategySignalEvent signal(StrategySignalType signalType) {
        return StrategySignalEvent.newBuilder()
                .setEventId("evt-sig-001")
                .setSchemaVersion(1)
                .setSignalId("sig-001")
                .setStrategyId("lfa")
                .setProvider(null)
                .setEnvironment(null)
                .setAccount(null)
                .setMarket(null)
                .setSymbol(null)
                .setSignalType(signalType)
                .setConfidence(0.85)
                .setTargetQuantity("0.001")
                .setTargetNotional(null)
                .setLimitPrice("50000.00")
                .setStopPrice(null)
                .setEmittedAtMicros(NOW)
                .setFeatures(Map.of())
                .setAttributes(Map.of())
                .build();
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
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
