package io.github.manu.runtime;

import io.github.manu.config.properties.BotProperties;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.ExchangeSectionProperties;
import io.github.manu.config.properties.ProviderCatalogProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.events.TradingEventType;
import io.github.manu.intervention.InterventionProperties;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

class RuntimeStatusControllerTest {

    private static final Instant NOW = Instant.parse("2026-06-18T21:30:00Z");

    private final ConfigManager configManager = new ConfigManager();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker =
            new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
    private final RuntimeStatusService statusService = new RuntimeStatusService(
            configManager,
            projection,
            reconciliationConfidenceTracker,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final RuntimeStatusController controller = new RuntimeStatusController(
            statusService,
            new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
    );
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    @Test
    void returns_runtime_status_when_operator_token_matches() {
        configManager.setConfig(config());
        reconciliationConfidenceTracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
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
                List.of(marketData()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        client.get()
                .uri("/internal/runtime/status")
                .header(RuntimeStatusController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.readiness")
                .isEqualTo("READY")
                .jsonPath("$.target.provider")
                .isEqualTo("binance")
                .jsonPath("$.projection.freshMarketDataSymbols")
                .isEqualTo(1);
    }

    @Test
    void rejects_runtime_status_when_operator_token_is_invalid() {
        configManager.setConfig(config());

        client.get()
                .uri("/internal/runtime/status")
                .header(RuntimeStatusController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    @Test
    void rejects_invalid_thresholds() {
        configManager.setConfig(config());

        client.get()
                .uri("/internal/runtime/status?maxMarketDataAgeMillis=0")
                .header(RuntimeStatusController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("bad_request");
    }

    private TradingBotProperties config() {
        TradingBotProperties properties = new TradingBotProperties();
        properties.setVersion(1);
        properties.setBot(new BotProperties("bot-demo-main-usdm-futures-1", "trading-bot-demo-main-usdm-futures", "UTC"));
        ExchangeSectionProperties exchange = new ExchangeSectionProperties();
        exchange.setActive(new ExchangeProperties("binance", "demo", "main", "usd_m_futures"));
        exchange.setProviders(new ProviderCatalogProperties());
        properties.setExchangeSection(exchange);
        return properties;
    }

    private TradingStateProjection.MarketDataState marketData() {
        return new TradingStateProjection.MarketDataState(
                "binance",
                "demo",
                "usd_m_futures",
                "BTCUSDT",
                "BOOK_TICKER",
                "100.00",
                "5",
                "100.10",
                "4",
                NOW.minusSeconds(5),
                null,
                null,
                null,
                null,
                Map.of(),
                NOW.minusSeconds(5),
                "evt-md"
        );
    }
}
