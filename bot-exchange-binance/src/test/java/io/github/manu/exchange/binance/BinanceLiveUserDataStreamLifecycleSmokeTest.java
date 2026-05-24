package io.github.manu.exchange.binance;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceLiveUserDataStreamLifecycleSmokeTest {

    private static final String ENABLE_PROPERTY = "binance.live.userdata.smoke";

    private final BinanceLiveSmokeTestSupport support = new BinanceLiveSmokeTestSupport();

    @Test
    void starts_renews_and_closes_user_data_stream_for_configured_live_target_when_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance live user-data stream smoke test");

        TradingBotProperties properties = support.loadCheckedInLiveConfig();
        ExchangeProperties active = properties.getExchange();
        support.requireBinanceLiveTarget(active);
        assertThat(active.market()).isEqualTo("usdm_futures");

        BinanceProperties binance = support.resolveBinance(properties);
        BinanceUserDataStreamClient client = new BinanceUserDataStreamClient(binance, support.apiKey(active), Clock.systemUTC());
        BinanceUserDataStreamSession started = null;
        try {
            started = client.start();
            assertThat(started.mode()).isEqualTo("listen_key");
            assertThat(started.streamId()).isNotBlank();
            assertThat(started.expiresAt()).isAfter(started.startedAt());
            assertThat(started.renewAfter()).isAfter(started.startedAt());
            assertThat(started.renewAfter()).isBefore(started.expiresAt());

            BinanceUserDataStreamSession renewed = client.keepAlive(started.streamId());
            assertThat(renewed.streamId()).isEqualTo(started.streamId());
        } finally {
            if (started != null) {
                client.close(started.streamId());
            }
        }
    }
}
