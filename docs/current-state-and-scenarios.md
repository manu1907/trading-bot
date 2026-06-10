# Current State And Supported Scenarios

This document describes the bot as implemented in this repository today. It is not a profitability claim and it is not a readiness certificate for real-money autonomous trading. Demo and real use the same codebase; the active behavior is selected by runtime profile, environment, account, market, credentials, and deployment configuration.

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

The position-order policy for the first-start demo runtime also restricts automated position remediation to:

- `allowed_symbols=["BTCUSDT"]`
- `max_position_quantity="0.001"`
- `chunk_close_when_max_quantity_exceeded=true`
- `max_position_notional="250"`
- `required_margin_type="cross"`
- `min_leverage="1"`
- `max_leverage="5"`
- inherited `max_account_margin_utilization=null`
- inherited `reject_unbounded_position_notional=true`
- inherited `reject_missing_account_risk_metadata=true`

## Supported External Order Scenarios

The bot can project external or manually created orders as interventions. It can recommend and decide remediation, then build executor command plans.

Supported exchange-executable order remediation today:

- External order close: an external order with a matching projected intervention can produce a `CANCEL_ORDER` plan.
- The cancel plan routes through `OrderExecutionPipeline` when executor policy is enabled, exchange execution is enabled, report-only is false, the operation is allowlisted, and the risk gate accepts the command.
- Cancel commands are allowed even under pause governance so the bot can reduce existing order risk.
- Provider gateways can reject approved commands during preflight before gateway submission. Binance uses this for `NEW` order capability and exchange-filter validation, `CANCEL` target identity validation, and futures `MODIFY` target/parameter validation.

Current non-executable order intents:

- `ADOPT`
- `IGNORE`
- order amendment or enhancement
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
- projected account margin-utilization above `max_account_margin_utilization`
- projected account margin risk missing or invalid while `max_account_margin_utilization` is configured and `reject_missing_account_risk_metadata=true`
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
- `trading.intervention.remediation_executor_policy.position_order_policy.max_account_margin_utilization=null`
- `trading.intervention.remediation_executor_policy.position_order_policy.reject_missing_account_risk_metadata=true`

## Known Gaps Before Professional Autonomous Real Trading

Remaining work includes:

- Broader provider preflight coverage for future command families where exchange-specific validation is more than currently supported `NEW`, `CANCEL`, and futures `MODIFY`.
- Broader account-level and symbol-level risk budgets, drawdown limits, and daily loss limits beyond the current optional projected exposure and account margin-utilization caps.
- External order adoption and managed amendment policies.
- Broader operational runbooks for hedge-mode remediation.
- Strategy entry/exit lifecycle, stops, take-profit, timeout handling, stale signal handling, partial-fill handling, and unknown-result handling.
- Backtesting, replay validation, demo soak criteria, promotion gates, and real-trading runbooks.
- CI/CD and cloud deployment completion for Google Cloud first, with provider-neutral deployment abstractions so AWS can be added without changing the trading code.
