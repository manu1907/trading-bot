package io.github.manu.storage;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.journal.JournaledTradingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventArchiveLayoutTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-24T10:15:30Z");

    @Test
    void builds_partitioned_gcs_object_name_for_journaled_event() {
        TradingEventArchiveLayout layout = new TradingEventArchiveLayout("prod/eu-west");
        JournaledTradingEvent journaled = new JournaledTradingEvent(42, serializedConfigChange());

        String objectName = layout.objectName(journaled, EVENT_TIME);

        assertThat(objectName).isEqualTo(
                "prod/eu-west/trading-events/v1/event_type=config-change/"
                        + "topic=trading.v1.config-change/date=2026-05-24/hour=10/"
                        + "00000000000000000042.avrobin"
        );
    }

    @Test
    void normalizes_optional_prefix() {
        TradingEventArchiveLayout layout = new TradingEventArchiveLayout(" /archive/ ");
        JournaledTradingEvent journaled = new JournaledTradingEvent(7, serializedConfigChange());

        String objectName = layout.objectName(journaled, EVENT_TIME);

        assertThat(objectName).startsWith("archive/trading-events/");
        assertThat(objectName).endsWith("/00000000000000000007.avrobin");
    }

    @Test
    void allows_empty_prefix_for_bucket_root_layout() {
        TradingEventArchiveLayout layout = new TradingEventArchiveLayout("");
        JournaledTradingEvent journaled = new JournaledTradingEvent(1, serializedConfigChange());

        assertThat(layout.objectName(journaled, EVENT_TIME))
                .startsWith("trading-events/v1/event_type=config-change/");
    }

    @Test
    void rejects_empty_prefix_segments() {
        assertThatThrownBy(() -> new TradingEventArchiveLayout("archive//events"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prefix must not contain empty path segments");
    }

    @Test
    void requires_inputs() {
        TradingEventArchiveLayout layout = new TradingEventArchiveLayout("archive");

        assertThatThrownBy(() -> layout.objectName(null, EVENT_TIME))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("journaled");
        assertThatThrownBy(() -> layout.objectName(new JournaledTradingEvent(1, serializedConfigChange()), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("archivedAt");
    }

    private static SerializedTradingEvent serializedConfigChange() {
        return new TradingEventMessageCodec().serialize(configChangeEnvelope());
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
                .setChangeId("cfg-archive-001")
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
                .setChangedAtMicros(EVENT_TIME)
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.CONFIG_CHANGE, key, event);
    }
}
