package io.github.manu.observability;

import io.github.manu.config.properties.BotProperties;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.ExchangeSectionProperties;
import io.github.manu.config.properties.ProviderCatalogProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.events.TradingEventType;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import io.github.manu.runtime.RuntimeStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStatusGaugeBinderTest {

    private static final Instant NOW = Instant.parse("2026-06-18T22:00:00Z");
    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usd_m_futures";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ConfigManager configManager = new ConfigManager();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker =
            new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exposes_active_runtime_readiness_projection_market_data_and_blocker_gauges() {
        configManager.setConfig(config());
        reconciliationConfidenceTracker.record(new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.ORDER_RESULT,
                "orders",
                ReconciliationConfidenceStatus.MISMATCH,
                List.of()
        ));
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(position()),
                List.of(order("client-unknown", "UNKNOWN"), order("client-pending", "COMMAND_RECEIVED")),
                List.of(),
                List.of(marketData(NOW.minusSeconds(120))),
                List.of(),
                List.of(),
                List.of(),
                List.of(pause()),
                List.of(),
                List.of()
        ));

        runtimeStatusGaugeBinder().bind();

        assertThat(readiness("READY")).isZero();
        assertThat(readiness("ATTENTION")).isZero();
        assertThat(readiness("BLOCKED")).isEqualTo(1.0d);
        assertThat(reconciliation("DEGRADED")).isEqualTo(1.0d);
        assertThat(blocker("reconciliation:degraded")).isEqualTo(1.0d);
        assertThat(blocker("orders:unknown_status")).isEqualTo(1.0d);
        assertThat(blocker("orders:unresolved_command")).isEqualTo(1.0d);
        assertThat(blocker("governance:active_pause")).isEqualTo(1.0d);
        assertThat(blocker("market_data:fresh_symbols_below_minimum")).isEqualTo(1.0d);
        assertThat(projectionGauge("open_orders")).isZero();
        assertThat(projectionGauge("open_positions")).isEqualTo(1.0d);
        assertThat(projectionGauge("unknown_order_statuses")).isEqualTo(1.0d);
        assertThat(projectionGauge("unresolved_order_commands")).isEqualTo(1.0d);
        assertThat(projectionGauge("active_pauses")).isEqualTo(1.0d);
        assertThat(marketDataGauge("total_symbols")).isEqualTo(1.0d);
        assertThat(marketDataGauge("fresh_symbols")).isZero();
        assertThat(marketDataGauge("stale_symbols")).isEqualTo(1.0d);

        reconciliationConfidenceTracker.record(new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.ORDER_RESULT,
                "orders",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(marketData(NOW.minusSeconds(5))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        assertThat(readiness("READY")).isEqualTo(1.0d);
        assertThat(readiness("BLOCKED")).isZero();
        assertThat(reconciliation("CONFIDENT")).isEqualTo(1.0d);
        assertThat(blocker("orders:unknown_status")).isZero();
        assertThat(marketDataGauge("fresh_symbols")).isEqualTo(1.0d);
    }

    private RuntimeStatusGaugeBinder runtimeStatusGaugeBinder() {
        RuntimeStatusService statusService = new RuntimeStatusService(
                configManager,
                projection,
                reconciliationConfidenceTracker,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new RuntimeStatusGaugeBinder(meterRegistry, statusService);
    }

    private double readiness(String readiness) {
        return meterRegistry.get(RuntimeStatusGaugeBinder.READINESS_STATES).tag("readiness", readiness).gauge().value();
    }

    private double reconciliation(String status) {
        return meterRegistry.get(RuntimeStatusGaugeBinder.RECONCILIATION_STATES).tag("status", status).gauge().value();
    }

    private double blocker(String blocker) {
        return meterRegistry.get(RuntimeStatusGaugeBinder.BLOCKER_STATES).tag("blocker", blocker).gauge().value();
    }

    private double projectionGauge(String kind) {
        return meterRegistry.get(RuntimeStatusGaugeBinder.PROJECTION_STATES).tag("kind", kind).gauge().value();
    }

    private double marketDataGauge(String kind) {
        return meterRegistry.get(RuntimeStatusGaugeBinder.MARKET_DATA_STATES).tag("kind", kind).gauge().value();
    }

    private TradingBotProperties config() {
        TradingBotProperties properties = new TradingBotProperties();
        properties.setVersion(1);
        properties.setBot(new BotProperties("bot-demo-main-usdm-futures-1", "trading-bot-demo-main-usdm-futures", "UTC"));
        ExchangeSectionProperties exchange = new ExchangeSectionProperties();
        exchange.setActive(new ExchangeProperties(PROVIDER, ENVIRONMENT, ACCOUNT, MARKET));
        exchange.setProviders(new ProviderCatalogProperties());
        properties.setExchangeSection(exchange);
        return properties;
    }

    private TradingStateProjection.OrderState order(String clientOrderId, String status) {
        return new TradingStateProjection.OrderState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                "BTCUSDT",
                "cmd-" + clientOrderId,
                clientOrderId,
                "exchange-" + clientOrderId,
                status,
                status,
                "BUY",
                "LIMIT",
                "100.00",
                "0.10",
                "0",
                null,
                null,
                "ORDER_COMMAND",
                "NEW",
                true,
                false,
                null,
                NOW.minusSeconds(10),
                "evt-order-" + clientOrderId
        );
    }

    private TradingStateProjection.PositionState position() {
        return new TradingStateProjection.PositionState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                "BTCUSDT",
                "BOTH",
                "ONE_WAY",
                "0.25",
                "100.00",
                "101.00",
                "1.00",
                "5",
                "CROSSED",
                null,
                "REST_SNAPSHOT",
                false,
                null,
                NOW.minusSeconds(8),
                "evt-position"
        );
    }

    private TradingStateProjection.MarketDataState marketData(Instant updatedAt) {
        return new TradingStateProjection.MarketDataState(
                PROVIDER,
                ENVIRONMENT,
                MARKET,
                "BTCUSDT",
                "BOOK_TICKER",
                "100.00",
                "5",
                "100.10",
                "4",
                updatedAt,
                null,
                null,
                null,
                null,
                Map.of(),
                updatedAt,
                "evt-md"
        );
    }

    private TradingStateProjection.PauseGovernanceState pause() {
        return new TradingStateProjection.PauseGovernanceState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                "ACCOUNT",
                ACCOUNT,
                null,
                "remediation-1",
                "POSITION",
                "PAUSE_ACCOUNT",
                "external_position_change",
                List.of("risk"),
                "automated-policy",
                "risk_control",
                Map.of(),
                true,
                NOW.minusSeconds(20),
                "evt-pause"
        );
    }
}
