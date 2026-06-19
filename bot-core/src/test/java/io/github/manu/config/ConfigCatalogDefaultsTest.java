package io.github.manu.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigCatalogDefaultsTest {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void checked_in_catalog_lists_runtime_overridable_trading_defaults() throws IOException {
        JsonNode catalog = jsonMapper.readTree(resolveCatalogPath().toFile());

        assertBoolean(catalog, "trading.journal.enabled", false);
        assertText(catalog, "trading.journal.directory", "data/journal/trading-events");
        assertBoolean(catalog, "trading.journal.recovery.enabled", false);

        assertBoolean(catalog, "trading.projection.snapshot_store.enabled", false);
        assertText(catalog, "trading.projection.snapshot_store.path", "data/projection/trading-state-snapshot.json");
        assertBoolean(catalog, "trading.projection.jdbc_store.enabled", false);
        assertNull(catalog, "trading.projection.jdbc_store.url");
        assertNull(catalog, "trading.projection.jdbc_store.username");
        assertText(catalog, "trading.projection.jdbc_store.password", "");
        assertText(catalog, "trading.projection.jdbc_store.table_prefix", "trading_projection_");
        assertBoolean(catalog, "trading.projection.jdbc_store.initialize_schema", false);

        assertBoolean(catalog, "trading.messaging.enabled", false);
        assertText(catalog, "trading.messaging.bootstrap_servers", "localhost:19092");
        assertText(catalog, "trading.messaging.schema_registry_url", "http://localhost:18081");
        assertText(catalog, "trading.messaging.client_id_prefix", "trading-bot");
        assertBoolean(catalog, "trading.messaging.topics.auto_create", false);
        assertInt(catalog, "trading.messaging.topics.replication_factor", 1);
        assertBoolean(catalog, "trading.messaging.consumers.enabled", false);
        assertBoolean(catalog, "trading.messaging.consumers.auto_start", false);
        assertText(catalog, "trading.messaging.consumers.group_id_suffix", "dispatcher");
        assertInt(catalog, "trading.messaging.consumers.poll_timeout_millis", 250);

        assertBoolean(catalog, "trading.audit.pause_governance.file_store.enabled", false);
        assertText(catalog, "trading.audit.pause_governance.file_store.path", "data/audit/pause-governance-audit.jsonl");
        assertBoolean(catalog, "trading.audit.pause_governance.jdbc_store.enabled", false);
        assertNull(catalog, "trading.audit.pause_governance.jdbc_store.url");
        assertNull(catalog, "trading.audit.pause_governance.jdbc_store.username");
        assertText(catalog, "trading.audit.pause_governance.jdbc_store.password", "");
        assertText(catalog, "trading.audit.pause_governance.jdbc_store.table_prefix",
                "trading_audit_pause_governance_");
        assertBoolean(catalog, "trading.audit.pause_governance.jdbc_store.initialize_schema", false);

        assertBoolean(catalog, "trading.observability.pause_governance.expiry_monitor.enabled", true);
        assertInt(catalog, "trading.observability.pause_governance.expiry_monitor.interval_millis", 30000);

        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.enabled", false);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.initial_delay_millis", 30000);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.interval_millis", 30000);
        assertText(catalog, "trading.strategy.lfa.signal_runner.strategy_id", "lfa");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.provider");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.environment");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.account");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.market");
        assertText(catalog, "trading.strategy.lfa.signal_runner.lifecycle_state", "STOPPED");
        assertThat(requiredNode(catalog, "trading.strategy.lfa.signal_runner.allowed_lifecycle_states").get(0)
                .asString()).isEqualTo("ACTIVE");
        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.require_warmup_market_data", true);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.min_warmup_market_data_symbols", 1);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.min_warmup_top_of_book_symbols", 1);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.warmup_max_market_data_age_millis", 30000);
        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.use_signal_planner_instrument_universe", true);
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_candidate_market_data_symbols");
        assertText(catalog, "trading.strategy.lfa.signal_runner.min_imbalance_ratio", "1.50");
        assertText(catalog, "trading.strategy.lfa.signal_runner.max_spread_bps", "5");
        assertText(catalog, "trading.strategy.lfa.signal_runner.min_top_of_book_quote_notional", "250");
        assertInt(catalog, "trading.strategy.lfa.signal_runner.max_market_data_age_millis", 30000);
        assertNull(catalog, "trading.strategy.lfa.signal_runner.target_quantity");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.target_notional");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.target_notional_margin_balance_fraction");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.min_allocated_target_notional");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_allocated_target_notional");
        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.reject_missing_allocation_balance", true);
        assertInt(catalog, "trading.strategy.lfa.signal_runner.max_signals_per_run", 1);
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_account_open_positions");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_symbol_open_positions");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_account_position_notional");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_symbol_position_notional");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_account_daily_realized_loss");
        assertNull(catalog, "trading.strategy.lfa.signal_runner.max_symbol_daily_realized_loss");
        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.reject_missing_account_risk_metadata", true);
        assertBoolean(catalog, "trading.strategy.lfa.signal_runner.require_signal_planner_enabled", true);

        assertBoolean(catalog, "trading.execution.risk_gate.target_order.allow_external_remediation_cancel", true);
        assertBoolean(catalog, "trading.execution.risk_gate.target_order.allow_adopted_target_orders", false);
        assertNull(catalog, "trading.execution.signal_planner.instrument_universe.min_top_of_book_quote_notional");
        assertNull(catalog, "trading.execution.signal_planner.instrument_universe.min_daily_quote_volume");
        assertThat(requiredNode(catalog, "trading.execution.signal_planner.instrument_universe.symbol_policies")
                .get(0)
                .path("min_top_of_book_quote_notional")
                .asString()).isEqualTo("250");
        String demoUsdmMarketData =
                "exchange.providers.binance.environments.demo.accounts.main.markets.usdm_futures.market_data";
        assertBoolean(catalog, demoUsdmMarketData + ".derive_streams_from_exchange_metadata", false);
        assertThat(requiredNode(catalog, demoUsdmMarketData + ".derived_stream_templates")).isEmpty();
        assertThat(requiredNode(catalog, demoUsdmMarketData + ".derived_allowed_quote_assets")).isEmpty();
        assertThat(requiredNode(catalog, demoUsdmMarketData + ".derived_allowed_contract_types")).isEmpty();
        assertNull(catalog, demoUsdmMarketData + ".derived_required_status");
        assertNull(catalog, demoUsdmMarketData + ".derived_max_symbols");

        assertBoolean(catalog, "trading.intervention.remediation_orchestrator.enabled", false);
        assertBoolean(catalog,
                "trading.intervention.remediation_orchestrator.operator_review_acknowledgement_enabled",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_orchestrator.order_adoption_acknowledgement_enabled",
                false);
        assertInt(catalog, "trading.intervention.remediation_orchestrator.max_tracked_decision_ids", 100000);

        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.enabled", false);
        assertInt(catalog, "trading.intervention.automated_remediation_runner.interval_millis", 30000);
        assertInt(catalog, "trading.intervention.automated_remediation_runner.initial_delay_millis", 30000);
        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.publish_decisions", true);
        assertBoolean(catalog, "trading.intervention.automated_remediation_runner.execute_remediation", true);
        assertBoolean(catalog,
                "trading.intervention.automated_remediation_runner.require_target_reconciliation_confidence",
                true);
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.provider");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.environment");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.account");
        assertNull(catalog, "trading.intervention.automated_remediation_runner.target.market");
    }

    @Test
    void checked_in_catalog_keeps_executor_exchange_execution_as_explicit_demo_live_override() throws IOException {
        JsonNode catalog = jsonMapper.readTree(resolveCatalogPath().toFile());

        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.enabled", false);
        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.exchange_execution_enabled", false);
        assertBoolean(catalog, "trading.intervention.remediation_executor_policy.report_only", true);
        assertThat(requiredNode(catalog, "trading.intervention.remediation_executor_policy.allowed_operations"))
                .as("trading.intervention.remediation_executor_policy.allowed_operations")
                .isEmpty();
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.one_way_reduce_only_enabled",
                false);
        assertText(catalog, "trading.intervention.remediation_executor_policy.position_order_policy.provider", "binance");
        assertText(catalog, "trading.intervention.remediation_executor_policy.position_order_policy.market",
                "usdm_futures");
        assertText(catalog, "trading.intervention.remediation_executor_policy.position_order_policy.position_side",
                "BOTH");
        assertText(catalog, "trading.intervention.remediation_executor_policy.position_order_policy.order_type",
                "MARKET");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.require_reduce_only",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.require_close_position_false",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.hedge_mode_execution_enabled",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.hedge_position_order_enabled",
                false);
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.allowed_symbols"))
                .as("trading.intervention.remediation_executor_policy.position_order_policy.allowed_symbols")
                .isEmpty();
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_position_quantity");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.chunk_close_when_max_quantity_exceeded",
                false);
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_position_notional");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.reject_unbounded_position_notional",
                true);
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.required_margin_type");
        assertText(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.required_position_mode",
                "HEDGE");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.min_leverage");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_leverage");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_account_position_notional");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_position_notional");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_account_unrealized_loss");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_unrealized_loss");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.min_account_margin_balance");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_account_margin_drawdown_fraction");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_account_margin_utilization");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_account_daily_realized_loss");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_daily_realized_loss");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.position_order_policy.reject_missing_account_risk_metadata",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.enabled",
                false);
        assertText(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.provider",
                "binance");
        assertText(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.market",
                "usdm_futures");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_bot_created_orders",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_adopted_orders",
                false);
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_symbols"))
                .as("trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_symbols")
                .isEmpty();
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_order_types"))
                .as("trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_order_types")
                .hasSize(1);
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_fields"))
                .as("trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_fields")
                .hasSize(2);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_quantity_increase",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_quantity_decrease",
                true);
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_quantity_increase_fraction");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_quantity_decrease_fraction");
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_price_drift_fraction");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.cancel_replace_on_unsupported_change",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.reject_stale_projection",
                true);
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_projection_age_millis");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.require_open_order_status",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.require_exchange_order_id",
                false);
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_statuses"))
                .as("trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_statuses")
                .hasSize(2);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.enabled",
                false);
        assertText(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.provider",
                "binance");
        assertText(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.market",
                "usdm_futures");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.preserve_by_default",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allow_cancel",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allow_amend",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allow_replace",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.rollback_on_ambiguous_outcome",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.reject_stale_projection",
                true);
        assertNull(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.max_projection_age_millis");
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.require_open_order_status",
                true);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.require_exchange_order_id",
                false);
        assertBoolean(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.reject_pending_or_unknown_modify",
                true);
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allowed_symbols"))
                .as("trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allowed_symbols")
                .isEmpty();
        assertThat(requiredNode(catalog,
                "trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allowed_statuses"))
                .as("trading.intervention.remediation_executor_policy.adopted_order_lifecycle_policy.allowed_statuses")
                .hasSize(2);
    }

    private void assertBoolean(JsonNode root, String path, boolean expected) {
        assertThat(requiredNode(root, path).asBoolean()).as(path).isEqualTo(expected);
    }

    private void assertInt(JsonNode root, String path, int expected) {
        assertThat(requiredNode(root, path).asInt()).as(path).isEqualTo(expected);
    }

    private void assertText(JsonNode root, String path, String expected) {
        assertThat(requiredNode(root, path).asString()).as(path).isEqualTo(expected);
    }

    private void assertNull(JsonNode root, String path) {
        assertThat(requiredNode(root, path).isNull()).as(path).isTrue();
    }

    private JsonNode requiredNode(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current.get(segment);
            assertThat(current).as(path).isNotNull();
        }
        return current;
    }

    private Path resolveCatalogPath() {
        Path cwdCatalog = Path.of("config", "catalog.json");
        if (Files.exists(cwdCatalog)) {
            return cwdCatalog;
        }

        Path parentCatalog = Path.of("..", "config", "catalog.json").normalize();
        if (Files.exists(parentCatalog)) {
            return parentCatalog;
        }

        throw new IllegalStateException("Unable to locate checked-in config/catalog.json");
    }
}
