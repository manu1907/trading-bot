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
import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class JournalStartupRecoveryIntegrationTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-05-24T10:00:00Z");

    @TempDir
    private Path journalDirectory;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JournalConfiguration.class);

    @Test
    void replays_reopened_chronicle_journal_during_startup() {
        TradingEventMessageCodec codec = new TradingEventMessageCodec();
        appendRestartEvents(codec.serialize(orderCommandEnvelope()), codec.serialize(configChangeEnvelope()));
        List<String> handled = new ArrayList<>();

        contextRunner
                .withBean(TradingEventHandlerRegistry.class, () -> recoveryRegistry(handled))
                .withPropertyValues(
                        "trading.journal.enabled=true",
                        "trading.journal.directory=" + journalDirectory,
                        "trading.journal.recovery.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(JournalRecoveryLifecycle.class);
                    assertThat(context.getBean(JournalRecoveryLifecycle.class).isRunning()).isTrue();
                    assertThat(handled).containsExactly("command:cmd-restart-001", "config:cfg-restart-001");
                });
    }

    private void appendRestartEvents(SerializedTradingEvent first, SerializedTradingEvent second) {
        try (TradingEventJournal journal = new ChronicleTradingEventJournal(journalDirectory)) {
            journal.append(first);
            journal.append(second);
        }
    }

    private static TradingEventHandlerRegistry recoveryRegistry(List<String> handled) {
        return new TradingEventHandlerRegistry(List.of(
                new TradingEventHandlerRegistration(
                        TradingEventType.ORDER_COMMAND,
                        envelope -> {
                            OrderCommandEvent event = (OrderCommandEvent) envelope.value();
                            handled.add("command:" + event.getCommandId());
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
    }

    private static TradingEventEnvelope<OrderCommandEvent> orderCommandEnvelope() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-restart-001"
        );
        OrderCommandEvent event = OrderCommandEvent.newBuilder()
                .setEventId("evt-restart-command")
                .setSchemaVersion(1)
                .setCommandId("cmd-restart-001")
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
                .setClientOrderId("tb-restart-001")
                .setIdempotencyKey("idem-restart-001")
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
                .setEventId("evt-restart-config")
                .setSchemaVersion(1)
                .setChangeId("cfg-restart-001")
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
}
