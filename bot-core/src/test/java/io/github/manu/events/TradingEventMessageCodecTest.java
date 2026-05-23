package io.github.manu.events;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventMessageCodecTest {

    @Test
    void serializes_envelope_into_route_and_key_value_payloads() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder()
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
                .setPrice("50000.10")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(Instant.parse("2026-05-23T10:30:00Z"))
                .setAttributes(Map.of())
                .build();
        TradingEventEnvelope<OrderCommandEvent> envelope =
                TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, key, command);

        SerializedTradingEvent serialized = new TradingEventMessageCodec().serialize(envelope);
        TradingEventKey decodedKey = TradingEventCodec.<TradingEventKey>of(TradingEventType.ORDER_COMMAND.keySchema())
                .decode(serialized.keyPayload());
        OrderCommandEvent decodedValue = TradingEventCodec.<OrderCommandEvent>of(TradingEventType.ORDER_COMMAND.avroSchema())
                .decode(serialized.valuePayload());

        assertThat(serialized.eventType()).isEqualTo(TradingEventType.ORDER_COMMAND);
        assertThat(serialized.route()).isEqualTo(TradingEventType.ORDER_COMMAND.route());
        assertThat(serialized.keyPayload()).isNotEmpty();
        assertThat(serialized.valuePayload()).isNotEmpty();
        assertThat(decodedKey.getPartitionKey().toString()).isEqualTo(key.getPartitionKey().toString());
        assertThat(decodedValue.getCommandId().toString()).isEqualTo("cmd-001");
    }

    @Test
    void serialized_payloads_are_defensive_copies() {
        TradingEventKey key = TradingEventKeys.strategy(TradingEventType.STRATEGY_SIGNAL, "lfa");
        byte[] keyPayload = TradingEventCodec.<TradingEventKey>of(TradingEventType.STRATEGY_SIGNAL.keySchema()).encode(key);
        byte[] valuePayload = new byte[] { 1, 2, 3 };

        SerializedTradingEvent event = new SerializedTradingEvent(
                TradingEventType.STRATEGY_SIGNAL,
                TradingEventType.STRATEGY_SIGNAL.route(),
                keyPayload,
                valuePayload
        );
        keyPayload[0] = 99;
        valuePayload[0] = 99;

        assertThat(event.keyPayload()[0]).isNotEqualTo(99);
        assertThat(event.valuePayload()[0]).isEqualTo((byte) 1);
    }
}
