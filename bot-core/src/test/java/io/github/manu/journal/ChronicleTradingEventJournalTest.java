package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventCodec;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleTradingEventJournalTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-05-23T12:00:00Z");

    @TempDir
    private Path journalDirectory;

    @Test
    void appends_and_reads_serialized_events_in_order() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        SerializedTradingEvent command = codec.serialize(orderCommandEnvelope());
        SerializedTradingEvent configChange = codec.serialize(configChangeEnvelope());

        try (TradingEventJournal journal = new ChronicleTradingEventJournal(journalDirectory)) {
            JournaledTradingEvent first = journal.append(command);
            JournaledTradingEvent second = journal.append(configChange);

            List<JournaledTradingEvent> events = journal.readAll();

            assertThat(first.index()).isLessThan(second.index());
            assertThat(events).hasSize(2);
            assertSerializedEvent(events.getFirst().event(), command);
            assertSerializedEvent(events.get(1).event(), configChange);
            assertCommand(events.getFirst().event());
            assertConfigChange(events.get(1).event());
        }
    }

    @Test
    void replays_events_from_a_reopened_journal() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        SerializedTradingEvent command = codec.serialize(orderCommandEnvelope());
        try (TradingEventJournal journal = new ChronicleTradingEventJournal(journalDirectory)) {
            journal.append(command);
        }

        List<JournaledTradingEvent> replayed = new ArrayList<>();
        try (TradingEventJournal journal = new ChronicleTradingEventJournal(journalDirectory)) {
            journal.replay(replayed::add);
        }

        assertThat(replayed).hasSize(1);
        assertSerializedEvent(replayed.getFirst().event(), command);
        assertCommand(replayed.getFirst().event());
    }

    private static TradingEventEnvelope<OrderCommandEvent> orderCommandEnvelope() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );
        OrderCommandEvent event = OrderCommandEvent.newBuilder()
                .setEventId("evt-command")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
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
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(TIMESTAMP)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, key, event);
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

    private static void assertCommand(SerializedTradingEvent event) {
        TradingEventKey key = TradingEventCodec.<TradingEventKey>of(TradingEventType.ORDER_COMMAND.keySchema())
                .decode(event.keyPayload());
        OrderCommandEvent command = TradingEventCodec.<OrderCommandEvent>of(TradingEventType.ORDER_COMMAND.avroSchema())
                .decode(event.valuePayload());

        assertThat(event.eventType()).isEqualTo(TradingEventType.ORDER_COMMAND);
        assertThat(key.getPartitionKey().toString())
                .isEqualTo("order_command|order|binance|demo|main|usdm_futures|btcusdt|tb-lfa-001");
        assertThat(command.getCommandId().toString()).isEqualTo("cmd-001");
    }

    private static void assertConfigChange(SerializedTradingEvent event) {
        TradingEventKey key = TradingEventCodec.<TradingEventKey>of(TradingEventType.CONFIG_CHANGE.keySchema())
                .decode(event.keyPayload());
        ConfigChangeEvent configChange = TradingEventCodec.<ConfigChangeEvent>of(TradingEventType.CONFIG_CHANGE.avroSchema())
                .decode(event.valuePayload());

        assertThat(event.eventType()).isEqualTo(TradingEventType.CONFIG_CHANGE);
        assertThat(key.getPartitionKey().toString())
                .isEqualTo(
                        "config_change|config|binance|demo|main|usdm_futures|-"
                                + "|/providers/binance/environments/demo/accounts/main/enabled"
                );
        assertThat(configChange.getChangeId().toString()).isEqualTo("cfg-001");
    }

    private static void assertSerializedEvent(SerializedTradingEvent actual, SerializedTradingEvent expected) {
        assertThat(actual.eventType()).isEqualTo(expected.eventType());
        assertThat(actual.route()).isEqualTo(expected.route());
        assertThat(actual.keyPayload()).containsExactly(expected.keyPayload());
        assertThat(actual.valuePayload()).containsExactly(expected.valuePayload());
    }
}
