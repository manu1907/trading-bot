package io.github.manu.events;

import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.events.v1.TradingEventKeyEntityType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventKeysTest {

    @Test
    void builds_symbol_keys_with_canonical_partition_text() {
        TradingEventKey key = TradingEventKeys.symbol(
                TradingEventType.MARKET_DATA,
                "Binance",
                "Demo",
                "main",
                "usdm_futures",
                "BTCUSDT"
        );

        assertThat(key.getSchemaVersion()).isEqualTo(1);
        assertThat(key.getEventType().toString()).isEqualTo("MARKET_DATA");
        assertThat(key.getProvider().toString()).isEqualTo("Binance");
        assertThat(key.getEnvironment().toString()).isEqualTo("Demo");
        assertThat(key.getAccount().toString()).isEqualTo("main");
        assertThat(key.getMarket().toString()).isEqualTo("usdm_futures");
        assertThat(key.getSymbol().toString()).isEqualTo("BTCUSDT");
        assertThat(key.getEntityType()).isEqualTo(TradingEventKeyEntityType.SYMBOL);
        assertThat(key.getEntityId().toString()).isEqualTo("BTCUSDT");
        assertThat(key.getPartitionKey().toString())
                .isEqualTo("market_data|symbol|binance|demo|main|usdm_futures|btcusdt|btcusdt");
    }

    @Test
    void builds_order_keys_that_partition_by_runtime_symbol_and_client_order_id() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_RESULT,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "ETHUSDT",
                "tb-lfa-001"
        );

        assertThat(key.getEntityType()).isEqualTo(TradingEventKeyEntityType.ORDER);
        assertThat(key.getEntityId().toString()).isEqualTo("tb-lfa-001");
        assertThat(key.getPartitionKey().toString())
                .isEqualTo("order_result|order|binance|demo|main|usdm_futures|ethusdt|tb-lfa-001");
    }

    @Test
    void builds_strategy_keys_without_exchange_target() {
        TradingEventKey key = TradingEventKeys.strategy(TradingEventType.STRATEGY_SIGNAL, "lfa");

        assertThat(key.getProvider()).isNull();
        assertThat(key.getEntityType()).isEqualTo(TradingEventKeyEntityType.STRATEGY);
        assertThat(key.getPartitionKey().toString())
                .isEqualTo("strategy_signal|strategy|-|-|-|-|-|lfa");
    }
}
