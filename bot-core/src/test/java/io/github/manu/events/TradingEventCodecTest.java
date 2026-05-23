package io.github.manu.events;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandPositionSide;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandTimeInForce;
import io.github.manu.events.v1.OrderCommandType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventCodecTest {

    @Test
    void encodes_and_decodes_specific_records_with_logical_timestamps() {
        Instant requestedAt = Instant.parse("2026-05-23T09:40:15.123456Z");
        OrderCommandEvent original = OrderCommandEvent.newBuilder()
                .setEventId("evt-001")
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
                .setPositionSide(OrderCommandPositionSide.LONG)
                .setTimeInForce(OrderCommandTimeInForce.GTC)
                .setQuantity("0.001")
                .setPrice("50000.10")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(requestedAt)
                .setAttributes(Map.of("orderResponseType", "RESULT"))
                .build();
        TradingEventCodec<OrderCommandEvent> codec = TradingEventCodec.of(OrderCommandEvent.getClassSchema());

        byte[] encoded = codec.encode(original);
        OrderCommandEvent decoded = codec.decode(encoded);

        assertThat(encoded).isNotEmpty();
        assertThat(decoded.getCommandId().toString()).isEqualTo("cmd-001");
        assertThat(decoded.getProvider().toString()).isEqualTo("binance");
        assertThat(decoded.getSymbol().toString()).isEqualTo("BTCUSDT");
        assertThat(decoded.getRequestedAtMicros()).isEqualTo(requestedAt);
        assertThat(decoded.getAttributes())
                .anySatisfy((key, value) -> {
                    assertThat(key.toString()).isEqualTo("orderResponseType");
                    assertThat(value.toString()).isEqualTo("RESULT");
                });
    }

    @Test
    void rejects_invalid_binary_payloads_with_context() {
        TradingEventCodec<OrderCommandEvent> codec = TradingEventCodec.of(OrderCommandEvent.getClassSchema());

        assertThatThrownBy(() -> codec.decode(new byte[] { 1, 2, 3 }))
                .isInstanceOf(TradingEventCodecException.class)
                .hasMessageContaining("OrderCommandEvent");
    }
}
