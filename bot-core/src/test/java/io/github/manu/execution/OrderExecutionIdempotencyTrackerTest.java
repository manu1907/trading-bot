package io.github.manu.execution;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExecutionIdempotencyTrackerTest {

    private static final Instant NOW = Instant.parse("2026-05-26T14:00:00Z");

    @Test
    void rejects_duplicate_command_id_in_same_runtime_target() {
        OrderExecutionIdempotencyTracker tracker = tracker(true, 100);

        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
        OrderExecutionIdempotencyTracker.Admission duplicate = tracker.admit(command("cmd-001", "idem-002", "main"));

        assertThat(duplicate.status()).isEqualTo(OrderExecutionIdempotencyTracker.Status.DUPLICATE_COMMAND_ID);
        assertThat(duplicate.reason()).isEqualTo("execution:duplicate_command_id");
        assertThat(duplicate.attributes()).containsEntry("duplicate_command_id", "cmd-001");
    }

    @Test
    void rejects_duplicate_idempotency_key_in_same_runtime_target() {
        OrderExecutionIdempotencyTracker tracker = tracker(true, 100);

        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
        OrderExecutionIdempotencyTracker.Admission duplicate = tracker.admit(command("cmd-002", "idem-001", "main"));

        assertThat(duplicate.status()).isEqualTo(OrderExecutionIdempotencyTracker.Status.DUPLICATE_IDEMPOTENCY_KEY);
        assertThat(duplicate.reason()).isEqualTo("execution:duplicate_idempotency_key");
        assertThat(duplicate.attributes()).containsEntry("duplicate_idempotency_key", "idem-001");
    }

    @Test
    void scopes_identity_by_runtime_target() {
        OrderExecutionIdempotencyTracker tracker = tracker(true, 100);

        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
        assertThat(tracker.admit(command("cmd-001", "idem-001", "secondary")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
    }

    @Test
    void can_be_disabled_by_config() {
        OrderExecutionIdempotencyTracker tracker = tracker(false, 100);

        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
    }

    @Test
    void evicts_oldest_tracked_keys_when_bound_is_reached() {
        OrderExecutionIdempotencyTracker tracker = tracker(true, 4);

        tracker.admit(command("cmd-001", "idem-001", "main"));
        tracker.admit(command("cmd-002", "idem-002", "main"));
        tracker.admit(command("cmd-003", "idem-003", "main"));

        assertThat(tracker.admit(command("cmd-002", "idem-002", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.DUPLICATE_COMMAND_ID);
        assertThat(tracker.admit(command("cmd-001", "idem-001", "main")).status())
                .isEqualTo(OrderExecutionIdempotencyTracker.Status.ADMITTED);
    }

    private OrderExecutionIdempotencyTracker tracker(boolean enabled, int maxTrackedKeys) {
        return new OrderExecutionIdempotencyTracker(new ExecutionProperties(
                null,
                null,
                new ExecutionProperties.Idempotency(enabled, maxTrackedKeys)
        ));
    }

    private OrderCommandEvent command(String commandId, String idempotencyKey, String account) {
        return OrderCommandEvent.newBuilder()
                .setEventId("evt-" + commandId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount(account)
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.00")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("client-" + commandId)
                .setIdempotencyKey(idempotencyKey)
                .setRequestedAtMicros(NOW)
                .setAttributes(Map.of())
                .build();
    }
}
