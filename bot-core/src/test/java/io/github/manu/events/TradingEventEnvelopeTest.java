package io.github.manu.events;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventEnvelopeTest {

    @Test
    void binds_event_type_route_key_and_typed_value() {
        OrderCommandEvent command = orderCommand();
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );

        TradingEventEnvelope<OrderCommandEvent> envelope = TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                key,
                command
        );

        assertThat(envelope.route()).isEqualTo(TradingEventType.ORDER_COMMAND.route());
        assertThat(envelope.key().getPartitionKey().toString())
                .isEqualTo("order_command|order|binance|demo|main|usdm_futures|btcusdt|tb-lfa-001");
        assertThat(envelope.value()).isSameAs(command);
    }

    @Test
    void rejects_value_class_that_does_not_match_event_type() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );

        assertThatThrownBy(() -> new TradingEventEnvelope<>(
                TradingEventType.ORDER_COMMAND,
                TradingEventType.ORDER_COMMAND.route(),
                key,
                OrderResultEvent.newBuilder()
                        .setEventId("evt-result")
                        .setSchemaVersion(1)
                        .setCommandId("cmd-001")
                        .setProvider("binance")
                        .setEnvironment("demo")
                        .setAccount("main")
                        .setMarket("usdm_futures")
                        .setSymbol("BTCUSDT")
                        .setClientOrderId("tb-lfa-001")
                        .setStatus(io.github.manu.events.v1.OrderResultStatus.ACCEPTED)
                        .setObservedAtMicros(Instant.parse("2026-05-23T10:30:00Z"))
                        .setAttributes(Map.of())
                        .build()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejects_key_event_type_that_does_not_match_envelope_type() {
        TradingEventKey wrongKey = TradingEventKeys.order(
                TradingEventType.ORDER_RESULT,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );

        assertThatThrownBy(() -> TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, wrongKey, orderCommand()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key event type");
    }

    private OrderCommandEvent orderCommand() {
        return OrderCommandEvent.newBuilder()
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
    }
}
