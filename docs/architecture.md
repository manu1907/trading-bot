# Trading Bot Architecture

The bot is organized around one application core and pluggable runtime modules.

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

- `bot-core`: owns application startup, config loading, runtime identity,
  exchange/strategy contracts, validation, reload policy, and orchestration.
- `bot-exchange-binance`: owns Binance-specific config binding, REST/WebSocket
  behavior, metadata parsing, and Binance lifecycle.
- `bot-strategy-lfa`: owns the LFA strategy implementation and its strategy
  config once the strategy contract is complete.

Provider modules and strategy modules are discovered as Spring beans through
core contracts. Core should not import provider-specific config or implementation
types.

## Decision Boundary

Strategies explain why and what to trade; they emit provider-agnostic intents
with direction, confidence, horizon, urgency, risk budget, and execution
preferences. They do not choose Binance endpoints, order-list shapes, listen-key
flows, or product-specific flags.

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

## Runtime Target

A live runtime target is identified by:

```text
provider/environment/account/market
```

Example:

```text
binance/demo/main/usdm_futures
```

Spring profiles describe application mode: `live` or `backtest`. Exchange
environment describes venue/account context: `demo` or `real`.

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

The loader merges `catalog.json`, then the active environment file, then the
active runtime file. Unknown override paths are rejected. Runtime override
files are created as empty JSON objects when the active target has no file yet.
Runtime override writes are staged as candidate config, validated, then moved
into place atomically. A rejected write leaves the previous runtime file intact.
Root `version` and `schema` metadata cannot be changed by environment or
runtime patches. The current migration policy is `fail_fast`: an unsupported
schema id, schema version, config version, or migration policy stops startup
or reload instead of trying an implicit migration.

For backtest mode, only the backtest config is used.

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
`connection_mode`, `route`, and `streams` parameters. If it is enabled, the
module requires configured streams and a `TradingEventBus` before connecting so
market-data events cannot be consumed and then lost.
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
  stale per-entity update rejection. Binance reconciliation can compare REST
  snapshots against projected state and record provider-agnostic reconciliation
  confidence for matched, missing, and mismatched entities. Core has a
  configurable order risk gate and order-command pipeline that consume that
  confidence. The pipeline publishes a risk decision first and only submits via
  an `OrderExecutionGateway` after approval. Provider gateways and the complete
  durable execution state machine are not wired yet.
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
balance updates, position updates, risk decisions, strategy signals, and config
changes. Decimal exchange values are represented as exact strings so connector
code does not lose precision before symbol filters and risk logic interpret
tick size, step size, notional, and product-specific scale.

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
and must be pointed at an explicit writable directory before use in live or
backtest runtime.
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
and saved at lifecycle stop. The schema contract lives at
`db/projection/postgresql-schema.sql`. TimescaleDB hypertable tuning and a
production migration runner remain open; the journal remains the authoritative
crash recovery source.

Archive object names for journal exports are defined by
`TradingEventArchiveLayout`. The layout is deterministic and GCS-friendly:

```text
{prefix}/trading-events/v1/event_type={event-type}/topic={topic}/date={yyyy-MM-dd}/hour={HH}/{journal-index}.avrobin
```

The layout is a contract for later upload/archive code; hot-path trading should
append to the local journal and publish events without waiting on GCS or
database writes.

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
