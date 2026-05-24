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
- `exchangeInfo` loading and parsing for symbol filters, order enums,
  precision, rate limits, trigger protection, and lifecycle metadata.
- Basic signed order create, query, open-orders, and cancel requests.
- Spot pegged-order command fields, validation, and signed request parameters.
- Margin `sideEffectType` and `autoRepayAtCancel` command fields, validation,
  and signed request parameters.
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
- Catalog-backed order feature enums and limits for price-match, futures
  trigger controls, pegged-order, and margin side-effect controls.
- Public WebSocket stream endpoint planning and reconnect/rollover policy.
- User-data listen-key or listen-token lifecycle for configured products.

The connector is not yet complete enough to be called a full Binance execution
adapter. Known gaps that must remain on the plan:

- Spot advanced trading endpoints: cancel-replace, amend-keep-priority, order
  lists (OCO, OTO, OTOCO, OPO, OPOCO), SOR orders, prevented matches,
  commission rates, and WebSocket API order placement.
- Margin borrow/repay, transfer, margin OCO/OTO/OTOCO, account/risk endpoints,
  prevented matches, and low-latency special-key workflows are not implemented.
- User-data and market-data WebSocket payloads are not yet mapped into the core
  Avro event model.
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
