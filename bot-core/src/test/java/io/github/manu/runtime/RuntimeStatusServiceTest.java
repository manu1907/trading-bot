package io.github.manu.runtime;

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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-18T21:30:00Z");
    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usd_m_futures";

    private final ConfigManager configManager = new ConfigManager();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker =
            new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
    private final RuntimeStatusService service = new RuntimeStatusService(
            configManager,
            projection,
            reconciliationConfidenceTracker,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void reports_ready_when_reconciliation_is_confident_and_market_data_is_fresh() {
        configManager.setConfig(config());
        reconciliationConfidenceTracker.record(confidentObservation("orders"));
        projection.restore(snapshot(
                List.of(freshMarketData("BTCUSDT"), freshMarketData("ETHUSDT")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        RuntimeStatusService.RuntimeStatus status = service.status(RuntimeStatusService.RuntimeStatusRequest.defaults());

        assertThat(status.readiness()).isEqualTo(RuntimeStatusService.RuntimeReadiness.READY);
        assertThat(status.target().targetId()).isEqualTo("trading-bot-demo-main-usdm-futures");
        assertThat(status.target().provider()).isEqualTo(PROVIDER);
        assertThat(status.reconciliation().status()).isEqualTo("CONFIDENT");
        assertThat(status.projection().marketDataSymbols()).isEqualTo(2);
        assertThat(status.projection().freshMarketDataSymbols()).isEqualTo(2);
        assertThat(status.blockers()).isEmpty();
    }

    @Test
    void reports_blocked_when_projection_contains_unsafe_runtime_state() {
        configManager.setConfig(config());
        reconciliationConfidenceTracker.record(degradedObservation("orders"));
        projection.restore(snapshot(
                List.of(staleMarketData("BTCUSDT")),
                List.of(order("BTCUSDT", "client-unknown", "UNKNOWN"), order("ETHUSDT", "client-pending", "COMMAND_RECEIVED")),
                List.of(position("SOLUSDT", "0.25", true)),
                List.of(pause()),
                List.of(strategyLifecycle("lfa-top-book-imbalance", "PAUSED"))
        ));

        RuntimeStatusService.RuntimeStatus status = service.status(new RuntimeStatusService.RuntimeStatusRequest(
                null,
                null,
                null,
                null,
                60_000L,
                1,
                "lfa-top-book-imbalance"
        ));

        assertThat(status.readiness()).isEqualTo(RuntimeStatusService.RuntimeReadiness.BLOCKED);
        assertThat(status.projection().unknownOrderStatuses()).isEqualTo(1);
        assertThat(status.projection().unresolvedOrderCommands()).isEqualTo(1);
        assertThat(status.projection().externalPositionInterventions()).isEqualTo(1);
        assertThat(status.projection().activePauses()).isEqualTo(1);
        assertThat(status.projection().staleMarketDataSymbols()).isEqualTo(1);
        assertThat(status.projection().strategyLifecycle().lifecycleState()).isEqualTo("PAUSED");
        assertThat(status.blockers())
                .containsExactly(
                        "reconciliation:degraded",
                        "orders:unknown_status",
                        "orders:unresolved_command",
                        "interventions:external_positions",
                        "governance:active_pause",
                        "market_data:fresh_symbols_below_minimum"
                );
    }

    @Test
    void explicit_target_request_can_inspect_a_non_active_runtime_scope() {
        configManager.setConfig(config());
        reconciliationConfidenceTracker.record(new ReconciliationObservation(
                PROVIDER,
                "real",
                ACCOUNT,
                MARKET,
                TradingEventType.ORDER_RESULT,
                "orders",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        projection.restore(snapshot(List.of(marketData("real", "BTCUSDT", NOW.minusSeconds(5))), List.of(), List.of(), List.of(), List.of()));

        RuntimeStatusService.RuntimeStatus status = service.status(new RuntimeStatusService.RuntimeStatusRequest(
                PROVIDER,
                "real",
                ACCOUNT,
                MARKET,
                60_000L,
                1,
                null
        ));

        assertThat(status.readiness()).isEqualTo(RuntimeStatusService.RuntimeReadiness.READY);
        assertThat(status.target().environment()).isEqualTo("real");
        assertThat(status.target().targetId()).isEqualTo("trading-bot-demo-main-usdm-futures");
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

    private ReconciliationObservation confidentObservation(String entityKey) {
        return new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.ORDER_RESULT,
                entityKey,
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        );
    }

    private ReconciliationObservation degradedObservation(String entityKey) {
        return new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.ORDER_RESULT,
                entityKey,
                ReconciliationConfidenceStatus.MISMATCH,
                List.of()
        );
    }

    private TradingStateSnapshot snapshot(
            List<TradingStateProjection.MarketDataState> marketData,
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.PositionState> positions,
            List<TradingStateProjection.PauseGovernanceState> pauses,
            List<TradingStateProjection.StrategyLifecycleState> lifecycles
    ) {
        return new TradingStateSnapshot(
                List.of(),
                positions,
                orders,
                List.of(),
                marketData,
                List.of(),
                List.of(),
                List.of(),
                pauses,
                lifecycles,
                List.of()
        );
    }

    private TradingStateProjection.MarketDataState freshMarketData(String symbol) {
        return marketData(ENVIRONMENT, symbol, NOW.minusSeconds(5));
    }

    private TradingStateProjection.MarketDataState staleMarketData(String symbol) {
        return marketData(ENVIRONMENT, symbol, NOW.minusSeconds(120));
    }

    private TradingStateProjection.MarketDataState marketData(String environment, String symbol, Instant updatedAt) {
        return new TradingStateProjection.MarketDataState(
                PROVIDER,
                environment,
                MARKET,
                symbol,
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
                "evt-md-" + environment + "-" + symbol
        );
    }

    private TradingStateProjection.OrderState order(String symbol, String clientOrderId, String status) {
        return new TradingStateProjection.OrderState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                symbol,
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

    private TradingStateProjection.PositionState position(String symbol, String amount, boolean externalIntervention) {
        return new TradingStateProjection.PositionState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                symbol,
                "BOTH",
                "ONE_WAY",
                amount,
                "100.00",
                "101.00",
                "1.00",
                "5",
                "CROSSED",
                null,
                "REST_SNAPSHOT",
                externalIntervention,
                externalIntervention ? "external_position_change" : null,
                NOW.minusSeconds(8),
                "evt-position-" + symbol
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

    private TradingStateProjection.StrategyLifecycleState strategyLifecycle(String strategyId, String state) {
        return new TradingStateProjection.StrategyLifecycleState(
                "lifecycle-1",
                strategyId,
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                "ACTIVE",
                state,
                "operator",
                "controlled pause",
                Map.of(),
                NOW.minusSeconds(15),
                "evt-lifecycle"
        );
    }
}
