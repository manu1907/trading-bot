package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.messaging.MessagingException;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventHandlerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalRecoveryServiceTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-05-23T13:00:00Z");

    @Test
    void replays_journal_events_through_registered_handlers_in_order() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        SerializedTradingEvent signal = codec.serialize(strategySignalEnvelope());
        SerializedTradingEvent configChange = codec.serialize(configChangeEnvelope());
        InMemoryTradingEventJournal journal = new InMemoryTradingEventJournal(List.of(
                new JournaledTradingEvent(10, signal),
                new JournaledTradingEvent(11, configChange)
        ));
        List<String> handled = new ArrayList<>();
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of(
                new TradingEventHandlerRegistration(
                        TradingEventType.STRATEGY_SIGNAL,
                        envelope -> {
                            StrategySignalEvent event = (StrategySignalEvent) envelope.value();
                            handled.add("signal:" + event.getSignalId());
                            return CompletableFuture.completedFuture(null);
                        }
                ),
                new TradingEventHandlerRegistration(
                        TradingEventType.CONFIG_CHANGE,
                        envelope -> {
                            ConfigChangeEvent event = (ConfigChangeEvent) envelope.value();
                            handled.add("config:" + event.getChangeId());
                            return CompletableFuture.completedFuture(null);
                        }
                )
        ));
        JournalRecoveryService recoveryService = new JournalRecoveryService(journal, registry);

        JournalRecoveryReport report = recoveryService.replayAll();

        assertThat(report.replayedEvents()).isEqualTo(2);
        assertThat(report.lastIndex()).isEqualTo(11);
        assertThat(handled).containsExactly("signal:sig-001", "config:cfg-001");
    }

    @Test
    void returns_empty_report_when_journal_has_no_events() {
        JournalRecoveryService recoveryService = new JournalRecoveryService(
                new InMemoryTradingEventJournal(List.of()),
                new TradingEventHandlerRegistry(List.of())
        );

        JournalRecoveryReport report = recoveryService.replayAll();

        assertThat(report.replayedEvents()).isZero();
        assertThat(report.lastIndex()).isEqualTo(-1);
    }

    @Test
    void fails_fast_when_a_handler_cannot_rebuild_state() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        SerializedTradingEvent signal = codec.serialize(strategySignalEnvelope());
        SerializedTradingEvent configChange = codec.serialize(configChangeEnvelope());
        InMemoryTradingEventJournal journal = new InMemoryTradingEventJournal(List.of(
                new JournaledTradingEvent(10, signal),
                new JournaledTradingEvent(11, configChange)
        ));
        List<String> handled = new ArrayList<>();
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of(
                new TradingEventHandlerRegistration(
                        TradingEventType.STRATEGY_SIGNAL,
                        envelope -> {
                            handled.add("signal");
                            return CompletableFuture.completedFuture(null);
                        }
                ),
                new TradingEventHandlerRegistration(
                        TradingEventType.CONFIG_CHANGE,
                        envelope -> CompletableFuture.failedFuture(new MessagingException("projection rejected"))
                )
        ));
        JournalRecoveryService recoveryService = new JournalRecoveryService(journal, registry);

        assertThatThrownBy(recoveryService::replayAll)
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("index 11")
                .hasRootCauseMessage("projection rejected");
        assertThat(handled).containsExactly("signal");
    }

    @Test
    void skips_live_only_handlers_during_journal_recovery() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        SerializedTradingEvent command = codec.serialize(orderCommandEnvelope());
        InMemoryTradingEventJournal journal = new InMemoryTradingEventJournal(List.of(
                new JournaledTradingEvent(12, command)
        ));
        List<String> handled = new ArrayList<>();
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of(
                TradingEventHandlerRegistration.liveOnly(
                        TradingEventType.ORDER_COMMAND,
                        envelope -> {
                            handled.add("live-command");
                            return CompletableFuture.completedFuture(null);
                        }
                ),
                TradingEventHandlerRegistration.replayOnly(
                        TradingEventType.ORDER_COMMAND,
                        envelope -> {
                            handled.add("replay-command");
                            return CompletableFuture.completedFuture(null);
                        }
                )
        ));
        JournalRecoveryService recoveryService = new JournalRecoveryService(journal, registry);

        JournalRecoveryReport report = recoveryService.replayAll();

        assertThat(report.replayedEvents()).isEqualTo(1);
        assertThat(handled).containsExactly("replay-command");
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
                .setEmittedAtMicros(TIMESTAMP)
                .setFeatures(Map.of("fragility", "0.82"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.STRATEGY_SIGNAL, key, signal);
    }

    private static TradingEventEnvelope<ConfigChangeEvent> configChangeEnvelope() {
        TradingEventKey key = TradingEventKeys.config(
                TradingEventType.CONFIG_CHANGE,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "/providers/binance/environments/demo/accounts/main/enabled"
        );
        ConfigChangeEvent event = ConfigChangeEvent.newBuilder()
                .setEventId("evt-config")
                .setSchemaVersion(1)
                .setChangeId("cfg-001")
                .setSource(ConfigChangeSource.RUNTIME_FILE)
                .setProfile("live")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setPath("/providers/binance/environments/demo/accounts/main/enabled")
                .setOldValue("false")
                .setNewValue("true")
                .setApplied(true)
                .setRejectedReason(null)
                .setChangedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.CONFIG_CHANGE, key, event);
    }

    private static TradingEventEnvelope<OrderCommandEvent> orderCommandEnvelope() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-recovery-001"
        );
        OrderCommandEvent event = OrderCommandEvent.newBuilder()
                .setEventId("evt-command")
                .setSchemaVersion(1)
                .setCommandId("cmd-recovery-001")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setSymbol("BTCUSDT")
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.00")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-recovery-001")
                .setIdempotencyKey("idem-recovery-001")
                .setRequestedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, key, event);
    }

    private record InMemoryTradingEventJournal(List<JournaledTradingEvent> events) implements TradingEventJournal {

        private InMemoryTradingEventJournal {
            events = List.copyOf(events);
        }

        @Override
        public JournaledTradingEvent append(SerializedTradingEvent event) {
            throw new UnsupportedOperationException("append is not used by recovery tests");
        }

        @Override
        public List<JournaledTradingEvent> readAll() {
            return events;
        }

        @Override
        public void close() {
        }
    }
}
