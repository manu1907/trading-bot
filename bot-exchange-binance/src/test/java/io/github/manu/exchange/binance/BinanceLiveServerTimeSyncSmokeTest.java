package io.github.manu.exchange.binance;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceLiveServerTimeSyncSmokeTest {

    private static final String ENABLE_PROPERTY = "binance.live.servertime.smoke";

    private final BinanceLiveSmokeTestSupport support = new BinanceLiveSmokeTestSupport();

    @Test
    void syncs_live_server_time_for_configured_target_when_explicitly_enabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY), () ->
                "Set -D" + ENABLE_PROPERTY + "=true to run the Binance live server-time smoke test");

        TradingBotProperties properties = support.loadCheckedInLiveConfig();
        ExchangeProperties active = properties.getExchange();
        support.requireBinanceLiveTarget(active);
        assertThat(active.market()).isEqualTo("usdm_futures");

        BinanceProperties binance = support.resolveBinance(properties);
        BinanceServerTimeSnapshot snapshot = new BinanceServerTimeSynchronizer(binance.rest()).sync();

        assertThat(snapshot.serverTime()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(snapshot.roundTripMillis()).isBetween(0L, 15_000L);
    }
}
