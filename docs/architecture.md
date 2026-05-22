# Trading Bot Architecture

The bot is organized around one application core and pluggable runtime modules.

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
The Binance connector should track the Binance documentation as the source of
truth for product families and authentication choices: Spot, Cross Margin,
Isolated Margin, USD-M Futures, COIN-M Futures, Options, HMAC keys, RSA PKCS#8
keys, and Ed25519 keys. Strategy selection may prefer liquid instruments, but
the provider layer must not hard-code a narrow instrument universe.
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
The checked-in Binance demo order lifecycle test is opt-in. It requires
`BINANCE_DEMO_API_KEY`, `BINANCE_DEMO_API_SECRET`, and
`-Dbinance.demo.order.smoke=true`; it refuses non-demo active targets, submits a
passive USD-M limit order, queries it, and cancels it by client order id.

## Demo And Real

Demo and real use the same execution engine. The allowed differences are:

- active target
- credentials/secrets
- endpoint/environment config
- deployment namespace
- account limits

There should be no separate toy execution code path for real-readiness work.

## Quality Gate

Before a commit:

- Relevant unit and integration tests must pass.
- Warnings should be corrected, not hidden.
- Tests should use explicit inputs and expected outputs.
- Comments should explain non-obvious design choices only.
