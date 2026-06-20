# Trading Bot Architecture

The bot is organized around a provider/strategy-agnostic core, a runnable
application assembly, and pluggable runtime modules.

## Continuation Standard

This repository should be developed as a production trading system, not a demo
script. Future chats or agents should follow the same process:

- Read the current code before deciding the next slice.
- Keep slices narrow, commit-sized, and tied to the delivery plan.
- Do not overclaim readiness. If a provider capability is documented but not
  implemented, record it as a gap rather than implying support.
- Preserve user-local tracked edits unless explicitly told to include them.
- Run focused tests first, then the relevant Gradle quality gate. For core
  slices, `./gradlew :bot-core:check` runs tests, Spotless, Checkstyle, and
  SpotBugs. For cross-module or documentation slices, prefer `./gradlew check`
  or `./gradlew spotlessCheck` plus the affected module checks.
- After every push, check GitHub Actions and wait for the pushed commit's run
  to complete.
- Treat unknown exchange status, stale data, rate limits, failed recovery, and
  missing protective orders as safety problems, not logging-only problems.

The current standard is conservative: no strategy signal may reach the exchange
without risk gating, idempotent order identity, durable event journaling,
reconciliation, and observable state transitions.

## Modules

- `bot-core`: owns config loading, runtime identity, exchange/strategy
  contracts, validation, reload policy, risk, projection, reconciliation,
  journal, remediation, and orchestration. It must not depend on concrete
  provider or strategy implementation modules.
- `bot-app`: owns the Spring Boot application entrypoint and runtime assembly.
  It is the only module that wires concrete provider and strategy plugins into
  the runnable process.
- `bot-exchange-binance`: owns Binance-specific config binding, REST/WebSocket
  behavior, metadata parsing, and Binance lifecycle.
- `bot-strategy-lfa`: owns the LFA strategy implementation and its strategy
  config once the strategy contract is complete.

Provider modules and strategy modules are discovered as Spring beans through
core contracts. Core should not import or assemble provider-specific config or
implementation types.

## Decision Boundary

Strategies explain why and what to trade; they emit provider-agnostic intents
with direction, confidence, horizon, urgency, risk budget, and execution
preferences. They do not choose Binance endpoints, order-list shapes, listen-key
flows, or product-specific flags.

Strategy modules may rank their own provider-agnostic candidate signals using
strategy-visible facts such as confidence, projected liquidity, spread, depth,
freshness, provider capability annotations, reconciliation availability, and
projected risk/money-management fit derived from core projection state and
strategy config. They may also publish auditable expected-profit estimates when
the estimate is derived from strategy-visible market facts rather than provider
implementation details. The LFA runner does this with `lfa_expected_edge_score`
before applying its publish cap. That score must remain free of Binance
implementation imports; provider modules expose capabilities and market/account
facts through core contracts.

Provider modules explain what the exchange allows and execute concrete
provider commands. Binance owns its documented market types, filters, fees,
rate limits, order fields, user-data semantics, and request validation, but it
must not embed strategy profitability logic.

Core owns how to trade. A future execution planner/smart order router in core
must combine strategy intents, provider capability catalogs, market/account
state, risk limits, latency constraints, and reconciliation confidence into
provider-specific execution plans. For multiple strategies, core must normalize
signals, allocate risk, resolve conflicts, net exposure where configured, and
reject commands that exceed the active runtime limits.

The strategy signal planner's instrument-universe gate is also core-owned. It
can refresh provider exchange metadata before planning, admit exchange-polled
symbols that pass runtime quote-asset, contract-type, market-data, spread,
top-of-book depth, and projected daily quote-volume gates, and reject candidates
that violate active runtime limits. The provider supplies capabilities and
execution; it does not decide strategy profitability or own the trading universe.

## Runtime Target

A live runtime target is identified by:

```text
provider/environment/account/market
```

Example:

```text
binance/demo/main/usdm_futures
```

For v1 online trading, the active Spring profile is `live`. Exchange
environment describes venue/account context: `demo` or `real`. Demo and real
must use the same application code; configuration, credentials, endpoints,
risk limits, deployment controls, and provider product availability are the
allowed differences.

## Config Precedence

For live mode:

```text
runtime override > environment config > default catalog config
```

The external live config is intentionally outside Spring config:

- `config/catalog.json`: schema-bearing catalog with all required provider,
  account, market, transport, timing, and safety fields.
- `config/application-demo.json`: demo endpoint and credential-reference
  overrides.
- `config/application-real.json`: real endpoint and credential-reference
  overrides.
- `config/active.json`: the selected provider/environment/account/market.
- `config/runtime/live/{provider}/{environment}/{account}/{market}.json`:
  operator overrides for the active runtime target.

For the current first demo run, the checked-in active runtime override is
`config/runtime/live/binance/demo/main/usdm_futures.json`. It is scoped to the
active Binance demo `main` USD-M futures target and keeps secrets out of source
control.

The loader merges `catalog.json`, then the active environment file, then the
active runtime file. Unknown override paths are rejected. Runtime override
files are created as empty JSON objects when the active target has no file yet.
Runtime override writes are staged as candidate config, validated, then moved
into place atomically. A rejected write leaves the previous runtime file intact.
Root `version` and `schema` metadata cannot be changed by environment or
runtime patches. The current migration policy is `fail_fast`: an unsupported
schema id, schema version, config version, or migration policy stops startup
or reload instead of trying an implicit migration.
`ExternalTradingRuntimeEnvironmentPostProcessor` also loads the same validated
live config before Spring bean conditions and `@ConfigurationProperties` binding,
then exposes the merged `trading.*` subtree as a high-priority Spring property
source. Command-line arguments and OS environment variables stay above this
source, so deployment secrets and emergency overrides remain authoritative while
checked-in runtime files can still activate runtime services on first start.

The `backtest` profile/config surface is deferred to v2. It should not be
treated as a v1 delivery target until the deterministic historical-data runtime
is intentionally designed and implemented.

Runtime target changes are immutable for a running process unless a future
supervisor explicitly creates or stops runtime instances.

Runtime reload uses content fingerprints rather than raw filesystem events.
This prevents one editor save from causing repeated reloads and lets nested
runtime override files participate in the reload decision.

## Binance Config Boundary

Binance config owns values that are documented outside `exchangeInfo`: REST
base endpoints, WebSocket base endpoints and routed paths, signing/timestamp
policy, `recvWindow`, API-key header, retry and unknown-status handling, listen
key lifecycle paths, WebSocket lifetime, ping/pong limits, message limits, and
account expectations. Symbol filters, rate-limit definitions, assets, and
tradable symbols still come from `exchangeInfo` at runtime.
Core validates the active provider/environment/account/market path; the Binance
module validates the Binance-specific contract before startup or reload can use
it.
Signed REST request construction is kept provider-local and deterministic:
query parameters are encoded in order, `timestamp` and `recvWindow` are added
before signing, and retry/reconciliation decisions come from the active Binance
config.
Server-time synchronization is provider-local as well. The Binance module calls
the configured server-time endpoint, computes the local/server offset from the
request midpoint, and feeds that offset into signed REST timestamp generation.
The Binance connector should track the Binance documentation as the source of
truth for product families and authentication choices: Spot, Cross Margin,
Isolated Margin, USD-M Futures, COIN-M Futures, Options, HMAC keys, RSA PKCS#8
keys, and Ed25519 keys. Strategy selection may prefer liquid instruments, but
the provider layer must not hard-code a narrow instrument universe.
The Binance `exchangeInfo` client fetches documented trading rules and symbol
metadata through the same provider-local public REST transport used by
server-time sync. The parser keeps precision, lifecycle dates, trigger
protection, liquidation fee, market-take bounds, order enums, and filter values
available for later sizing and risk decisions.
In live mode, exchange metadata is refreshed periodically from the active
runtime config so symbol/filter/rate-limit changes can be picked up without a
process restart. Backtest mode must use deterministic metadata fixtures instead
of live refresh.
The catalog declares every currently targeted Binance trading product family,
even when disabled. Spot user data is modeled as WebSocket API based, margin
user data uses `listenToken`, and futures/options user data uses `listenKey`;
activation remains a runtime target decision validated before use.
Binance trading capabilities are also provider-local: documented order paths,
order types, response modes, position-side support, reduce-only support, and
product-specific flags are declared per market and validated against the
connector's market-type capability map before startup.
Provider-local order command validation is separate from REST submission. A
candidate Binance order must satisfy the active market's supported enums,
feature flags, and mandatory parameter combinations before later execution code
can turn it into a signed request. The validator also blocks known Binance
collisions such as `priceMatch` with `price`, `GTD` without `goodTillDate`, and
`reduceOnly` on hedge-mode orders.
The Binance order client owns signed create, query, list-open, and cancel calls
and converts Binance error responses into typed exceptions without logging
credentials or signed payloads.
The Binance user-data stream client owns API-key-authenticated start, keepalive,
and close calls for configured listen-key or listen-token streams. It does not
use the API secret for REST listen-key lifecycle calls.
The Binance user-data event mapper converts documented Spot/Margin execution
reports and balance events plus USD-M/COIN-M futures order-trade and
account-update payloads into the core Avro execution, balance, and position
event envelopes. Runtime WebSocket consumers must publish those envelopes
through the normal event bus and reconcile them against REST snapshots before
the connector can be treated as an execution adapter.
The Binance user-data event publisher is the private-stream listener boundary:
it maps incoming provider payloads and publishes core envelopes through
`TradingEventBus`, while leaving strategy intent and execution planning outside
the provider module.
The Binance user-data stream runtime composes the REST listen-key or
listen-token lifecycle with the private WebSocket supervisor. It starts the
stream id, opens the private socket through the endpoint planner, schedules
renewal, restarts token-style streams that have no keepalive path, and only
calls REST close when Binance exposes a close endpoint for that product.
`user_data.runtime_enabled` is the catalog-backed switch for attaching this
runtime to `BinanceExchangeModule`. The default is false. If it is enabled, the
module requires a `TradingEventBus` before connecting so private user-data
events cannot be consumed and then lost.
REST clients parse Binance rate-limit headers after every response and retain
the latest observed request-weight, order-count, and retry-after values for
later risk, throttling, and observability wiring.
The Binance WebSocket endpoint planner builds raw and combined stream URIs from
the active market config, including routed USD-M paths, stream-count limits,
ping/pong timing, and reconnect-before-expiry scheduling. Live WebSocket reading
will use this plan rather than hard-coded URLs. The lifecycle client keeps the
planned endpoint, listener callbacks, idempotent close behavior, and reconnect
decision in a transport boundary so the concrete socket implementation can be
tested separately from URL and rollover policy. The Reactor Netty transport is
the first concrete transport and has opt-in live smoke tests that run through
the configured live target.
The WebSocket supervisor owns controlled reconnects: it rolls connections over
before Binance's 24-hour expiry point and schedules retry reconnects after
active connection errors or unexpected closes.
The Binance market-data event mapper is the public-stream normalization
boundary for documented trade, aggregate trade, book ticker, depth snapshot,
depth delta, mark price, and kline payloads. It converts Binance payloads into
core Avro market-data envelopes without strategy or execution decisions. The
market-data event publisher attaches that mapper to a WebSocket listener and
publishes through `TradingEventBus`.
The Binance market-data stream runtime opens configured public raw or combined
streams through the endpoint planner and WebSocket supervisor, so rollover and
reconnect behavior remains shared with the private-stream runtime.
`market_data.runtime_enabled` is the catalog-backed switch for attaching this
runtime to `BinanceExchangeModule`. The default is false with explicit
`connection_mode`, `route`, and `streams` parameters. It can also derive
provider stream names from refreshed Binance exchange metadata using configured
symbol templates and provider-level status, quote-asset, contract-type, and
max-symbol filters. The provider module owns only this transport subscription
coverage; core and strategy code still decide admission and trading intent from
provider-agnostic metadata/projection contracts. Scheduled exchange-metadata
refreshes can now call a provider-agnostic exchange-module hook; Binance uses
that hook to re-render metadata-derived market-data streams and reconnect the
public websocket only when the effective plan changes. If the runtime is
enabled, the module requires either configured streams or valid derived stream
templates plus a `TradingEventBus` before connecting so market-data events
cannot be consumed and then lost.
The Binance REST snapshot event mapper converts open-order, futures
account/balance, futures position-risk, cross-margin account, and
isolated-margin account snapshots into core Avro reconciliation envelopes. It
does not decide when reconciliation runs; that belongs to a runtime that can
compare stream state, REST state, and journaled events with clear ordering and
idempotency rules.
The checked-in Binance live smoke tests are opt-in. They load `active.json`,
merge `application-{environment}.json`, and use the same connector code for
demo and real. Credentials may come from process environment variables or local
`api.env`; `api.env` must stay ignored. Real-target smoke tests require an
extra explicit `-Dbinance.live.smoke.allowReal=true` guard.

### Binance Capability Review

The Binance connector tracks the official Binance documentation as the source
of truth. As of the current code, the connector covers these foundations:

- HMAC, RSA PKCS#8, and Ed25519 signing.
- Server-time synchronization and `recvWindow` validation.
- Product-family config for Spot, Cross Margin, Isolated Margin, USD-M Futures,
  COIN-M Futures, and Options.
- Options order request construction is product-aware for documented `/eapi`
  client-order, MMP, and post-only field names: new orders use
  `clientOrderId`, `isMmp`, and `postOnly`, while Spot/Futures continue to use
  their own client-order and post-only conventions.
- Options read-only account coverage includes catalog-backed
  `/eapi/v1/marginAccount` and `/eapi/v1/position` clients for account
  balances, account Greeks, and option positions.
- Options MMP coverage includes catalog-backed `/eapi/v1/mmp`,
  `/eapi/v1/mmpSet`, and `/eapi/v1/mmpReset` clients. State-changing MMP
  config/reset operations are disabled by default and must be explicitly
  enabled in config.
- Options auto-cancel kill-switch coverage includes catalog-backed
  `/eapi/v1/countdownCancelAll` config get/set and
  `/eapi/v1/countdownCancelAllHeartBeat` heartbeat clients. State-changing
  config and heartbeat operations are disabled by default and must be
  explicitly enabled in config because they affect live order cancellation and
  post-timeout order acceptance.
- Options trading reconciliation inputs include catalog-backed
  `/eapi/v1/historyOrders`, `/eapi/v1/openOrders`, `/eapi/v1/userTrades`,
  `/eapi/v1/commission`, and `/eapi/v1/exerciseRecord` request/client coverage
  with options-specific query-shape validation and typed parsing for commission
  and exercise records.
- Options REST snapshot reconciliation includes catalog-backed margin-account
  and position snapshot sources, disabled by default, mapped into core balance,
  position, and risk-update envelopes for account assets, option positions, and
  account Greeks.
- Options user-data stream mapping covers documented `ORDER_TRADE_UPDATE`,
  direct `/private` `ACCOUNT_UPDATE`, `BALANCE_POSITION_UPDATE`,
  `GREEK_UPDATE`, and `RISK_LEVEL_CHANGE` payloads into core execution,
  balance, position, and risk-update envelopes.
- `exchangeInfo` loading and parsing for symbol filters, order enums,
  precision, rate limits, trigger protection, and lifecycle metadata.
- Basic signed order create, query, open-orders, and cancel requests.
- Spot pegged-order command fields, validation, and signed request parameters.
- Margin `sideEffectType` and `autoRepayAtCancel` command fields, validation,
  and signed request parameters.
- Margin borrow/repay signed request/client support with catalog-backed path,
  cross/isolated validation, and typed transaction-result parsing.
- Margin transfer-history and max-transferable signed request/client support
  with catalog-backed paths, documented transfer-history query limits, and
  typed transfer/amount parsing.
- Margin OCO, OTO, and OTOCO order-list placement with catalog-backed paths,
  documented margin controls, legacy OCO parameter mapping, and typed
  order-list parsing.
- Margin cross-account, isolated-account, isolated-account-limit, and
  trade-coefficient risk reads with catalog-backed paths, documented isolated
  symbol limits, and typed parsing.
- Margin prevented-match history with catalog-backed path, documented
  `isIsolated` handling, margin-specific pagination constraints, and shared
  typed parsing.
- Margin read-only special-key list/query support with catalog-backed paths and
  typed parsing. State-changing special-key create/edit/delete/exit workflows
  require separate guardrails because they mutate account/API-key state.
- Margin special-key create, IP-edit, delete, and exit-special-key-mode signed
  requests with catalog-backed paths, documented IP and permission-mode
  validation, typed creation parsing, and explicit config guards. Catalog
  defaults keep special-key mutations and exit disabled.
- Futures `workingType` and `priceProtect` command fields, validation, and
  signed request parameters.
- Futures position-mode, margin-type, initial-leverage, and USD-M multi-assets
  mode request clients with catalog-backed paths, enums, and leverage bounds.
- Standard USD-M and COIN-M futures config rejects portfolio-margin accounts
  instead of routing `/papi` semantics through the standard futures connector.
- Spot, margin, USD-M futures, and COIN-M futures all-orders and account-trade
  history clients with catalog-backed paths, documented query-shape validation,
  and response parsing for later reconciliation.
- USD-M and COIN-M futures cancel-all and countdown-cancel-all clients with
  catalog-backed paths for outage and emergency-order cleanup workflows.
- USD-M and COIN-M futures batch-order placement clients with catalog-backed
  paths, max-5 batch validation, reused new-order validation, and per-item
  error parsing.
- USD-M and COIN-M futures modify-order clients with catalog-backed paths,
  documented order-id/client-order-id identity, LIMIT modification fields, and
  price-match collision validation.
- USD-M and COIN-M futures modify-multiple-orders clients with catalog-backed
  paths, max-5 batch validation, reused modify-order validation, and per-item
  error parsing.
- USD-M and COIN-M futures modify-history clients with catalog-backed paths,
  documented order-id/client-order-id query shape, max-100 limit validation,
  and amendment before/after parsing.
- USD-M and COIN-M futures cancel-multiple-orders clients with catalog-backed
  paths and documented order-id/client-order-id batch-shape validation.
- USD-M and COIN-M futures balance, account-information, and position-risk
  snapshot clients with catalog-backed paths and product-specific query-shape
  validation.
- USD-M and COIN-M futures ADL quantile and force-order history clients with
  catalog-backed paths for liquidation/ADL monitoring.
- USD-M and COIN-M futures income-history and funding-rate-history clients with
  catalog-backed paths for funding-cost and realized-PnL reconciliation.
- Spot commission-rate client with catalog-backed path and typed parsing for
  standard, special, tax, and BNB-discount commission fields.
- Spot prevented-match history client with catalog-backed path, documented
  query-shape validation, and typed STP match parsing.
- Spot amend-keep-priority client with catalog-backed path, documented
  identity/new-quantity validation, and typed amended-order parsing.
- Spot cancel-replace client with catalog-backed path, reused replacement-order
  validation, documented cancel mode/restriction validation, and typed
  success/partial-failure parsing.
- Spot SOR order and SOR test-order clients with catalog-backed paths,
  SOR-specific request validation, typed SOR fill parsing, and optional
  commission-rate test parsing.
- Spot OCO order-list client with catalog-backed path, documented leg-shape
  validation, and typed order-list/order-report parsing.
- Spot OTO order-list client with catalog-backed path, documented working and
  pending order validation, and shared typed order-list/order-report parsing.
- Spot OTOCO order-list client with catalog-backed path, documented working
  order and pending OCO-pair validation, and shared typed order-list parsing.
- Spot OPO order-list client with catalog-backed path, documented working
  order and pending order validation, and typed order-list parsing.
- Spot OPOCO order-list client with catalog-backed path, documented working
  order and pending OCO-pair validation, without adding an unsupported pending
  quantity field.
- Margin OCO, OTO, and OTOCO order-list clients with catalog-backed paths,
  margin controls, legacy margin OCO parameter mapping, and typed order-list
  parsing. Margin OPO/OPOCO placement is not claimed because the current
  Binance margin docs do not expose those placement pages.
- Margin cross-account, isolated-account, isolated-account-limit, and
  trade-coefficient risk clients with catalog-backed paths and typed parsing.
- Margin prevented-match and read-only special-key clients with catalog-backed
  paths and typed parsing.
- Guarded margin special-key mutation clients for create, IP edit, delete, and
  exit-special-key-mode. These operations are unavailable unless the active
  profile explicitly enables the matching config guard.
- Catalog-backed order feature enums and limits for price-match, futures
  trigger controls, pegged-order, and margin side-effect controls.
- Public WebSocket stream endpoint planning and reconnect/rollover policy.
- Spot WebSocket API order placement over a configurable WebSocket API endpoint,
  with signed `order.place` requests, outbound WebSocket transport support, and
  correlated response/error parsing.
- User-data listen-key or listen-token lifecycle for configured products.
- User-data event mapping for Spot/Margin execution reports and balance
  updates plus USD-M/COIN-M futures order-trade and account-update payloads
  into core Avro execution, balance, and position envelopes.
- User-data event publisher listener that converts private WebSocket payloads
  into core events and hands them to the core event bus.
- User-data stream runtime component that attaches listen-key or listen-token
  REST lifecycle management to a managed private WebSocket supervisor.
- Opt-in `BinanceExchangeModule` lifecycle wiring for user-data runtime, guarded
  by `user_data.runtime_enabled` and `TradingEventBus` availability.
- Public market-data event mapping and publisher boundary for documented trade,
  aggregate trade, book ticker, depth snapshot, depth delta, mark price, and
  kline WebSocket payloads.
- Opt-in public market-data stream runtime and `BinanceExchangeModule`
  lifecycle wiring, guarded by `market_data.runtime_enabled`, configured
  streams, and `TradingEventBus` availability.
- REST snapshot-to-core-event mapping for open orders, futures account/balance,
  futures position risk, cross-margin account, and isolated-margin account
  snapshots.
- REST snapshot reconciliation publisher boundary that publishes mapped
  snapshot envelopes through the core event bus with runtime observation
  timestamps.
- Catalog-backed REST snapshot reconciliation runtime, disabled by default,
  that can periodically call configured open-order, futures account/position,
  and margin account snapshot sources and publish normalized reconciliation
  events.
- Reconciliation runs aggregate configured snapshot sources into one ordered
  publish batch and suppress duplicate event IDs inside the run.
- The runtime also keeps a catalog-configured bounded in-memory window of
  recently published reconciliation event IDs and suppresses repeats across
  runtime runs.
- If the core trading-event journal is available, Binance reconciliation
  startup decodes journaled REST reconciliation events and seeds that bounded
  event-ID window, reducing duplicate publication after process restart.

The connector is not yet complete enough to be called a full Binance execution
adapter. Known gaps that must remain on the plan:

- Active margin transfer placement and margin OPO/OPOCO placement are not
  exposed in the current Binance margin docs; do not claim them until Binance
  documents endpoints for them.
- Options support is still deliberately incomplete. The connector must not
  enable real options trading until REST snapshot comparison is backed by
  durable projection state and projection-backed ordering/idempotency is
  implemented and tested against current Binance documentation.
- User-data payload mapping, event-bus publishing, listen-key/listen-token
  runtime supervision, and opt-in ExchangeModule lifecycle wiring are
  implemented. Market-data payload mapping, publishing, subscription runtime,
  and opt-in ExchangeModule lifecycle wiring are implemented. REST snapshot
  event mapping, publishing, and catalog-backed runtime scheduling are
  implemented. Core now has an in-memory trading-state projection for order,
  execution, balance, position, and risk-update events with event-ID dedupe and
  stale per-entity update rejection. Order state records bot-managed identity,
  update source, execution type, and external/unplanned intervention detection.
  Binance reconciliation can compare REST snapshots against projected state and
  record provider-agnostic reconciliation confidence for matched, missing, and
  mismatched entities. Core has a configurable order risk gate and order-command
  pipeline that consume that confidence. The pipeline has configurable
  in-memory command/idempotency-key dedupe, publishes a risk decision first,
  and only submits via an `OrderExecutionGateway` after approval. Binance
  implements the first new-order gateway and maps Binance responses into
  normalized order-result events, including configured unknown execution status.
  The complete durable execution state machine is not wired yet.
- The exchange module lifecycle currently connects config/metadata primitives;
  it is not yet the risk-gated execution engine.

These gaps are intentional roadmap items. They should be closed before the bot
is described as real-trading ready.

## Trading Events

Trading events are schema-first Avro contracts owned by `bot-core`. The source
schemas live under `bot-core/src/main/avro/io/github/manu/events/v1`, are kept
on the runtime classpath, and generate Java SpecificRecord classes during
`compileJava`.

Version 1 covers market data, order commands, order results, execution reports,
balance updates, position updates, risk decisions, intervention
acknowledgements, strategy signals, and config changes. Decimal exchange values
are represented as exact strings so connector code does not lose precision
before symbol filters and risk logic interpret tick size, step size, notional,
and product-specific scale.

The first compatibility rule is conservative: nullable extension fields need
explicit `null` defaults, and the checked-in tests verify schema availability,
self compatibility, optional-field evolution, generated class availability, and
binary round trips with explicit expected values.

Event routing is also declared in core. Each event type maps to one Avro schema,
one generated SpecificRecord class, one Redpanda topic, one key subject, one
value subject, and one dead-letter topic. Current topic names use the
`trading.v1.{event-name}` pattern and Schema Registry subjects use the standard
`{topic}-key` and `{topic}-value` shape.
All topics use the shared `TradingEventKey` Avro record for keys. Its
`partitionKey` is canonical lower-case text built from event type, entity type,
runtime target, symbol, and entity id so account-level, symbol-level,
order-level, strategy-level, and config-level events have predictable
partitioning.
Core wraps outgoing events in a `TradingEventEnvelope`: event type, route,
typed Avro key, and typed Avro value must agree before serialization. This is
the handoff boundary that later Redpanda producers should consume.
The schema manifest exposes each event route with its key/value schema names and
Avro parsing fingerprints. Tests assert uniqueness and self-compatibility so
schema drift is visible before messaging infrastructure is wired in.

## Execution Pipeline

Core owns the provider-agnostic order-command pipeline. It is disabled by
default under `trading.execution.pipeline.enabled` and must be explicitly
enabled by the active runtime profile. When enabled, an order command first
passes through configurable in-runtime idempotency under
`trading.execution.idempotency`. The default is enabled with a bounded
`max_tracked_keys` catalog value. The tracker scopes command IDs and
idempotency keys by provider/environment/account/market and rejects duplicates
before risk evaluation or provider submission can happen. Duplicate decisions
include the duplicate identity kind and runtime scope so operators can audit
why a command was rejected without reverse-engineering tracker state.

After idempotency admission, the core risk gate evaluates reconciliation
confidence and publishes a `RiskDecisionEvent`. Only approved commands can be
submitted through an `OrderExecutionGateway`. Provider modules implement that
gateway for their concrete exchange APIs and return normalized
`OrderResultEvent` values; strategies never call provider gateways directly.

This in-memory idempotency prevents blind duplicate submits inside one running
process. It is not the final professional lifecycle. Durable restart-safe
idempotency still has to bind strategy signal, command, risk decision, exchange
acknowledgement, user-data execution report, REST reconciliation, unknown
status recovery, and final position/account projection into one ordered state
machine.

Manual or external exchange-side changes are safety-relevant state changes. The
order projection records whether an order is bot-managed, whether the latest
update came from submit result or user-data, and whether user-data exposed an
external order or an unplanned cancel/modify of a managed order. That metadata
feeds the order risk gate: by default, new order commands for that runtime
target become `MANUAL_REVIEW` while unresolved external/unplanned order
intervention is present. Manual intervention behavior is configured under
`trading.execution.risk-gate.manual-intervention`: legacy reject flags remain
supported, and the explicit `external_order_action` and
`external_position_action` values decide whether unresolved interventions send
commands to `MANUAL_REVIEW`, `REJECT_NEW_COMMANDS`, or `ALLOW_NEW_COMMANDS`.
The catalog default is `MANUAL_REVIEW` for both order and position
interventions. `MANUAL_REVIEW` risk decisions are projected as manual-review
decisions keyed by command id, included in durable projection snapshots, and
exposed by the operator API while their intervention reason remains unresolved.
Clearing an order or position intervention is explicit and auditable: an
`InterventionAcknowledgementEvent` is journal/replay
compatible and clears a matching intervention in the projection. Order
acknowledgements are keyed by client order id. Position acknowledgements are
keyed by symbol and carry `position_side` in event attributes. Core exposes an
acknowledgement service that validates order-level and position-level operator
review requests against the current projection before publishing the event
through `TradingEventBus`, allowing journal decoration and normal routing to
capture the audit trail without blind or mismatched acknowledgements. The
optional operator HTTP API is disabled by default and controlled by
`trading.intervention.operator-api.enabled`; when enabled it requires
`X-Operator-Token` to match
`trading.intervention.operator-api.operator-token` before listing unresolved
order or position interventions, listing pending manual-review decisions, or
accepting an order or position acknowledgement. A later execution policy must decide whether to
stand down, replace, re-plan, hedge, or require operator review. Position
projection records update source and conservatively flags user-data or REST
position-size changes as external interventions when no managed bot order exists
for the same target/symbol. The order risk gate stops new commands while such
position interventions are unresolved unless the configured action is
`ALLOW_NEW_COMMANDS`.
Execution reports can also carry realized-PnL attributes. After an execution
report passes projection duplicate and stale-update checks, the projection
accumulates `realizedProfit`, `realizedPnl`, or `realized_pnl` into
account-scope and symbol-scope `DailyRealizedPnlState` keyed by provider,
environment, account, market, optional symbol, and UTC trading day. This state
is accounting/recovery input for realized-loss policy enforcement; it does not
by itself authorize or block exchange actions.
The same operator-token gate also protects the read-only runtime status
endpoint at `GET /internal/runtime/status`. That endpoint is provider-agnostic
core code: it reads the active or explicitly requested runtime target, the
projection, and reconciliation confidence, then returns readiness, explicit
blockers, market-data freshness counts, open exposure counts, active pauses,
external interventions, unknown order states, unresolved commands, and optional
strategy lifecycle state. It is an observability/control-plane surface only; the
order execution pipeline, risk gate, idempotency, journal, reconciliation, and
provider gateway remain the trading authority.
`RuntimeStatusGaugeBinder` exports the same active-target view as bounded
Micrometer gauges for readiness state, reconciliation state, readiness blockers,
projection safety counts, and projected market-data freshness counts. The gauge
labels are fixed enums such as `readiness`, `status`, `blocker`, and `kind`; they
do not include order ids, exchange ids, signal ids, or symbols. Source-controlled
Prometheus alerts and a Grafana dashboard consume these metrics for operational
readiness monitoring.
Position fill-to-delta causality still needs durable execution-state wiring
before live automation can be called complete. The remediation advisor now
keeps position remediation conservative: a flat external position recommends
stand-down/replan, an open external position recommends rehedge/replan, and an
unparseable position amount requires operator review instead of suggesting an
automated hedge.
Those recommendation actions are driven by
`trading.intervention.automated-policy`, so demo and real can keep the same
code path while using different remediation posture. The current layer emits
recommendations such as adopt, amend, reduce, close, hedge, pause-symbol, and
pause-account; a later executor still has to turn those recommendations into
risk-bounded exchange commands.
`InterventionAutomatedDecisionService` is the policy-to-decision bridge. When
enabled, it publishes auditable `REMEDIATION_DECISION` events from current
recommendations, skips operator-review recommendations unless explicitly
allowed, deduplicates by `recommendation_event_id`, and caps each run with
`maxDecisionsPerRun`. This keeps unattended policy decisions replayable and
visible before any exchange executor is introduced.
`PAUSE_SYMBOL` and `PAUSE_ACCOUNT` decisions are projected into durable pause
governance state. This state is included in projection snapshots, exposed
through the operator API, suppresses strategy-planned order commands for paused
targets, and makes the order risk gate reject non-cancel commands for paused
accounts or symbols. Cancel commands remain admissible under pause governance
so the bot can still reduce risk by cancelling unsafe or unwanted open orders.
Pause releases are also recorded as remediation decision events, allowing
operator-controlled release of a symbol or account pause to replay and restore
as inactive pause governance state instead of a transient toggle. Pauses may
also carry a `pause_expires_at` ISO-8601 instant attribute; expired pauses stay
auditable in projection state but no longer block strategy admission or order
risk-gate admission. Pause override is a separate order risk-gate policy:
source-controlled defaults keep it disabled, and when enabled it requires
`pause_override=true`, `pause_override_by`, `pause_override_reason`, and a
time-bounded `pause_override_expires_at` command attribute inside the configured
maximum override window. Strategy signal planning still suppresses paused
targets, so strategy code cannot self-authorize around pause governance.
Pause activation decisions, successful pause releases, explicit pause override
attempts, and observed pause expiry transitions emit structured audit records
through `AuditLogger`. The same control points record Micrometer counters for
Prometheus scraping: release publication outcomes use
`trading.pause_governance.release.events`, and explicit override evaluations use
`trading.pause_governance.override.events`. Effective active pause counts are
exposed as low-cardinality gauges with
`trading.pause_governance.active.states` tagged by pause scope. Live-only
pause activation decision counters use
`trading.pause_governance.activation.events`; pause activations with a valid
`pause_expires_at` also increment
`trading.pause_governance.expiry.configured.events`. A scheduled pause expiry
monitor observes projected active pauses that have crossed `pause_expires_at`
and emits `pause_governance_expired` audit records plus the
`trading.pause_governance.expiry.transitions` counter exactly once per projected
pause expiry. It does not mutate projection state or call the exchange; expiry
effectiveness remains derived from the replayable pause attributes. Recent
pause activation, release, override, and expiry audit records are queryable
through the operator API. The audit trail keeps a bounded in-memory buffer by
default and can be backed by an append-only JSONL file store when
`trading.audit.pause-governance.file-store.enabled=true`; the endpoint reads
the file store when it is configured, so recent pause governance audit events
remain available after restart. A production-grade indexed JDBC audit store can
also be enabled with `trading.audit.pause-governance.jdbc-store.enabled=true`;
it stores the full audit event payload plus indexed provider, environment,
account, market, event, remediation, pause target, actor, and occurrence-time
columns, and can initialize its schema when
`trading.audit.pause-governance.jdbc-store.initialize-schema=true`. JDBC is
disabled by default and must receive its URL and credentials from deployment
secrets. Dashboard deployment/customization and deployment-specific external
alert routing are still future work. Audit store
persistence or query failures increment
`trading.pause_governance.audit_store.failures`, tagged by operation and store
type, so dashboards and alert rules can detect degradation even when the
in-memory fallback still serves recent events. Prometheus-compatible alert rules
for the pause governance metrics live in
`ops/prometheus/pause-governance-alerts.yml`; Alertmanager routing for operator
and platform notification channels lives in
`ops/alertmanager/pause-governance-alertmanager.yml`; and an importable Grafana
dashboard lives in `ops/grafana/pause-governance-dashboard.json`. Real webhook
URLs, PagerDuty routing keys, SMTP credentials, and email addresses must be
injected through deployment secrets, not source-controlled config. A Google
Cloud Alertmanager renderer turns the
placeholder-only routing profile into a demo or real rendered config from Secret
Manager, validates the required placeholder set, fails closed on missing secret
versions or unresolved placeholders, and writes restricted-permission output
without printing secret values. The first Google Cloud deployment contract for Binance
USD-M futures demo lives in `ops/google-cloud/demo-usdm-futures-deployment.yml`;
it selects Cloud Run, binds the active target through environment variables,
maps Binance credentials, operator token, audit JDBC credentials, and
Alertmanager receiver substitutions to Secret Manager names, disables ephemeral
JSONL audit persistence and file projection snapshots for Cloud Run, selects
indexed JDBC audit and projection persistence with deployment-owned schema
migration, 180-day retention, Cloud SQL automated backups, and 90-day restore
drills, and declares journal archive policy. Deployment contracts now use the
cloud-neutral schema in `ops/deployment/deployment-contract.yml`; AWS
equivalents live in `ops/aws/demo-usdm-futures-deployment.yml` and
`ops/aws/real-usdm-futures-deployment.yml`, mapping the same app-facing runtime
variables to ECS Fargate, AWS Secrets Manager, RDS PostgreSQL, and S3 archive
policy without changing trading code. Matching Google Cloud and AWS real
contracts select the real Binance USD-M futures target with isolated real
credentials and real audit/projection secrets, 365-day state retention, 30-day
restore drills, and remediation exchange execution disabled until promotion
evidence, manual approval, and explicit real-operation allowlists are supplied by
deployment.

The root `Dockerfile` is the production runtime image contract for Cloud Run and
ECS. It packages the prebuilt `bot-app` Spring Boot jar, copies
source-controlled config to `/app/config`, defaults to the `live` profile, sets
the Chronicle Queue JVM module-access flags, runs as a non-root user, exposes
the readiness health endpoint, carries OCI source/revision/version/created
labels, and is built by GitHub Actions after the Gradle quality gate without
publishing yet. GitHub Actions uploads Buildx metadata as an artifact so the
image build can be tied back to a commit before registry publication is added.
The guarded Google Cloud image-publish workflow is manual, environment-gated,
verifies that the requested commit passed the `Security` workflow, uses OIDC
Workload Identity, publishes to Artifact Registry with commit-SHA tags, and
uploads publish metadata.
The guarded Cloud Run deployment workflow is also manual and environment-gated:
it verifies `Security` success, verifies the commit-tagged image exists, applies
the Google Cloud deployment contract runtime variables and Secret Manager
bindings, attaches the configured Cloud SQL instance, blocks unauthenticated
access, labels the revision with the source commit, and uploads deployment
metadata. The guarded Cloud Run smoke workflow is manual and environment-gated
as well: it verifies `Security` success for the requested commit, confirms that
the latest ready revision is labeled with that commit and runs the matching
commit-tagged image, invokes the private readiness endpoint with a Google
identity token, and uploads smoke evidence. A separate guarded Binance live
smoke workflow is manual and environment-gated; it verifies `Security` success,
selects the requested demo or real active target inside the CI workspace, runs
the existing server-time, passive order lifecycle, user-data stream, and
market-data websocket smoke tasks with environment-scoped Binance credentials,
uploads smoke evidence, and requires an explicit confirmation input before any
real target can run. The guarded Cloud Run rollback workflow is also manual and
environment-gated: it requires an explicit target revision and rollback commit
SHA, verifies `Security` success for that commit, proves that the target
revision belongs to the selected service and matches the expected
app/environment/commit labels plus commit-tagged image, routes all traffic to
that existing revision, verifies private readiness, and uploads rollback
evidence. The Google Cloud bootstrap script prepares the current
workflow foundation by enabling required APIs, creating Artifact Registry, the
journal archive bucket, Cloud SQL PostgreSQL, demo/real databases, separate
audit/projection database users, service accounts, IAM bindings, GitHub OIDC
Workload Identity Federation, and Secret Manager containers/versions. It also
defaults non-secret deployment values, reads the Binance demo key and secret
from `api.env`, generates operator-token and Cloud SQL password secret versions
when absent, and generates Cloud SQL JDBC URL/username/password secret versions
when no runtime override is supplied. It can optionally create an idempotent
project-scoped monthly Google Cloud budget alert when budget alerts are enabled
and a billing account is supplied, with default current-spend and forecasted
thresholds. When `GITHUB_CONFIGURE_ENVIRONMENTS=true`, the same bootstrap path
uses GitHub CLI to create or update the `demo` and
`real` GitHub environments with the required Google Cloud OIDC/service-account
secrets and deployment variables, keeping the application runtime contract
unchanged. The Google Cloud operations runbook defines the operator sequence and
evidence for bootstrap, publish, deploy, smoke, alert rendering, rollback,
emergency stop, controlled drain, incident handling, and real promotion. The
scenario-specific incident response runbook defines response paths for exchange
outages, stale streams, reconciliation degradation, external intervention,
unknown outcomes, failed deployments, persistence failures, alerting outages,
credential events, cost spikes, and real-environment incidents.

Google Cloud managed platform alert policy templates live under
`ops/google-cloud/monitoring/alert-policies` and are rendered by
`ops/google-cloud/provision-monitoring-alert-policies.sh` for either demo or
real. They are not trading logic; they monitor platform risk around Cloud Run
5xx responses and Cloud SQL CPU/disk utilization, and they accept Cloud
Monitoring notification channel resource names without committing receiver
secrets.

Operational evidence templates under `ops/evidence` define the release and
demo-burn-in records required for promotion decisions. They bind together commit
identity, CI, image, Cloud Run revision, smoke results, secret-binding proof,
runtime config diff, trading-state projection, reconciliation confidence,
observability, rollback/emergency drills, incident state, market-universe
coverage, and the final promotion decision without storing secret values. The
live release collector in the same directory materializes a sanitized demo or
real evidence bundle from committed contracts, config checksums, workflow ids,
Cloud Run metadata, and scrubbed observation files. The demo burn-in collector
materializes the pre-real promotion evidence bundle from committed demo/real
contracts plus supplied runtime-stage, market-universe, continuous metrics,
trading metrics, drill, observability, and incident evidence. Both collectors
are offline-first and do not fetch or print secret values.

`InterventionRemediationCommandPlanner` is the first executor-boundary layer. It
turns a remediation decision into a deterministic internal plan, validates that
the projected order or position still carries the matching intervention, and
marks the plan as stale or insufficient when the target has changed or lacks
safe sizing data. Order `CLOSE` for a projected external order now becomes an
exchange-executable `CANCEL_ORDER` plan with target identity and an
`order_execution_pipeline` execution path. One-way position `CLOSE` and bounded
one-way position `REDUCE` plans also become exchange-executable when the
projected position side is `BOTH`: close uses the full projected absolute
position amount, reduce requires explicit `reduce_quantity` or
`reduce_fraction` decision attributes bounded by the projected amount, and both
submit `NEW MARKET` orders on the opposite side with `reduceOnly=true` and
`closePosition=false` through the normal order execution pipeline. Hedge-mode
position `CLOSE` and bounded `REDUCE` plans can also become exchange-executable
when `position_order_policy.hedge_mode_execution_enabled=true`; they submit
`NEW MARKET` orders on the opposite side with the projected `positionSide`
(`LONG` or `SHORT`), bounded quantity, `reduceOnly=false`, and
`closePosition=false`. Pause and ignore remain governance intents until bounded
command-specific executors are implemented. Order adoption is a gated
projection-governance transition rather than an exchange command: when
`order_adoption_acknowledgement_enabled=true`, an order-scope `ADOPT`
remediation decision for a matching non-managed external order publishes an
auditable intervention acknowledgement with adoption metadata, and replay marks
the order as bot-managed while clearing the external intervention. Adopted
orders are still distinct from bot-created orders at the risk-gate boundary:
`target_order.allow_adopted_target_orders=false` by default blocks ordinary
target-order commands for adopted targets, while explicit adopted-order
remediation policy can allow a narrow command path.
`adopted_order_lifecycle_policy` defaults to preserve adopted orders and can
explicitly allow cancel or amend for configured provider, market, symbols,
statuses, projection freshness, exchange-order-id requirements, and pending or
unknown modify blockers. Replace and ambiguous-outcome rollback remain policy
metadata only until dedicated execution semantics exist. Order amendment is
policy-gated and exchange-executable only for bounded managed-order changes:
`AMEND` decisions for managed-order interventions are checked against
`managed_order_amendment_policy` for provider, market, symbol, ownership,
allowed order type, allowed fields, quantity direction and drift, price drift,
projection freshness, open-order status, and target identity requirements.
Adopted-order amendments must pass both `managed_order_amendment_policy` and
`adopted_order_lifecycle_policy`. A policy-qualified amendment builds an
idempotent `MODIFY` command with projected side/order type and requested or
retained price/quantity, then submits through `OrderExecutionPipeline`; Binance
futures preflight validates the `MODIFY` target and parameters before gateway
submission. Unknown `MODIFY` outcomes
retain command-action metadata in projection, and the planner blocks repeat
amendments while a prior modify is pending or unknown until reconciliation
updates the target. Unsupported amendment shapes remain blocked rather than
using cancel/replace fallback.
`trading.intervention.remediation-executor-policy` is the explicit executor
safety boundary. The checked-in catalog keeps the executor disabled, exchange
execution disabled, report-only mode enabled, real environments blocked, and
executable operation names empty for safe startup. It also keeps
`position_order_policy.one_way_reduce_only_enabled=false`; the provider, market,
position side, order type, reduce-only requirement, close-position prohibition,
hedge-mode block, symbol allowlist, quantity cap, notional cap, unbounded
notional behavior, separately disabled hedge-order execution, required margin
type, required hedge account position mode, leverage bounds, and missing
account-risk metadata behavior are all explicit catalog policy values. The
catalog also exposes optional `max_account_position_notional`,
`max_symbol_position_notional`, `max_account_unrealized_loss`,
`max_symbol_unrealized_loss`, `min_account_margin_balance`,
`max_account_margin_drawdown_fraction`, `max_account_margin_utilization`, and
`max_account_daily_realized_loss` and `max_symbol_daily_realized_loss` caps. The
position-notional caps use projected gross open-position notional after the
planned remediation action and are risk-reduction aware, so close/reduce actions
that lower already-excessive exposure are not blocked solely because current
exposure was already above the cap. The unrealized-loss caps read current
projected open-position unrealized PnL and block non-reducing hedge remediation
once account or symbol loss exceeds the configured cap, while still allowing
close/reduce plans that lower risk. The daily realized-loss caps read projected
UTC account and symbol daily realized PnL and block non-reducing hedge
remediation once configured realized-loss caps are exceeded, while still
allowing close/reduce risk reduction. The account margin-balance
floor blocks non-reducing hedge remediation when current projected account equity
is below the configured floor, while still allowing validated close/reduce risk
reduction. The account margin drawdown cap compares current projected margin
balance against the stored max margin-balance high-watermark retained in
risk projection snapshots, then blocks non-reducing hedge remediation when the
drawdown fraction exceeds the configured cap. The margin-utilization cap reads
projected account-level risk and blocks position orders if maintenance margin
divided by margin balance exceeds the cap, or if the required account-risk
metadata is missing or invalid under the missing-risk policy.
Demo-live exchange execution is an explicit runtime override state: the policy
must be enabled, `exchange_execution_enabled=true`, `report_only=false`, the
operation must be allowlisted, one-way position order execution must be
explicitly enabled, and the position symbol/quantity/notional/account-risk caps
must admit the projected target before the checked-in demo runtime may submit
position close/reduce commands.
For `CLOSE` decisions only, an explicit chunking policy can convert an
oversized full close into a capped close chunk so the scheduled remediation
runner can reduce risk over multiple projected ticks without breaching the
configured max position quantity. Explicit `REDUCE` decisions still remain
strictly bounded by their requested size and the same caps.
Hedge-mode close/reduce requires a separate explicit
`hedge_mode_execution_enabled=true` runtime override. Hedge orders require both
`hedge_mode_execution_enabled=true` and
`hedge_position_order_enabled=true`; they open the opposite hedge-mode
`positionSide` with `reduceOnly=false`. Hedge-mode close/reduce and hedge
orders also require projected position-mode proof matching
`position_order_policy.required_position_mode=HEDGE`; missing or mismatched
account-mode metadata blocks the plan before it can reach execution. These
paths remain off in the checked-in demo runtime. Enabling exchange execution
requires the ready-plan,
fresh-projection, target-identity, and managed-pipeline gates to remain enabled,
so remediation cannot be configured to bypass the normal execution pipeline.
`InterventionRemediationExecutorService` consumes persisted remediation
decisions, regenerates current command plans through the planner, evaluates each
plan against the executor policy, caps each batch, and returns blocked, preview,
submitted, or no-action reports. The preview endpoint is a pre-execution report
surface and never submits commands. Execute mode currently supports
external-order `CLOSE` as `CANCEL_ORDER` plus one-way position `CLOSE` and
bounded `REDUCE` as reduce-only market orders and config-gated hedge-mode
position-side `CLOSE` and bounded `REDUCE` as `reduceOnly=false` market orders,
plus separately policy-gated hedge orders as opposite-position-side market
orders, all routed through `OrderExecutionPipeline`; the normal risk gate,
idempotency,
event bus, journal, projection, reconciliation, and provider gateway remain
authoritative. Provider gateways can now reject an approved command during
preflight before gateway submission; the pipeline publishes that as a rejected
risk decision with provider preflight attributes instead of sending a command to
the exchange. The Binance gateway uses this hook for new-order capability and
exchange-filter validation, including remediation-generated position market
orders, cancel target-identity validation, and futures modify target/parameter
validation.
`InterventionAutomatedRemediationRunner` is the scheduled live automation layer.
It is disabled by default. When enabled, each tick resolves either its explicit
target or the current active runtime target. By default it requires target-level
reconciliation confidence before it publishes decisions or executes remediation,
so a restart cannot resume automated remediation from a restored projection
before the target has been reconciled against exchange observations. Once that
gate passes, it executes already-projected remediation decisions through
`InterventionRemediationExecutorService`, and then publishes new automated
remediation decisions for the next projected tick. This ordering avoids relying
on same-tick projection updates from asynchronous event consumers while still
allowing unattended remediation when policy gates permit exchange execution.
Recovery coverage now verifies the same ordering after restoring a file snapshot
that contains an external-order intervention plus an already-projected automated
remediation decision: the restored runner evaluates that existing decision first
and does not republish a duplicate decision for the same recommendation event.
Recovery coverage also verifies that restored runner automation is skipped when
the target has no reconciliation observations after restart. Pause-governance
recovery coverage also verifies that active symbol pause state restored from a
file snapshot remains effective at the order risk-gate admission boundary after
restart.
The operator API exposes preview reports at
`GET /internal/interventions/remediation/executor/preview` so operators can see
the exact executor blocker before any exchange-executable remediation path is
enabled, and exposes policy-gated execution at
`POST /internal/interventions/remediation/executor/execute`.
Each preview or execute evaluation now emits
`trading.remediation_executor.outcome.events` for Prometheus scraping, tagged by
provider, environment, account, market, mode, operation, status, and bounded
executor reason. A disabled executor policy emits the same metric with
`operation=NONE`, `status=DISABLED`, and `reason=executor:policy_disabled`.
The metric intentionally excludes remediation ids and order ids to avoid
unbounded time-series cardinality.
Prometheus-compatible alert rules for these outcomes live in
`ops/prometheus/remediation-executor-alerts.yml`; the shared Alertmanager
profile routes them by `service`, `routing_hint`, and `severity`; an importable
Grafana dashboard lives in `ops/grafana/remediation-executor-dashboard.json`;
and the operator response runbook lives in
`ops/runbooks/remediation-executor.md`.

The LFA strategy runner emits
`trading.strategy.lfa.signal_runner.run.events` counters for disabled,
skipped, blocked, no-signal, and published outcomes. Labels are bounded to the
runtime target, enabled state, status, reason, and primary blocker; symbols and
signal ids are intentionally not metric tags. Prometheus-compatible rules in
`ops/prometheus/strategy-lfa-alerts.yml` alert on disabled,
lifecycle-blocked, reconciliation-blocked, budget-blocked, allocation-blocked,
and published-signal outcomes. An importable Grafana dashboard for the same
low-cardinality LFA runner metric lives in
`ops/grafana/strategy-lfa-dashboard.json`.

## Redpanda Messaging

Local Redpanda is defined in `docker-compose.redpanda.yaml`. It exposes the
Kafka API on `localhost:19092`, Schema Registry on `localhost:18081`, and the
Admin API on `localhost:19644`.

Spring wiring is controlled by `trading.messaging`. Messaging is disabled by
default so the application can start without a local broker. When enabled, core
creates Schema Registry, producer, dead-letter producer, and replay consumer
factory beans from typed configuration. Topic provisioning is separately gated
by `trading.messaging.topics.auto-create`.
Application code should depend on `TradingEventBus`, not Kafka classes. The
Redpanda implementation publishes typed envelopes and dead-letter records
through non-blocking Kafka callbacks.
Consumer code should enter through `TradingEventDispatcher`: it decodes raw
Schema Registry payloads, invokes a typed handler, and sends original bytes to
the matching dead-letter topic when decoding or handling fails.
`TradingEventConsumerService` polls records through a small consumer boundary,
dispatches them through the registered event-type handler, and commits offsets
only after handling or dead-letter publication completes.
`TradingEventConsumerLoop` is the Spring lifecycle wrapper around that service;
it is enabled separately from messaging and does not auto-start unless
`trading.messaging.consumers.auto-start` is set.

Topic names are derived from `TradingEventType` routes:

- Primary topics use `trading.v1.<event-name>`.
- Dead-letter topics use `trading.v1.<event-name>.dlq`.
- Schema Registry subjects use `<topic>-key` and `<topic>-value`.

Kafka payloads use the Schema Registry Avro wire format: magic byte, schema id,
then Avro binary payload. The schema id is verified against the expected Avro
parsing fingerprint on replay.

The Redpanda Testcontainers suite is opt-in because it needs Docker:

```text
./gradlew :bot-core:test -Dredpanda.integration.tests=true
```

When enabled, it publishes, replays, and dead-letters real Redpanda records.
Unit replay coverage also exercises every current event type through the
Schema Registry wire format so lifecycle, metadata, account, order, risk, and
strategy families remain replayable as schemas evolve.

## Durable Journal

Critical trading events are journaled through `TradingEventJournal`, a core
boundary that accepts the same typed Avro payloads used by Redpanda. The first
implementation uses Chronicle Queue so append and replay stay local, ordered,
and independent of database availability.

The journal stores event type, topic, key payload, and value payload. On replay,
the reader validates the stored topic against `TradingEventType` before exposing
the record. Corrupt or incomplete journal documents fail with `JournalException`
instead of returning partial data.

Chronicle Queue uses memory-mapped files and Java internals for performance.
Gradle test and Java execution tasks include the required Java 25 module access
arguments so local runs match the intended runtime shape.
Spring exposes the journal through `trading.journal`; it is disabled by default
and must be pointed at an explicit writable directory before use in the live
runtime.
When both Redpanda messaging and the journal are enabled, the trading event bus
is decorated so primary typed events are appended to the local journal before
they are published to Redpanda. Dead-letter records continue through the
dead-letter publisher and are not written to the primary event journal.
`JournalRecoveryService` replays raw Avro journal records through registered
typed handlers in journal order. Recovery fails fast on decode or handler
failure because partial state rebuilds are not acceptable for trading state.
When `trading.journal.recovery.enabled=true`, an early startup lifecycle runs
journal recovery before auto-starting messaging loops; any replay failure fails
the process.

The core trading-state projection also supports opt-in snapshot stores. A file
store is configured under `trading.projection.snapshot-store`; a
PostgreSQL-compatible JDBC store is configured under
`trading.projection.jdbc-store` with explicit URL, credentials, table prefix,
and schema initialization controls. Snapshots are loaded before journal recovery
and saved at lifecycle stop. JDBC snapshot persistence includes balance,
position, order, risk, daily realized PnL, manual-review decision, remediation
decision, pause-governance, and applied-event-id tables. The schema contract lives at
`bot-core/src/main/resources/db/projection/postgresql-schema.sql`. TimescaleDB
hypertable tuning and a production migration runner remain open; the journal
remains the authoritative crash recovery source.

Archive object names for journal exports are defined by
`TradingEventArchiveLayout`. The layout is deterministic and GCS-friendly:

```text
{prefix}/trading-events/v1/event_type={event-type}/topic={topic}/date={yyyy-MM-dd}/hour={HH}/{journal-index}.avrobin
```

The layout is a contract for later upload/archive code; hot-path trading should
append to the local journal and publish events without waiting on GCS or
database writes.

Operational retention, compaction, backup, restore, and post-restore
reconciliation procedures live in `ops/runbooks/persistence-recovery.md`. That
runbook requires side-effecting runtime components to stay disabled during
restore/replay and requires reconciliation confidence before order admission,
remediation execution, or strategy signal publication resumes.

## Demo And Real

Demo and real use the same execution engine. The allowed differences are:

- active target
- credentials/secrets
- endpoint/environment config
- deployment namespace
- account limits

There should be no separate toy execution code path for real-readiness work.
Switching from demo to real should be an active-target/config change, not a
code change. Defaults for bot behavior, Binance product capabilities, execution
limits, and strategy parameters belong in `config/catalog.json`; environment
files and runtime overrides may narrow or point those defaults at the selected
account, but they should not create a second implementation path.

## Quality Gate

Before a commit:

- Relevant unit and integration tests must pass.
- Spotless, Checkstyle, and SpotBugs must pass through the appropriate Gradle
  check task.
- Warnings should be corrected, not hidden.
- Tests should use explicit inputs and expected outputs.
- Comments should explain non-obvious design choices only.
- If a slice is pushed, GitHub Actions for that pushed commit must be checked
  before calling the work complete.
