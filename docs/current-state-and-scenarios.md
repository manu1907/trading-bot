# Current State And Supported Scenarios

This document describes the bot as implemented in this repository today. It is not a profitability claim and it is not a readiness certificate for real-money autonomous trading. Demo and real use the same codebase; the active behavior is selected by the effective merged configuration: catalog defaults execute unless overridden by environment/application config, runtime target overrides, environment variables, or command-line arguments.

The intended product direction is a professional autonomous order and position
manager: it should open, amend, reduce, close, pause, recover, and reconcile
positions through policy-gated automation while minimizing avoidable risk and
loss. The current codebase implements only the supported scenarios listed in
this document. It must not be described as a completed profit-maximizing trading
manager yet.

The core strategy signal planner now includes a first provider-agnostic
exit/reduction safety guard. `EXIT_LONG`, `EXIT_SHORT`, `REDUCE_LONG`, and
`REDUCE_SHORT` signals can become reduce-only order commands only when they
carry an explicit `targetQuantity`, or when they are unsized
`close_position=true` signals. Quote-notional reduce-only signals are suppressed
because they do not express a bounded position-size reduction. Close-position
signals with explicit quantity or notional sizing are also suppressed so full
close intents cannot be mixed with partial-size semantics.
The order risk gate enforces the same command-shape rule centrally for every
non-cancel order command source before provider mapping.

## Active First-Start Target

The checked-in first-start runtime target is:

- Provider: `binance`
- Environment: `demo`
- Account: `main`
- Market: `usdm_futures`
- Runtime file: `config/runtime/live/binance/demo/main/usdm_futures.json`

That runtime enables the live demo execution path for the currently supported remediation operations only. It does not enable every remediation action and it does not enable real-environment trading.

## Strategy Instrument Universe

Strategy signal planning now has a runtime-configurable instrument-universe
admission gate under `trading.execution.signal_planner.instrument_universe`.
When enabled, the planner suppresses new strategy order commands for excluded
symbols, symbols outside the required include list, disabled symbol policies, or
symbols that are not explicitly marked promotion-ready when promotion readiness
is required. A matching symbol policy can also cap strategy order notional with
`max_order_notional`; unbounded or oversized orders are suppressed before
command publication. The planner can also refresh provider exchange metadata
before planning and require the resolved symbol to be present, currently
tradable, compatible with the configured quote asset and contract type, and
compatible with the order type being planned.
Latest market-data projection is now also available to the planner. When
configured, a strategy signal is suppressed unless recent market data exists,
top-of-book bid/ask are present, the top-of-book snapshot is fresh enough, and
the effective spread is below the configured universe or symbol-policy
`max_spread_bps`. The planner can also require a minimum projected
top-of-book quote notional using `min_top_of_book_quote_notional`; the effective
depth is the smaller quote notional available on the best bid and best ask.

The checked-in catalog currently provides this bounded high-liquidity USD-M
futures candidate baseline:

`BTCUSDT`, `ETHUSDT`, `BNBUSDT`, `SOLUSDT`, `XRPUSDT`, `DOGEUSDT`,
`ADAUSDT`, `LINKUSDT`, `AVAXUSDT`, `BCHUSDT`, `LTCUSDT`, `TRXUSDT`, and
`DOTUSDT`.

The catalog also owns the matching USD-M futures market-data stream defaults
(`bookTicker`, `aggTrade`, and `kline_1d` for each baseline symbol) and
reconciliation open-order symbol defaults for demo and real. The runtime file
enables those services and can override operational cadence or risk limits, but
it should not duplicate the baseline universe or operational coverage lists. The
checked-in demo runtime also enables Binance metadata-derived market-data stream
coverage for `TRADING` `USDT` `PERPETUAL` instruments, rendering
`bookTicker`, `aggTrade`, and `kline_1d` stream templates for up to 250
exchange-polled symbols while deduplicating them with the catalog baseline.
During scheduled exchange-metadata refresh, core invokes a provider-agnostic
metadata-dependent runtime hook; Binance reuses refreshed metadata to re-render
the derived stream plan and reconnects the market-data websocket only when the
effective stream set, route, or connection mode changes.

The checked-in demo runtime currently enables that catalog baseline and sets:

- `refresh_exchange_metadata_before_planning=true`
- `require_exchange_metadata=true`
- inherits catalog `require_included_symbol=false`
- `allowed_quote_assets=["USDT"]`
- `allowed_contract_types=["PERPETUAL"]`
- inherits catalog `max_eligible_symbols=null`
- `require_market_data=true`
- `require_top_of_book=true`
- `max_market_data_age_millis=30000`
- `max_spread_bps="5"`
- `min_top_of_book_quote_notional="250"`
- `min_daily_quote_volume="100000000"`
- `max_order_notional="50"` per initial symbol policy

The resolver returns the eligible, exchange-confirmed subset of active provider
metadata after applying quote-asset, contract-type, market-data, spread, depth,
and projected daily quote-volume gates. The catalog list remains the
source-controlled first-start baseline for streams, reconciliation coverage, and
symbol policies, but the effective demo config does not require a strategy
candidate to appear in that static include list. When a strategy signal does not
explicitly name a symbol, the planner can rotate across admissible universe
candidates and select the highest ranked symbol using projected market-data
freshness, daily quote volume, daily trade count, taker-buy quote volume,
provider capability score, reconciliation availability, top-of-book spread, and
top-of-book quote depth. Explicit signal symbols remain authoritative and are
still rejected if any universe, projection, reconciliation, pause, or order-limit
gate fails. The LFA runner now adds a first expected-edge score for emitted
signals and multiplies it by a projected risk and money-management fit score,
plus a first top-of-book expected-profit score, but that is not yet a calibrated
realized-PnL model, portfolio allocator, or position lifecycle.

## LFA Strategy Signal Analysis

The LFA strategy module now includes a conservative projected top-of-book
imbalance analyzer. It accepts projected market-data states for a target
provider, environment, account, and market, then evaluates each instrument
independently. A signal emitted by this analyzer always carries the selected
instrument symbol explicitly.

The analyzer suppresses an instrument instead of emitting an ambiguous signal
when:

- the projected market-data target does not match the request
- the symbol is missing
- the top-of-book timestamp is missing or stale
- best bid, best ask, bid quantity, or ask quantity is missing or non-positive
- best ask is below best bid
- spread is wider than the configured maximum basis-points threshold
- effective top-of-book quote notional is below the configured minimum depth
- bid/ask quote-notional imbalance does not meet the configured ratio

When bid-side quote notional dominates ask-side quote notional, the analyzer
emits an `ENTER_LONG` signal with a limit price at the best ask. When ask-side
quote notional dominates bid-side quote notional, it emits an `ENTER_SHORT`
signal with a limit price at the best bid. Signal features include `LIMIT` and
`GTC`, while signal attributes include source, reason, market-data age, spread,
bid/ask quote notional, effective quote depth, and imbalance ratio.

The strategy module also has a config-gated LFA signal runner under
`trading.strategy.lfa.signal_runner`. When enabled, it periodically reads the
current projected market-data snapshot, requires the configured lifecycle state
to be allowed, requires projected market-data and top-of-book warm-up thresholds
to be met, optionally filters candidate symbols through the core signal-planner
instrument universe, ranks candidate market data by projected spread,
projected daily `quoteVolume`, projected daily trade count, projected daily
taker-buy quote volume, provider capability score, reconciliation availability, top-of-book quote
depth, freshness, and symbol, optionally caps candidate market data before
analysis, runs the analyzer, records `lfa_provider_capability_score` on signals
when exchange metadata is available, records
`lfa_reconciliation_availability_score` when symbol-level reconciliation
observations exist, computes `lfa_expected_edge_score` from analyzer confidence,
imbalance, spread, depth, projected liquidity/trade statistics, freshness,
provider capability, reconciliation availability, and projected risk and
money-management fit plus an expected-profit score, records
`lfa_risk_money_management_fit_score` from remaining account/symbol order
capacity, position capacity, notional capacity, loss capacity, and margin-health
capacity, records `lfa_expected_profit_bps`,
`lfa_expected_profit_notional` when signal notional is known,
`lfa_expected_profit_score` from a top-of-book imbalance/spread estimate, and stamps `signal_ttl_millis` plus `signal_expires_at` from the configured runner TTL, blocks
candidate signals below configured `min_expected_profit_bps`,
`min_expected_profit_score`, or `min_risk_money_management_fit_score` floors, sorts analyzed signals by expected edge before applying `max_signals_per_run`, can allocate a total run target notional from
the latest projected account margin balance, and splits it across candidate publish slots
by configured `allocation_weighting_mode` (`EQUAL`, `CONFIDENCE`, or
`MARKET_QUALITY`) when configured. The optional `max_strategy_run_notional` cap
limits total notional published by one runner cycle: margin-balance allocation is
reduced to the cap automatically, while fixed/unallocated sizing blocks when the
runner cannot prove or safely resize total run notional. `MARKET_QUALITY` includes projected daily
`quoteVolume`, projected daily trade count, and projected daily taker-buy quote
volume against their configured market-quality baselines in addition to
confidence, imbalance, spread, depth, and freshness. The runner applies account
and symbol budget gates, publishes symbol-keyed `STRATEGY_SIGNAL` events through
the core event bus, and
relies on the core signal planner, order risk gate, idempotency, reconciliation
confidence, and execution pipeline for downstream order admission. Lifecycle states are validated
as `STARTING`, `ACTIVE`, `PAUSED`, `DRAINING`, `STOPPED`, or
`EMERGENCY_STOP`; only `ACTIVE` can publish new signals, and non-entry states
fail closed even if accidentally included in `allowed_lifecycle_states`.
Lifecycle transitions are policy-gated by the catalog-backed
`allowed_lifecycle_transitions` matrix, and leaving `EMERGENCY_STOP` is blocked
unless `allow_emergency_stop_reactivation=true`; the default emergency recovery
path only permits moving to `STOPPED`. `DRAINING` now reports projected open order and open position counts for the runner target and rejects `DRAINING -> STOPPED` until both counts are zero. Lifecycle and warm-up blockers include `lfa_lifecycle:starting`,
`lfa_lifecycle:paused`, `lfa_lifecycle:draining`,
`lfa_lifecycle:stopped`, `lfa_lifecycle:emergency_stop`,
`lfa_lifecycle:not_allowed`, `lfa_warmup:market_data_symbols_below_min`, and
`lfa_warmup:top_of_book_symbols_below_min`. Budget blockers include
account/symbol open-order count caps, account/symbol open-order notional caps,
account/symbol open-position caps, account/symbol position notional caps,
account/symbol current unrealized-loss caps, account margin-balance floor,
account margin-balance high-watermark drawdown cap, account margin-utilization
cap, account/symbol daily realized-loss caps, missing notional, account-risk,
margin-balance, max-margin-balance, maintenance-margin, unrealized-PnL, or
daily-PnL metadata when strict metadata rejection is enabled, and unbounded
candidate signal notional when a notional cap is configured. Budget blockers also
include configured expected-profit bps, expected-profit score, or risk-fit score floors. Open-order notional
caps use projected remaining order quantity and limit price, and block with
`lfa_budget:open_order_notional_metadata_missing` when relevant open-order
metadata is unusable under strict metadata rejection. Allocation blockers include
`lfa_allocation:account_margin_balance_missing`,
`lfa_allocation:max_strategy_run_notional`,
`lfa_allocation:strategy_run_notional_unbounded`,
`lfa_allocation:target_notional_below_min`, and
`lfa_allocation:target_notional_non_positive`. Runner reconciliation blockers
include `lfa_reconciliation:no_observations` and `lfa_reconciliation:degraded`
when `require_reconciliation_confidence=true`. The catalog value for the runner
currently executes as `enabled=false`, `lifecycle_state=STOPPED`,
one-symbol projected-data warm-up,
`use_signal_planner_instrument_universe=true`, no LFA-specific candidate cap,
unset allocation fraction/min/max/run-notional cap, and `reject_missing_allocation_balance=true`
with `allocation_weighting_mode=EQUAL`,
`market_quality_quote_volume_baseline=100000000`,
`market_quality_trade_count_baseline=100000`,
`market_quality_taker_buy_quote_volume_baseline=50000000`, and
`expected_profit_bps_baseline=1`, `expected_profit_score_cap=10`, `signal_ttl_millis=30000`, unset
`min_expected_profit_bps`, unset `min_expected_profit_score`, unset
`min_risk_money_management_fit_score`, and `require_reconciliation_confidence=true`
unless overridden. LFA signal-runner notional, current unrealized-loss,
account-margin-health, and daily realized-loss caps default to `null` until
calibrated by a runtime override. The checked-in demo runtime overrides target, bootstrap sizing,
open-position caps, `lifecycle_state=PAUSED`, three-symbol warm-up thresholds,
`max_candidate_market_data_symbols=13`, `signal_ttl_millis=15000`,
`target_notional_margin_balance_fraction=0.01`, and
`max_allocated_target_notional=50`, `max_strategy_run_notional=50`, and
`allocation_weighting_mode=MARKET_QUALITY`, plus
`min_expected_profit_bps=1`, `min_expected_profit_score=1`, and
`min_risk_money_management_fit_score=0.25`, but does not
override `enabled`, so the effective demo runner remains disabled because the
full position-lifecycle and broader money-management layers are not complete
enough for autonomous strategy execution.

When the LFA runner bean and internal operator API are enabled, the operator API
also exposes `GET /internal/strategy/lfa/lifecycle` and
`POST /internal/strategy/lfa/lifecycle` with the same `X-Operator-Token`
authentication used by intervention endpoints. These endpoints inspect and change the effective LFA lifecycle state without
changing code, subject to the configured transition policy. Successful transitions publish a durable `STRATEGY_LIFECYCLE`
event, apply it to the local projection, and are recoverable through the normal
journal/projection replay path. Lifecycle status also reports drain readiness from
projected open orders and positions. The durable lifecycle event records projected
open order count, open position count, drain readiness, allowed next states, and
emergency-stop transition/reactivation flags as audit evidence. On restart, the
runner refreshes from projected strategy lifecycle state before status checks or
scheduled signal runs.

This is now strategy-side signal generation plus a controlled live publication
hook with a first projected account-margin allocation layer that can weight
capital toward stronger liquid signals and a first auditable expected-edge
signal ordering layer that includes projected risk and money-management fit plus
a first top-of-book expected-profit estimate. It is not yet the full portfolio,
position-management, calibrated realized-PnL, risk-adjusted-return, or
money-management selector needed for production autonomous trading.

## Runtime Policy Boundary

The executor remains policy-gated. A plan must pass projection identity checks, freshness checks, operation allowlists, environment checks, executor policy checks, and the order risk gate before exchange submission.

The checked-in demo runtime currently enables:

- External-order `CANCEL_ORDER`
- One-way external-position `CLOSE_POSITION`
- One-way external-position `REDUCE_POSITION`
- Managed-order `AMEND_ORDER`

The position-order policy for the first-start demo runtime also restricts automated position remediation to:

- `allowed_symbols=["BTCUSDT","ETHUSDT"]`
- `max_position_quantity="0.001"`
- `chunk_close_when_max_quantity_exceeded=true`
- `max_position_notional="250"`
- `required_margin_type="cross"`
- `min_leverage="1"`
- `max_leverage="5"`
- inherited `max_account_position_notional=null`
- inherited `max_symbol_position_notional=null`
- inherited `max_account_unrealized_loss=null`
- inherited `max_symbol_unrealized_loss=null`
- inherited `min_account_margin_balance=null`
- inherited `max_account_margin_drawdown_fraction=null`
- inherited `max_account_margin_utilization=null`
- inherited `max_account_daily_realized_loss=null`
- inherited `max_symbol_daily_realized_loss=null`
- inherited `reject_unbounded_position_notional=true`
- inherited `reject_missing_account_risk_metadata=true`

The managed-order amendment policy for the first-start demo runtime restricts automated amendments to:

- `enabled=true`
- `allowed_symbols=["BTCUSDT","ETHUSDT"]`
- `allowed_order_types=["LIMIT"]`
- `allowed_fields=["PRICE","QUANTITY"]`
- `allow_quantity_increase=false`
- `allow_quantity_decrease=true`
- `max_quantity_decrease_fraction="0.50"`
- `max_price_drift_fraction="0.02"`
- `reject_stale_projection=true`
- `max_projection_age_millis=30000`
- `require_open_order_status=true`
- `require_exchange_order_id=false`
- `allowed_statuses=["ACCEPTED","PARTIALLY_FILLED"]`
- inherited `allow_bot_created_orders=true`
- inherited `allow_adopted_orders=false`

The first-start demo runtime also enables adopted-order lifecycle policy for:

- `allowed_symbols=["BTCUSDT","ETHUSDT"]`
- `allow_cancel=true`
- `allow_amend=true`
- `reject_stale_projection=true`
- `max_projection_age_millis=30000`
- inherited `preserve_by_default=true`
- inherited `allow_replace=false`
- inherited `require_open_order_status=true`
- inherited `reject_pending_or_unknown_modify=true`

## Supported External Order Scenarios

The bot can project external or manually created orders as interventions. It can recommend and decide remediation, then build executor command plans.

Supported exchange-executable order remediation today:

- External order close: an external order with a matching projected intervention can produce a `CANCEL_ORDER` plan.
- The cancel plan routes through `OrderExecutionPipeline` when executor policy is enabled, exchange execution is enabled, report-only is false, the operation is allowlisted, and the risk gate accepts the command.
- Cancel commands are allowed even under pause governance so the bot can reduce existing order risk.
- Managed order amendment: an unresolved managed-order intervention with an `AMEND` remediation decision can produce an `AMEND_ORDER` plan when `managed_order_amendment_policy` admits the provider, market, symbol, ownership, order type, fields, quantity direction, drift, projection freshness, status, and target identity.
- A qualified amendment is submitted as a `MODIFY` command through `OrderExecutionPipeline`. The command uses the projected target client/exchange identity, projected side, projected order type, requested or retained price, and requested or retained quantity.
- Managed amendment commands are still checked by the order risk gate and provider preflight. Binance futures preflight validates `MODIFY` target identity, side, type, quantity, and price before gateway submission.
- A target with a pending `MODIFY` command or an unknown `MODIFY` result is blocked from repeat amendment until reconciliation updates the projection. The planner reports `managed_order_amendment_modify_pending_reconciliation` or `managed_order_amendment_modify_unknown_reconciliation_required`.
- Remediation executor reports now include compact `executor_plan_summary`,
  `executor_target_summary`, and `executor_policy_summary` attributes for
  operator/API audit. Reports also include
  `executor_ambiguous_outcome_detected`,
  `executor_ambiguous_outcome_reason`, and
  `executor_ambiguous_outcome_action=reconcile_before_retry` when an unknown,
  pending-reconciliation, or ambiguous condition is detected.
- Provider gateways can reject approved commands during preflight before gateway submission. Binance uses this for `NEW` order capability and exchange-filter validation, `CANCEL` target identity validation, and futures `MODIFY` target/parameter validation.

Supported non-exchange order remediation today:

- External order adoption: an order-scope `ADOPT` remediation decision can publish an auditable intervention acknowledgement when `trading.intervention.remediation_orchestrator.enabled=true` and `order_adoption_acknowledgement_enabled=true`.
- Adoption acknowledgement replay marks the projected external order as bot-managed and clears the unresolved external-order intervention. It does not submit, amend, or cancel an exchange order.
- Adopted orders remain protected after ownership transfer. The order risk gate treats a managed target with no bot command id as adopted and blocks ordinary target-order commands unless `trading.execution.risk_gate.target_order.allow_adopted_target_orders=true`. Policy-qualified adopted cancels can pass only when `adopted_order_lifecycle_policy.allow_cancel=true` and the executor command carries matching lifecycle metadata. Policy-qualified adopted amendments can pass only when both `managed_order_amendment_policy.allow_adopted_orders=true` and `adopted_order_lifecycle_policy.allow_amend=true`. Adopted pending/unknown modify outcomes are blocked with explicit reconciliation action metadata, rollback policy state, and rollback blocker metadata.

Current non-executable order intents:

- `ADOPT`
- `IGNORE`
- order amendment that requires cancel/replace fallback, order-type changes, unsupported fields, stale projection, unmanaged/adopted ownership outside policy, or symbols/types outside policy
- strategy replan beyond command planning metadata

## Supported External Position Scenarios

The bot can project external or manually created positions as interventions. It can recommend close, reduce, hedge, replan, pause, or operator-review actions depending on policy and projected state.

Supported exchange-executable one-way position remediation today:

- One-way `CLOSE`: projected `positionSide=BOTH` can produce a bounded opposite-side `NEW MARKET` order for the projected absolute amount.
- One-way `REDUCE`: projected `positionSide=BOTH` can produce a bounded opposite-side `NEW MARKET` order when the remediation decision provides `reduce_quantity` or `reduce_fraction`.
- One-way commands use `reduceOnly=true`, `closePosition=false`, `positionSide=BOTH`, and `position_execution_mode=one_way_reduce_only`.
- Oversized full `CLOSE` can be converted into a capped close chunk when `chunk_close_when_max_quantity_exceeded=true`; the next scheduled remediation tick can continue reducing from the updated projection.
- Explicit `REDUCE` decisions do not chunk. They remain blocked if the requested size exceeds `max_position_quantity`.

Supported config-gated hedge-mode remediation today:

- Hedge-mode `CLOSE` and bounded `REDUCE` can become exchange-executable for projected `positionSide=LONG` or `SHORT` only when `position_order_policy.hedge_mode_execution_enabled=true`.
- Hedge-mode commands use the opposite side, `MARKET`, projected `positionSide`, bounded quantity, `reduceOnly=false`, and `closePosition=false`.
- Position `HEDGE` and `HEDGE_OR_REPLAN` can construct opposite-position-side hedge-mode `MARKET` orders only when both `position_order_policy.hedge_mode_execution_enabled=true` and `position_order_policy.hedge_position_order_enabled=true`.
- Hedge-mode close/reduce and hedge-order plans require projected account position-mode proof matching `position_order_policy.required_position_mode=HEDGE`; missing or mismatched metadata blocks exchange execution.
- The checked-in demo runtime keeps hedge-mode close/reduce and hedge-order execution disabled.

Position plans remain non-executable when any configured policy gate fails, including:

- symbol not in `allowed_symbols`
- target quantity above `max_position_quantity`
- estimated notional above `max_position_notional`
- missing or invalid mark price while `reject_unbounded_position_notional=true`
- projected margin type missing while `required_margin_type` is configured and `reject_missing_account_risk_metadata=true`
- projected margin type not matching `required_margin_type`
- projected leverage missing while leverage bounds are configured and `reject_missing_account_risk_metadata=true`
- projected leverage below `min_leverage`
- projected leverage above `max_leverage`
- projected gross account position notional above `max_account_position_notional` when the plan would not reduce current account exposure
- projected gross symbol position notional above `max_symbol_position_notional` when the plan would not reduce current symbol exposure
- missing or invalid projected position mark price while exposure caps are configured and `reject_unbounded_position_notional=true`
- current account unrealized loss above `max_account_unrealized_loss` for non-reducing hedge plans
- current symbol unrealized loss above `max_symbol_unrealized_loss` for non-reducing hedge plans
- missing or invalid projected unrealized PnL while unrealized-loss caps are configured and `reject_missing_account_risk_metadata=true`
- current account margin balance below `min_account_margin_balance` for non-reducing hedge plans
- projected account margin risk missing or invalid while `min_account_margin_balance` is configured and `reject_missing_account_risk_metadata=true`
- current account margin-balance drawdown from projected high-watermark above `max_account_margin_drawdown_fraction` for non-reducing hedge plans
- projected account max margin-balance metadata missing or invalid while `max_account_margin_drawdown_fraction` is configured and `reject_missing_account_risk_metadata=true`
- projected account margin-utilization above `max_account_margin_utilization`
- projected account margin risk missing or invalid while `max_account_margin_utilization` is configured and `reject_missing_account_risk_metadata=true`
- current account daily realized loss above `max_account_daily_realized_loss` for non-reducing hedge plans
- current symbol daily realized loss above `max_symbol_daily_realized_loss` for non-reducing hedge plans
- projected account or symbol daily realized PnL missing or invalid while the matching daily realized-loss cap is configured and `reject_missing_account_risk_metadata=true`
- missing or mismatched hedge-mode position metadata while `required_position_mode=HEDGE`
- provider or market mismatch
- unsupported order type
- unsupported position side
- disabled one-way, hedge-mode, or hedge-order policy

## Supported Governance Scenarios

The bot supports pause governance as an auditable risk control:

- Account or symbol pauses suppress strategy-planned order commands.
- The order risk gate rejects non-cancel order commands for paused accounts or symbols.
- Pause release requests are auditable governance events.
- Active pause counts are exposed through low-cardinality Micrometer gauges.
- Optional JDBC persistence exists for pause governance audit records.

## Supported Operator API Scenarios

The internal operator API can expose and manipulate remediation state:

- Inspect the current or explicitly requested runtime target status.
- List projected order interventions.
- List projected position interventions.
- List remediation recommendations.
- Create remediation decisions.
- List command plans.
- Preview executor reports.
- Execute eligible remediation batches when policy allows.
- Acknowledge order or position interventions.
- List and release pause governance state.

`GET /internal/runtime/status` is a read-only operator endpoint protected by
the same `X-Operator-Token` gate. It defaults to the active runtime target from
the effective config and can also inspect an explicit
provider/environment/account/market query. The response includes runtime
identity, reconciliation confidence, open order and position counts, external
intervention counts, unknown order status count, unresolved command count,
active pause count, projected market-data freshness counts, optional strategy
lifecycle state, and explicit readiness blockers. It does not bypass the order
risk gate or authorize trading; it is an operator surface for understanding
whether the live target is `READY`, needs `ATTENTION`, or is `BLOCKED`.
The same readiness surface now also has low-cardinality Micrometer gauges,
Prometheus-compatible alert rules in
`ops/prometheus/runtime-readiness-alerts.yml`, and an importable Grafana
dashboard at `ops/grafana/runtime-readiness-dashboard.json`.

## Remediation Executor Observability

Implemented remediation executor observability includes:

- `trading.risk_gate.decision.events` Micrometer counters for each order risk-gate decision before provider mapping.
- Bounded risk-gate labels for `provider`, `environment`, `account`, `market`, `action`, `decision`, `primary_reason`, `reduce_only`, and `close_position`.
- Command ids, client order ids, exchange order ids, symbols, strategy ids, signal ids, and idempotency keys are intentionally excluded from risk-gate metric tags to keep Prometheus cardinality bounded.
- Prometheus-compatible alert rules exist at `ops/prometheus/risk-gate-alerts.yml` for rejected commands, manual-review decisions, unsafe reduce-only shape rejections, and approved-command visibility during live validation.
- An importable Grafana risk-gate dashboard exists at `ops/grafana/risk-gate-dashboard.json`.
- `trading.remediation_executor.outcome.events` Micrometer counters for preview and execute evaluations.
- Bounded labels for `provider`, `environment`, `account`, `market`, `mode`, `operation`, `status`, and executor `reason`.
- Disabled executor policy evaluations counted as `operation=NONE`, `status=DISABLED`, and `reason=executor:policy_disabled`.
- Per-plan outcomes counted for blocked, preview-only, submitted-to-pipeline, and no-action reports.
- Remediation ids, client order ids, exchange order ids, and symbols are intentionally excluded from metric tags to keep Prometheus cardinality bounded.
- Prometheus-compatible alert rules exist at `ops/prometheus/remediation-executor-alerts.yml` for disabled policy evaluations, blocked plans, pipeline submission failures, submitted commands, repeated no-action outcomes, and execute-mode evaluations that remain preview-only.
- The shared Alertmanager profile routes remediation executor alerts through the same `service`, `routing_hint`, and `severity` labels used by pause governance alerts.
- LFA signal-runner outcomes now emit
  `trading.strategy.lfa.signal_runner.run.events` counters with bounded target,
  enabled, status, reason, and primary-blocker labels. Prometheus-compatible
  alert rules exist at `ops/prometheus/strategy-lfa-alerts.yml` for disabled,
  lifecycle-blocked, reconciliation-blocked, budget-blocked,
  allocation-blocked, and published-signal runner outcomes.
- Runtime readiness now emits low-cardinality gauges for readiness state,
  reconciliation state, readiness blockers, projection safety counts, and
  projected market-data freshness counts. Prometheus-compatible alert rules
  exist at `ops/prometheus/runtime-readiness-alerts.yml` for blocked runtime
  readiness, missing or degraded reconciliation, unsafe order state, external
  interventions, active pauses, and missing or insufficient fresh market data.
- A Google Cloud Alertmanager renderer now renders the placeholder-only
  Alertmanager profile from Secret Manager for `demo` or `real`, validates the
  supported placeholder set, fails if a required alert secret is missing, refuses
  unresolved placeholders, and writes rendered files with restricted permissions
  without printing secret values. The Alertmanager profile now supports
  PagerDuty, Slack, and SMTP email receivers; email targets and SMTP credentials
  are deployment secrets and are not committed.
- A Google Cloud operations runbook now covers bootstrap, GitHub environment
  setup, image publication, Cloud Run deployment, private smoke, Alertmanager
  rendering, rollback, emergency stop, controlled drain, incident evidence, and
  real-promotion gates for the shared demo/real codebase. A scenario-specific
  incident response runbook now covers exchange outages, stale user-data and
  market-data streams, reconciliation degradation, external orders/positions,
  unknown order outcomes, failed deployments, persistence failures, alerting
  outages, credential rotation/compromise, cost spikes, real-environment rules,
  and incident evidence bundles.
- Operational evidence templates, a live release collector, and a demo burn-in
  collector now exist in `ops/evidence`. The release collector creates a
  sanitized demo or real bundle from the current commit, deployment contract,
  config checksums, secret binding names, workflow run ids, Cloud Run metadata,
  smoke outputs, and operator/API snapshots without reading or printing secret
  values. The burn-in collector creates a sanitized demo burn-in bundle from the
  committed catalog, demo runtime override, demo/real deployment contracts,
  release-evidence manifests, and supplied live observation files for runtime
  stages, market universe coverage, continuous metrics, trading metrics, drills,
  observability, and incidents. Generated bundles remain promotion-blocking
  until live outcomes are completed.
- An importable Grafana remediation executor dashboard exists at `ops/grafana/remediation-executor-dashboard.json`.
- An importable Grafana LFA strategy runner dashboard exists at `ops/grafana/strategy-lfa-dashboard.json`.
- An importable Grafana runtime readiness dashboard exists at `ops/grafana/runtime-readiness-dashboard.json`.
- A remediation executor operator runbook exists at `ops/runbooks/remediation-executor.md`, covering
  disabled, blocked, preview-only, submitted-to-pipeline, no-action, pipeline-submission-failure,
  ambiguous/unknown reconciliation, and hedge-mode remediation outcomes.

## Persistence And Recovery

Implemented persistence/recovery surfaces include:

- Trading event journal support.
- Projection snapshot lifecycle.
- JDBC projection snapshot store.
- Cloud deployment contracts now require durable JDBC projection persistence,
  projection retention, compaction, managed backups, restore drills, and journal
  archive policy. Google Cloud demo/real and AWS demo/real contracts disable
  file projection snapshots in ephemeral runtimes and bind projection JDBC
  settings through secrets.
- A production runtime `Dockerfile` now packages the prebuilt `bot-app` boot jar
  and source-controlled config, defaults to the `live` profile, sets the
  Chronicle Queue JVM access flags, runs as a non-root user, and exposes the
  readiness health endpoint. The image carries OCI source/revision/version/created
  labels, and GitHub Actions builds this image without pushing it after the
  Gradle quality gate while uploading Buildx metadata as a CI artifact.
- A guarded manual Google Cloud image publication workflow now publishes the
  same Dockerfile to Artifact Registry after verifying the requested commit has
  passed the `Security` workflow, using GitHub environment gates, OIDC Workload
  Identity, commit-SHA tags, OCI metadata, and uploaded publish evidence. It
  does not deploy Cloud Run yet.
- A guarded manual Google Cloud Cloud Run deployment workflow now deploys a
  previously published commit-tagged image after verifying `Security` success
  and image existence, maps contract runtime variables and Secret Manager
  bindings, blocks unauthenticated access, labels the revision with the source
  commit, and uploads deployment evidence.
- A guarded manual Google Cloud Cloud Run smoke workflow now verifies the latest
  ready private Cloud Run revision after deployment. It checks `Security` success
  for the requested commit, verifies the revision source label and image tag,
  calls `/actuator/health/readiness` with a Google identity token, and uploads
  smoke evidence.
- A guarded manual Binance live smoke workflow now verifies demo or real
  exchange connectivity through GitHub environment gates. It checks `Security`
  success for the requested commit, selects the requested active target inside
  the CI workspace, runs the existing server-time, passive order lifecycle,
  user-data stream, and market-data websocket smoke tasks with environment-scoped
  Binance credentials, uploads smoke evidence, and requires
  `confirm_real_smoke=RUN_REAL_BINANCE_SMOKE` before any real target can run.
- A guarded manual Google Cloud evidence archive workflow now stores generated
  release, burn-in, smoke, rollback, incident, drill, or promotion evidence
  artifacts in a versioned Cloud Storage evidence bucket after a secret-pattern
  scan. The bootstrap script creates the evidence archive bucket, evidence
  archiver service account, GitHub OIDC binding, and optional GitHub environment
  secret/variable values.
- A guarded manual Google Cloud Cloud Run rollback workflow now routes traffic
  back to an existing revision after verifying the requested rollback commit
  passed `Security`, the target revision belongs to the selected service, the
  revision labels and image match the expected commit/environment, 100 percent
  traffic is shifted to that revision, and private readiness returns `UP`.
- A Google Cloud bootstrap script now prepares the foundation required by the
  current workflows: required APIs, Artifact Registry, journal archive bucket,
  Cloud SQL PostgreSQL, demo/real database users, service accounts, IAM
  bindings, GitHub OIDC Workload Identity Federation, and Secret Manager
  containers/versions. It defaults non-secret deployment values, reads the
  Binance demo key/secret from `api.env`, generates operator-token and Cloud SQL
  password secret versions when absent, and generates Cloud SQL JDBC
  URL/username/password secret versions when no runtime override is supplied.
  It can optionally create an idempotent project-scoped monthly Google Cloud
  budget alert when `GCP_BUDGET_ALERTS_ENABLED=true` and `GCP_BILLING_ACCOUNT`
  are supplied; the default budget alert is `250USD` with 50 percent, 80
  percent, 100 percent, and forecasted 100 percent thresholds. If
  `GITHUB_CONFIGURE_ENVIRONMENTS=true`, it also uses GitHub CLI to create or
  update the `demo` and `real` GitHub environments with the required OIDC
  service-account secrets and deployment variables.
- A Google Cloud Monitoring policy provisioning script now renders shared
  demo/real alert policy templates and creates missing Cloud Monitoring policies
  by display name. The first managed policies cover Cloud Run 5xx responses,
  Cloud SQL CPU above 80 percent, and Cloud SQL disk utilization above 85
  percent. Notification channels are passed as Cloud Monitoring resource names,
  not inline receiver secrets.
  service-account secrets and deployment variables.
- The persistence recovery runbook at `ops/runbooks/persistence-recovery.md`
  defines restore sequencing, post-restore reconciliation gates, compaction
  constraints, and evidence requirements.
- Recovery test coverage now verifies that a file snapshot containing an external-order intervention and matching automated remediation decision can be restored into a new projection, then the scheduled remediation runner executes the restored decision before publishing new decisions and skips republishing a duplicate recommendation decision.
- Recovery test coverage now verifies that active symbol pause governance restored from a file snapshot still blocks new order admission through the order risk gate after restart.
- Recovery test coverage now verifies that restored managed-order unknown modify state still blocks execution and preserves the generic reconciliation action in the remediation executor report after restart.
- Recovery test coverage now verifies that restored adopted-order ambiguous modify state still blocks execution and preserves reconciliation action plus rollback blocker metadata in the remediation executor report after restart.
- Recovery test coverage now verifies that restored LFA strategy state with usable market data still blocks signal publication after restart until target reconciliation has at least one observation.
- Position projection metadata now retains `leverage`, `margin_type`, and `isolated_margin` so recovered snapshots keep the account-risk data used by remediation policy.
- Daily realized PnL projection now accumulates non-duplicate, non-stale execution-report attributes (`realizedProfit`, `realizedPnl`, or `realized_pnl`) by provider, environment, account, market, optional symbol, and UTC trading day. File and JDBC snapshots persist account-scope and symbol-scope accounting state for recovery and risk policy.
- Audit persistence for supported intervention and pause governance flows.

## Configuration Surface Added For Position Remediation

Catalog values execute for these fields unless overridden, and remain explicit
and overridable:

- `trading.intervention.remediation_executor_policy.position_order_policy.one_way_reduce_only_enabled=false`
- `trading.intervention.remediation_executor_policy.position_order_policy.provider=binance`
- `trading.intervention.remediation_executor_policy.position_order_policy.market=usdm_futures`
- `trading.intervention.remediation_executor_policy.position_order_policy.position_side=BOTH`
- `trading.intervention.remediation_executor_policy.position_order_policy.order_type=MARKET`
- `trading.intervention.remediation_executor_policy.position_order_policy.require_reduce_only=true`
- `trading.intervention.remediation_executor_policy.position_order_policy.require_close_position_false=true`
- `trading.intervention.remediation_executor_policy.position_order_policy.hedge_mode_execution_enabled=false`
- `trading.intervention.remediation_executor_policy.position_order_policy.hedge_position_order_enabled=false`
- `trading.intervention.remediation_executor_policy.position_order_policy.allowed_symbols=[]`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_position_quantity=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.chunk_close_when_max_quantity_exceeded=false`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_position_notional=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.reject_unbounded_position_notional=true`
- `trading.intervention.remediation_executor_policy.position_order_policy.required_margin_type=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.required_position_mode=HEDGE`
- `trading.intervention.remediation_executor_policy.position_order_policy.min_leverage=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_leverage=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_position_notional=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_position_notional=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_unrealized_loss=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_unrealized_loss=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.min_account_margin_balance=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_margin_drawdown_fraction=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_margin_utilization=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_daily_realized_loss=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.max_symbol_daily_realized_loss=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.reject_missing_account_risk_metadata=true`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.enabled=false`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.provider=binance`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.market=usdm_futures`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_bot_created_orders=true`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_adopted_orders=false`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_symbols=[]`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_order_types=[LIMIT]`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_fields=[PRICE,QUANTITY]`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_quantity_increase=false`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allow_quantity_decrease=true`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_quantity_increase_fraction=null`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_quantity_decrease_fraction=null`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_price_drift_fraction=null`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.cancel_replace_on_unsupported_change=false`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.reject_stale_projection=true`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.max_projection_age_millis=null`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.require_open_order_status=true`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.require_exchange_order_id=false`
- `trading.intervention.remediation_executor_policy.managed_order_amendment_policy.allowed_statuses=[ACCEPTED,PARTIALLY_FILLED]`

Managed order amendment state:

- `AMEND` decisions for unresolved managed-order interventions now pass through a catalog-disabled planner policy that is explicitly enabled by the checked-in demo runtime for bounded BTCUSDT and ETHUSDT limit-order amendments.
- The policy can qualify or block amendments by provider, market, symbol allowlist, bot-created versus adopted ownership, allowed order type, allowed fields, quantity increase/decrease permission, optional quantity drift fractions, optional price drift fraction, stale projection age, open-order status, and optional exchange-order-id requirement.
- A policy-qualified amendment is `exchangeExecutable=true` only when projected side, projected order type, current price, current quantity, and target identity are available. The executor constructs an idempotent `MODIFY` command and submits it through the normal order execution pipeline.
- Unknown `MODIFY` outcomes retain command-action metadata in projection so follow-up remediation cannot blindly repeat an amendment while the exchange state is ambiguous.
- Cancel/replace fallback remains intentionally unimplemented; unsupported amendment shapes are blocked with an explicit fallback blocker instead of converted into destructive multi-step order replacement.

## Known Gaps Before Professional Autonomous Real Trading

Remaining work includes:

- Broader provider preflight coverage for future command families where exchange-specific validation is more than currently supported `NEW`, `CANCEL`, and futures `MODIFY`.
- Broader account-level and symbol-level risk budgets beyond the current optional projected exposure, current unrealized-loss, account margin-balance floor, account margin-balance high-watermark drawdown, account margin-utilization, and account/symbol daily realized-loss caps.
- Cancel/replace fallback for unsupported amendments and executable adopted replace/rollback behavior if a safe design is accepted.
- Strategy entry/exit lifecycle, stops, take-profit, timeout handling, stale signal handling, partial-fill handling, and unknown-result handling.
- V1 live validation, demo soak criteria, promotion gates, and real-trading runbooks.
- Backtesting and historical simulation are intentionally deferred to v2.
- CI/CD and cloud deployment completion for Google Cloud first, with provider-neutral deployment abstractions so AWS can be added without changing the trading code.
