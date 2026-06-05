# Current Bot State And Supported Scenarios

This document describes what the bot currently supports in code, what is
guarded behind configuration, and what remains incomplete. It is intentionally
conservative: a scenario is listed as supported only when the repository has a
concrete implementation path and tests around the behavior.

## Executive State

The bot has strong production foundations but is not yet a complete
professional execution system.

The implemented core is event-first and safety-oriented:

- Runtime configuration is layered, validated, and scoped to an active
  provider/environment/account/market target.
- Provider and strategy modules are separated from core.
- Trading events are schema-first Avro records with routing, Kafka/Redpanda
  publication, dead-letter handling, journal persistence, and replay support.
- Core maintains replayable projections for balances, positions, orders, risk,
  manual reviews, and remediation decisions.
- Order commands pass through a provider-agnostic risk gate before any gateway
  submission.
- Binance has substantial provider foundations and the first execution gateway
  boundary for order submission.
- Manual intervention and operator remediation workflows are conservative,
  auditable, and disabled by default where live side effects are involved.

The bot is still incomplete for unattended real-money trading:

- No complete order lifecycle across strategy signal, planner, exchange result,
  user stream, reconciliation, restart, and final position state.
- Retries, cancel/replace lifecycle, durable unknown-status recovery, and final
  state transition policy are incomplete.
- LFA strategy lifecycle and feature calculations are not implemented as a
  complete trading strategy.
- Backtest runtime, production metrics/alerts, deployment manifests, runbooks,
  production secrets integration, TimescaleDB tuning, and GCS archive workers
  are not complete.

## Runtime And Configuration Scenarios

Supported scenarios:

- Load default catalog configuration from `config/catalog.json`.
- Apply environment overrides from `config/application-demo.json` or
  `config/application-real.json`.
- Resolve the active runtime target from `config/active.json`.
- Apply runtime overrides from
  `config/runtime/live/{provider}/{environment}/{account}/{market}.json`.
- Create an empty runtime override file when the active target has none.
- Validate active provider/environment/account/market paths before use.
- Reject unknown override paths.
- Reject attempts to mutate schema/version metadata through environment or
  runtime overrides.
- Use fail-fast migration behavior for unsupported schema/config versions.
- Write runtime overrides atomically after staging and validation.
- Leave the previous runtime config intact when a candidate override is
  invalid.
- Detect config reloads by content fingerprint rather than raw filesystem
  events.
- Keep a running process bound to one active runtime target.

Unsupported or incomplete scenarios:

- Hot-swapping the active runtime target inside one running process.
- A supervisor that starts or stops multiple runtime instances.
- Production secret manager integration.

## Module Boundary Scenarios

Supported scenarios:

- `bot-core` owns startup, config loading, runtime identity, projections,
  messaging, journaling, execution risk, operator intervention, and provider
  and strategy contracts.
- `bot-exchange-binance` owns Binance-specific config, REST/WebSocket behavior,
  request signing, market metadata parsing, capability declarations, and
  provider command mapping.
- `bot-strategy-lfa` exists as the first strategy module boundary.
- Provider and strategy modules are discovered through core contracts.
- Core does not import provider-specific implementation types for strategy or
  exchange logic.

Unsupported or incomplete scenarios:

- A complete multi-strategy signal allocator.
- A complete smart order router.
- A full LFA strategy lifecycle.

## Event, Messaging, Journal, And Replay Scenarios

Supported scenarios:

- Encode and decode schema-first Avro trading events.
- Route event keys by account, symbol, order, and other supported entity scopes.
- Publish trading events through the event bus.
- Publish failed dispatches to dead letter handling.
- Consume Kafka/Redpanda records through registered handlers.
- Register handlers as live-only, replay-safe, or both depending on side-effect
  requirements.
- Persist trading events to Chronicle Queue.
- Replay journaled events on startup.
- Separate replay-safe projection rebuilding from live side-effect handlers.
- Report journal recovery status.
- Treat recovery failures as startup-safety problems rather than logging-only
  events.

Supported event categories include:

- Balance updates.
- Position updates.
- Risk updates.
- Order commands.
- Order results.
- Execution reports.
- Risk decisions.
- Intervention acknowledgements.
- Remediation decisions.
- Market-data events in the Binance provider boundary.

Unsupported or incomplete scenarios:

- GCS archive upload worker.
- Production archive retention workflows.
- Full stream-gap ordering policy around reconciliation.

## Core Projection Scenarios

Supported scenarios:

- Rebuild balances, positions, orders, risk, manual-review decisions, and
  remediation decisions from replayed events.
- Deduplicate events by event id.
- Reject stale per-entity updates when a newer state is already projected.
- Query balances by provider/environment/account/market/asset.
- Query positions by provider/environment/account/market/symbol/position side.
- Query orders by provider/environment/account/market/symbol/client order id.
- Query orders by exchange order id.
- Query projected orders by command id.
- Query unresolved external order interventions.
- Query unresolved external position interventions.
- Query unresolved unknown order statuses.
- Query unresolved replayed order commands.
- Query pending manual-review decisions.
- Query published remediation decisions for operator audit.
- Snapshot projection state in memory.
- Restore projection state from snapshots.
- Persist projection snapshots through opt-in file storage.
- Persist projection snapshots through opt-in PostgreSQL-compatible JDBC
  storage.

## Order Projection Scenarios

Supported scenarios:

- Project replayed order commands as `COMMAND_RECEIVED`.
- Preserve command id, client order id, and target order identity for replayed
  new, cancel, and modify commands.
- Treat replayed commands without later result evidence as unresolved command
  state.
- Clear unresolved command state when a later order result or user-data report
  arrives.
- Project order submit results as bot-managed orders.
- Project gateway failures as conservative `UNKNOWN` order results.
- Keep `UNKNOWN` order state unresolved until newer order evidence arrives.
- Clear unknown order state when later user-data, REST reconciliation, or order
  result evidence resolves the order.
- Project user-data execution reports for known bot orders.
- Detect user-data orders with no known bot command as external order
  interventions.
- Detect unplanned cancel, replace, or amendment of bot-managed orders as
  external intervention.
- Treat planned cancel and modify user-data reports as expected when a matching
  target command is projected.
- Preserve known bot command identity when REST snapshots later describe the
  same order.
- Treat REST open-order snapshots as reconciliation evidence, not submit
  evidence.
- Mark previously unknown REST snapshot orders as external interventions.
- Resolve `external_order_observed` automatically when newer user-data or REST
  evidence proves a no-fill terminal state: canceled, expired, or rejected.
- Keep filled external orders blocked for operator review.
- Keep unplanned managed-order changes blocked even if later REST evidence also
  shows a terminal no-fill state.
- Clear matching order interventions only through replayable intervention
  acknowledgement events.

Unsupported or incomplete scenarios:

- Complete durable order lifecycle from strategy signal to final position state.
- Full retry policy around unknown exchange status.
- Complete cancel/replace lifecycle policy.
- Durable exchange-stream gap recovery policy.

## Position Projection Scenarios

Supported scenarios:

- Project position updates with update source metadata.
- Detect manual or external position size changes when no recent managed fill
  explains the delta.
- Attribute a position change to bot activity only when a recent managed
  user-data fill exists after the previous position state and no later than the
  incoming position update.
- Keep stale managed fills from masking later manual position changes.
- Query open positions for a target.
- Clear matching position interventions only through replayable intervention
  acknowledgement events.

Unsupported or incomplete scenarios:

- Complete policy for whether to stand down, re-hedge, re-open, or require
  operator review after manual position changes.
- Complete fill-to-position binding across all exchange products.

## Reconciliation Scenarios

Supported scenarios:

- Track reconciliation confidence per provider/environment/account/market,
  event type, and entity key.
- Classify confidence as no observations, confident, or degraded states.
- Feed reconciliation confidence into order risk decisions.
- Compare Binance REST snapshots against the core projection for balances,
  positions, orders, and risk state.
- Publish reconciliation observations for matched, missing, and mismatched
  state.
- Use opt-in controls for projection comparison and fail-on-mismatch behavior.
- Reconcile Binance order history as order-result evidence when explicitly
  configured.
- Reconcile Binance account trades as execution reports only when correlated to
  order-history rows with documented identity.
- Skip unmatched account trades instead of fabricating order identity.

Unsupported or incomplete scenarios:

- Durable reconciliation-backed action gating across all stream gaps and
  restarts.
- Full historical reconciliation without explicitly bounded symbols/limits.

## Risk Gate Scenarios

Supported decisions:

- `APPROVED`: command may proceed to gateway when all configured checks pass.
- `REJECTED`: command is blocked and no gateway call is made.
- `MANUAL_REVIEW`: command is blocked and projected into the operator-review
  queue when the decision event is handled.

Supported risk-gate checks:

- Reject commands when risk gate is enabled and reconciliation has no
  observations.
- Reject or manual-review commands when reconciliation confidence is degraded,
  according to configuration.
- Reject duplicate command id or client order id already projected after
  restart, when projected idempotency rejection is enabled.
- Reject duplicate in-process command id or idempotency key through the
  execution idempotency tracker.
- Enforce configurable order quantity and notional limits.
- Reject invalid numeric order fields when configured.
- Reject unbounded notional when configured and neither price nor reference
  price can bound the command.
- Apply target-specific order-limit overrides.
- Stop or review commands when projected external order interventions exist.
- Stop or review commands when projected external position interventions exist.
- Stop or review commands when unresolved `UNKNOWN` order states exist.
- Stop or review commands when unresolved replayed order commands exist.
- Optionally apply intervention checks to target commands such as cancel and
  modify.
- Validate cancel/modify target identity against projected order state.
- Reject or review missing target orders when target validation is configured.
- Reject or review unmanaged projected targets when configured.
- Reject or review closed projected targets when configured.
- Reject or review target orders with external interventions when configured.

Risk-decision diagnostics currently include:

- Reconciliation status and state counts.
- External order and position intervention counts.
- Affected external order command/client/exchange identities.
- Affected external position symbols and sides.
- Affected unknown order command/client/exchange identities.
- Affected unresolved command/client/exchange identities.
- Target order identity and projected target identity.
- Projected duplicate command/client identity.
- Effective order-limit scope and thresholds.
- Configured intervention actions.

## Order Execution Pipeline Scenarios

Supported scenarios:

- Handle only `ORDER_COMMAND` envelopes.
- Evaluate the risk gate before gateway submission.
- Publish a risk decision before any gateway side effect.
- Stop after publishing a rejected or manual-review risk decision.
- Reject duplicate in-process command id before submitting again.
- Reject duplicate in-process idempotency key before submitting again.
- Reject approved commands when no gateway supports the target.
- Preserve target identity in no-gateway risk decisions.
- Submit approved commands through the first supporting gateway.
- Publish gateway order result after successful submission.
- Convert gateway exceptions into conservative `UNKNOWN` order results.
- Convert failed gateway futures into conservative `UNKNOWN` order results.
- Convert null gateway results into conservative `UNKNOWN` order results.
- Validate gateway result identity against provider/environment/account/market,
  symbol, command id, client order id, and target exchange order id.
- Convert mismatched gateway result identity into conservative `UNKNOWN`
  gateway-failure results.
- Preserve target order identity for gateway failures on cancel/modify
  commands.

Unsupported or incomplete scenarios:

- Multiple-gateway routing or utility scoring.
- Full retry orchestration.
- Complete cancel/replace execution lifecycle.

## Operator Review And Remediation Scenarios

Supported operator API concepts:

- Disabled-by-default token-gated operator HTTP API.
- List unresolved order interventions.
- List unresolved position interventions.
- List pending manual-review decisions.
- List remediation recommendations.
- Publish intervention acknowledgements.
- Publish remediation decisions.

Supported acknowledgement scenarios:

- Acknowledge a matching order intervention by projected order identity.
- Acknowledge a matching position intervention by projected position identity.
- Reject acknowledgement when the projected intervention no longer exists.
- Reject acknowledgement when the projected intervention reason does not match.
- Clear matching order or position interventions through replayable
  acknowledgement events.

Supported remediation recommendation scenarios:

- Recommend operator review for unknown external orders.
- Recommend replan from projection for unplanned managed order changes.
- Recommend replan from projection for manual position close to zero.
- Recommend hedge or replan for non-zero external position size changes.
- Include manual-review decisions as operator-review recommendations.

Supported remediation decision scenarios:

- Publish remediation decisions only when the request still matches a current
  recommendation.
- Require exact order identity for order-scope remediation.
- Require exact position identity for position-scope remediation.
- Require decision id, command id, affected order identity, or affected
  position side for manual-review remediation.
- Match manual-review remediation by decision id.
- Match manual-review remediation by command id.
- Match manual-review remediation by affected unknown order identity.
- Match manual-review remediation by affected unresolved order identity.
- Match manual-review remediation by affected external order identity.
- Match manual-review remediation by affected target order identity.
- Match manual-review remediation by affected external position side.
- Normalize selected affected client order id into
  `affected_order_client_order_id`.
- Normalize selected affected position side into `affected_position_side`.
- Preserve request-supplied affected exchange order id.
- Retain remediation decisions in projection and snapshots.
- Clear matching manual-review queue entries through replay when a
  `MANUAL_REVIEW` / `OPERATOR_REVIEW` decision carries the matching command id.

Supported live remediation-orchestrator scenarios:

- Disabled by default.
- Ignore all remediation side effects when the orchestrator is disabled.
- Ignore acknowledgement publication when operator-review acknowledgement is
  disabled.
- Ignore non-`OPERATOR_REVIEW` remediation actions.
- Publish deterministic acknowledgement events for direct order-scope
  `OPERATOR_REVIEW` remediation when the projected order intervention still
  matches.
- Publish deterministic acknowledgement events for direct position-scope
  `OPERATOR_REVIEW` remediation when the projected position intervention still
  matches.
- Publish deterministic acknowledgement events for manual-review external-order
  remediation when the event attributes identify exactly one projected external
  order intervention.
- Publish deterministic acknowledgement events for manual-review
  external-position remediation when the event attributes identify exactly one
  projected external position intervention.
- Ignore manual-review decisions for unknown or unresolved order conditions.
- Suppress duplicate live acknowledgement publication by remediation id.
- Remove a remediation id from the duplicate-suppression set if publication
  fails.
- Keep the orchestrator live-only so journal replay does not rerun side effects.

Unsupported or incomplete remediation scenarios:

- Automated stand-down.
- Automated hedge.
- Automated re-open.
- Automated replan order placement.
- Multi-order or multi-position acknowledgement from one ambiguous review.
- Operator UI beyond the HTTP API surface.

## Binance Provider Scenarios

Supported product-family modeling:

- Spot.
- Cross Margin.
- Isolated Margin.
- USD-M Futures.
- COIN-M Futures.
- Options.

Supported Binance infrastructure:

- Validate Binance provider/environment/account/market configuration.
- Validate active market capabilities against Binance market type.
- Build deterministic signed REST requests.
- Add timestamp and `recvWindow` before signing.
- Support configured HMAC/RSA/Ed25519 authentication shapes where modeled in
  config.
- Synchronize local timestamps against Binance server time.
- Parse `exchangeInfo` metadata, filters, precision, lifecycle dates, trigger
  protection, fees, rate limits, and symbol rules.
- Refresh exchange metadata in live mode when enabled.
- Track REST rate-limit headers.
- Build WebSocket endpoint plans for raw and combined streams.
- Use a WebSocket supervisor for rollover and reconnect behavior.
- Keep live WebSocket transport behind explicit runtime switches.

Supported Binance order REST scenarios:

- Build and validate new order requests for supported product families.
- Submit new orders through the Binance order client.
- Query orders.
- List open orders.
- Cancel orders.
- Build cancel-by-underlying routes for options where supported.
- Build batch order requests and parse batch results.
- Build Spot order-list requests for OCO, OTO, and OTOCO style flows where
  modeled.
- Build Spot cancel-replace requests.
- Build futures modify requests.
- Build Spot amend keep-priority requests.
- Build Spot SOR order requests and parse SOR results.
- Validate provider-specific order flags and unsupported parameter collisions.
- Map Binance order results into core order result events.
- Convert Binance API errors into typed exceptions without logging secrets.

Supported Binance account and reconciliation scenarios:

- Fetch futures account snapshots.
- Fetch futures balances.
- Fetch futures position risk.
- Fetch cross-margin account snapshots.
- Fetch isolated-margin account snapshots.
- Fetch margin transfer history.
- Fetch margin max transferable amount.
- Fetch margin borrow/repay results.
- Fetch margin special-key state and issue special-key commands where modeled.
- Fetch futures income, funding rate, force order, and ADL quantile data where
  modeled.
- Fetch options margin account snapshots.
- Fetch options position snapshots.
- Fetch options order history.
- Fetch options open orders.
- Fetch options account trades.
- Fetch options commission and exercise records.
- Map configured REST snapshots into core reconciliation events.
- Compare REST snapshots to core projection when configured.

Supported Binance user-data scenarios:

- Start, keep alive, and close user-data streams for configured stream types.
- Support Spot WebSocket API style user data, margin listen tokens, and
  futures/options listen keys as modeled in config.
- Map Spot/Margin execution reports and balance events.
- Map USD-M and COIN-M futures order-trade updates and account updates.
- Map Options `ORDER_TRADE_UPDATE`, `ACCOUNT_UPDATE`,
  `BALANCE_POSITION_UPDATE`, `GREEK_UPDATE`, and `RISK_LEVEL_CHANGE`.
- Publish mapped private-stream events through `TradingEventBus`.
- Require an event bus before enabling runtime user-data consumption.

Supported Binance market-data scenarios:

- Map trade and aggregate trade payloads.
- Map book ticker payloads.
- Map depth snapshot and depth delta payloads.
- Map mark price payloads.
- Map kline payloads.
- Publish mapped public-stream events through `TradingEventBus`.
- Require configured streams and an event bus before enabling runtime
  market-data consumption.

Supported Binance options-specific scenarios:

- Use documented options `/eapi/v1/order` parameter names such as
  `clientOrderId`, `isMmp`, and `postOnly`.
- Fetch options account margin and position snapshots.
- Configure options market-maker protection endpoints.
- Configure options countdown-cancel-all kill switch and heartbeat endpoints.
- Keep options mutation-style market-maker endpoints disabled by default unless
  explicitly configured.

Unsupported or incomplete Binance scenarios:

- Treating every modeled REST endpoint as production-enabled by default.
- Unbounded history reconciliation.
- Live real-options trading readiness.
- Full provider gateway support for every state-changing endpoint.
- Complete exchange-filter enforcement for every remaining endpoint.

## Strategy And Planning Scenarios

Supported scenarios:

- Strategy module discovery exists.
- Strategy signals can be converted into initial provider-agnostic order
  commands through the core strategy-signal planner.
- Risk gates and execution pipeline consume provider-agnostic order commands.

Unsupported or incomplete scenarios:

- Complete LFA feature calculations.
- Complete LFA lifecycle.
- Multiple strategy coordination.
- Signal conflict resolution.
- Portfolio/risk allocation across strategies.
- Smart order routing.
- Expected-utility scoring across edge, fees, spread/slippage, fill
  probability, latency, inventory risk, margin risk, rate-limit cost, and
  reconciliation confidence.

## Persistence And Storage Scenarios

Supported scenarios:

- Chronicle Queue journal for critical event persistence.
- In-memory projection rebuild from events.
- Opt-in file projection snapshot store.
- Opt-in JDBC projection snapshot store with PostgreSQL-compatible schema.
- Deterministic archive object naming layout.

Unsupported or incomplete scenarios:

- Production TimescaleDB hypertable tuning.
- Production database migration runner.
- GCS uploader/archive worker.
- Archive lifecycle management.

## Operational Readiness Scenarios

Supported scenarios:

- Local Gradle quality gates.
- Spotless formatting checks.
- Checkstyle and SpotBugs for core.
- GitHub Actions security workflow.
- Binance demo smoke-test documentation.
- Local `api.env` support for smoke-test credentials while staying ignored.

Unsupported or incomplete scenarios:

- Production deployment manifests.
- Production metrics, alerts, traces, and dashboards.
- Production secrets integration.
- Production runbooks.
- Deterministic backtest runtime.

## Safety Summary

The current bot supports conservative, auditable progression from observed
state to risk decision to optional execution or operator review. Its strongest
supported scenarios are configuration safety, event journaling, replayable
projection, Binance provider foundations, reconciliation evidence, order risk
gating, and operator remediation audit trails.

It should not be treated as fully autonomous production trading software yet.
The remaining work is mostly around completing the durable execution lifecycle,
closing reconciliation ordering gaps, implementing strategy lifecycle and
planning, and adding production operations infrastructure.
