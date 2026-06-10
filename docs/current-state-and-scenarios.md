# Current State And Supported Scenarios

This document describes the bot as implemented in this repository today. It is not a profitability claim and it is not a readiness certificate for real-money autonomous trading. Demo and real use the same codebase; the active behavior is selected by runtime profile, environment, account, market, credentials, and deployment configuration.

The intended product direction is a professional autonomous order and position
manager: it should open, amend, reduce, close, pause, recover, and reconcile
positions through policy-gated automation while minimizing avoidable risk and
loss. The current codebase implements only the supported scenarios listed in
this document. It must not be described as a completed profit-maximizing trading
manager yet.

## Active First-Start Target

The checked-in first-start runtime target is:

- Provider: `binance`
- Environment: `demo`
- Account: `main`
- Market: `usdm_futures`
- Runtime file: `config/runtime/live/binance/demo/main/usdm_futures.json`

That runtime enables the live demo execution path for the currently supported remediation operations only. It does not enable every remediation action and it does not enable real-environment trading.

## Runtime Policy Boundary

The executor remains policy-gated. A plan must pass projection identity checks, freshness checks, operation allowlists, environment checks, executor policy checks, and the order risk gate before exchange submission.

The checked-in demo runtime currently enables:

- External-order `CANCEL_ORDER`
- One-way external-position `CLOSE_POSITION`
- One-way external-position `REDUCE_POSITION`
- Managed-order `AMEND_ORDER`

The position-order policy for the first-start demo runtime also restricts automated position remediation to:

- `allowed_symbols=["BTCUSDT"]`
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
- inherited `reject_unbounded_position_notional=true`
- inherited `reject_missing_account_risk_metadata=true`

The managed-order amendment policy for the first-start demo runtime restricts automated amendments to:

- `enabled=true`
- `allowed_symbols=["BTCUSDT"]`
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
- Provider gateways can reject approved commands during preflight before gateway submission. Binance uses this for `NEW` order capability and exchange-filter validation, `CANCEL` target identity validation, and futures `MODIFY` target/parameter validation.

Supported non-exchange order remediation today:

- External order adoption: an order-scope `ADOPT` remediation decision can publish an auditable intervention acknowledgement when `trading.intervention.remediation_orchestrator.enabled=true` and `order_adoption_acknowledgement_enabled=true`.
- Adoption acknowledgement replay marks the projected external order as bot-managed and clears the unresolved external-order intervention. It does not submit, amend, or cancel an exchange order.
- Adopted orders remain protected after ownership transfer. The order risk gate treats a managed target with no bot command id as adopted and blocks ordinary target-order commands unless `trading.execution.risk_gate.target_order.allow_adopted_target_orders=true`. Policy-qualified adopted managed amendments can still pass when the amendment policy explicitly allowed adopted orders.

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
- projected account daily realized PnL missing or invalid while `max_account_daily_realized_loss` is configured and `reject_missing_account_risk_metadata=true`
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

- List projected order interventions.
- List projected position interventions.
- List remediation recommendations.
- Create remediation decisions.
- List command plans.
- Preview executor reports.
- Execute eligible remediation batches when policy allows.
- Acknowledge order or position interventions.
- List and release pause governance state.

## Persistence And Recovery

Implemented persistence/recovery surfaces include:

- Trading event journal support.
- Projection snapshot lifecycle.
- JDBC projection snapshot store.
- Position projection metadata now retains `leverage`, `margin_type`, and `isolated_margin` so recovered snapshots keep the account-risk data used by remediation policy.
- Daily realized PnL projection now accumulates non-duplicate, non-stale execution-report attributes (`realizedProfit`, `realizedPnl`, or `realized_pnl`) by provider, environment, account, market, and UTC trading day. File and JDBC snapshots persist this accounting state for recovery and future risk policy.
- Audit persistence for supported intervention and pause governance flows.

## Configuration Surface Added For Position Remediation

Catalog defaults keep these fields explicit and overridable:

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

- `AMEND` decisions for unresolved managed-order interventions now pass through a catalog-disabled planner policy that is explicitly enabled by the checked-in demo runtime for bounded BTCUSDT limit-order amendments.
- The policy can qualify or block amendments by provider, market, symbol allowlist, bot-created versus adopted ownership, allowed order type, allowed fields, quantity increase/decrease permission, optional quantity drift fractions, optional price drift fraction, stale projection age, open-order status, and optional exchange-order-id requirement.
- A policy-qualified amendment is `exchangeExecutable=true` only when projected side, projected order type, current price, current quantity, and target identity are available. The executor constructs an idempotent `MODIFY` command and submits it through the normal order execution pipeline.
- Unknown `MODIFY` outcomes retain command-action metadata in projection so follow-up remediation cannot blindly repeat an amendment while the exchange state is ambiguous.
- Cancel/replace fallback remains intentionally unimplemented; unsupported amendment shapes are blocked with an explicit fallback blocker instead of converted into destructive multi-step order replacement.

## Known Gaps Before Professional Autonomous Real Trading

Remaining work includes:

- Broader provider preflight coverage for future command families where exchange-specific validation is more than currently supported `NEW`, `CANCEL`, and futures `MODIFY`.
- Broader account-level and symbol-level risk budgets, including symbol-level realized-PnL budgets, beyond the current optional projected exposure, current unrealized-loss, account margin-balance floor, account margin-balance high-watermark drawdown, account margin-utilization, and account daily realized-loss caps.
- Cancel/replace fallback for unsupported amendments and broader adopted-order lifecycle controls.
- Broader operational runbooks for hedge-mode remediation.
- Strategy entry/exit lifecycle, stops, take-profit, timeout handling, stale signal handling, partial-fill handling, and unknown-result handling.
- V1 live validation, demo soak criteria, promotion gates, and real-trading runbooks.
- Backtesting and historical simulation are intentionally deferred to v2.
- CI/CD and cloud deployment completion for Google Cloud first, with provider-neutral deployment abstractions so AWS can be added without changing the trading code.
