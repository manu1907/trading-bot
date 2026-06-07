# Demo USD-M Futures User Manual

This manual describes how to run and use the bot in its current state for the
active demo USD-M futures target:

```text
binance/demo/main/usdm_futures
```

It is intentionally conservative. The bot can start in live mode, load and
validate the active Binance demo configuration, run Binance demo smoke tests,
consume configured runtime streams when enabled, maintain projections from
published events, and expose internal intervention state when the operator API
is enabled. It is not yet a finished end-user auto-trading product.

## Current Readiness

The bot is currently a trading-system foundation, not a turnkey trading UI.

What is usable now:

- Start the Spring Boot application in live mode.
- Load the active demo USD-M futures config from `config/catalog.json`,
  `config/application-demo.json`, `config/active.json`, and optional runtime
  overrides.
- Validate Binance provider config and USD-M futures trading capability config.
- Run read-only and guarded Binance demo smoke tests.
- Use the Binance provider clients for server time, exchange metadata, order
  validation, signed order lifecycle tests, listen-key lifecycle tests, and
  WebSocket smoke tests.
- Enable private user-data stream runtime, public market-data stream runtime,
  and reconciliation runtime through config switches.
- Project normalized trading events into order, position, balance,
  reconciliation, manual-review, and remediation state.
- Gate order commands through idempotency, reconciliation confidence, target
  order checks, order limit checks, unknown-status checks, pending-command
  checks, and manual-intervention checks when the execution pipeline is enabled.
- Expose internal intervention and remediation endpoints when the operator API
  is explicitly enabled.

What is not ready yet:

- There is no finished user-facing CLI, web UI, or dashboard for placing trades.
- The strategy signal planner is disabled by default.
- The order execution pipeline is disabled by default.
- Runtime market-data, user-data, and reconciliation loops are disabled by
  default.
- The LFA strategy module is present as a strategy module boundary, but this
  manual does not treat it as a complete production trading strategy.
- The bot should not be considered production-ready for real-money trading.
- Real Binance trading is not covered by this manual.

## Safety Model

By default, starting the app should not place exchange orders.

The default catalog disables the parts that would make the system trade or
attach live streams automatically:

- `trading.execution.pipeline.enabled`: `false`
- `trading.execution.signal_planner.enabled`: `false`
- `user_data.runtime_enabled`: `false`
- `market_data.runtime_enabled`: `false`
- `reconciliation.runtime_enabled`: `false`
- `trading.intervention.operator_api.enabled`: `false`
- `trading.intervention.remediation_orchestrator.enabled`: `false`

The Binance live order smoke test is the exception: it is an opt-in Gradle task
that deliberately submits a passive demo USD-M futures order, queries it, and
cancels it.

## Prerequisites

Required locally:

- Java 25, or a JDK toolchain that Gradle can resolve for Java 25.
- The Gradle wrapper included in the repository.
- Binance demo futures API credentials for signed smoke tests and signed
  runtime clients.
- A shell environment that can export environment variables.

Recommended before any demo trading test:

- Use Binance demo credentials only.
- Confirm `config/active.json` still points at `binance/demo/main/usdm_futures`.
- Keep `api.env` local and uncommitted if you use it for credentials.
- Run the read-only server-time smoke test before the order smoke test.

## Active Target

The current repository target is selected by `config/active.json`:

```json
{
  "active": {
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures"
  },
  "runtime": {}
}
```

The target means:

- Provider: `binance`
- Environment: `demo`
- Account: `main`
- Market: `usdm_futures`
- Product family: Binance USD-M Futures

Spring profile and exchange environment are separate concepts:

- Spring profile `live` means the app is running as a live runtime.
- Exchange environment `demo` means the active exchange endpoints are demo or
  test endpoints.

The default Spring profile in `bot-core/src/main/resources/application.yaml` is
`live`.

## Demo Endpoints

For the active USD-M futures demo target, `config/application-demo.json`
overrides the catalog endpoints with:

```text
REST:      https://demo-fapi.binance.com
WebSocket: wss://fstream.binancefuture.com
```

The catalog still owns the product paths, request defaults, retry policy,
WebSocket path defaults, listen-key paths, reconciliation options, account
options, and trading capabilities.

## Credentials

The demo account credentials are read from environment-variable references:

```text
BINANCE_DEMO_API_KEY
BINANCE_DEMO_API_SECRET
```

The demo config also accepts generic fallbacks:

```text
EXCHANGE_API_KEY
EXCHANGE_API_SECRET
```

Use direct environment variables:

```bash
export BINANCE_DEMO_API_KEY="<demo-api-key-from-binance>"
export BINANCE_DEMO_API_SECRET="<demo-api-secret-from-binance>"
```

Or create a local ignored `api.env` file at the repository root for the smoke
test tasks:

```text
BINANCE_DEMO_API_KEY=<demo-api-key-from-binance>
BINANCE_DEMO_API_SECRET=<demo-api-secret-from-binance>
```

Never commit `api.env`.

## Build And Quality Check

Run the full project check:

```bash
./gradlew check
```

Run Spotless formatting only:

```bash
./gradlew spotlessApply
```

Run Spotless verification only:

```bash
./gradlew spotlessCheck
```

## Start The Bot

Start the Spring Boot application:

```bash
./gradlew :bot-core:bootRun
```

With credentials in the same shell:

```bash
BINANCE_DEMO_API_KEY="<demo-api-key-from-binance>" \
BINANCE_DEMO_API_SECRET="<demo-api-secret-from-binance>" \
./gradlew :bot-core:bootRun
```

Because the default Spring profile is `live`, an explicit profile is normally
not required. If you want to be explicit:

```bash
./gradlew :bot-core:bootRun --args='--spring.profiles.active=live'
```

Expected behavior in the default config:

- The application starts as a live runtime.
- The active target resolves to `binance/demo/main/usdm_futures`.
- The Binance demo config is loaded and validated.
- No strategy signal planning starts by default.
- No order execution pipeline starts by default.
- No market-data stream runtime starts by default.
- No private user-data stream runtime starts by default.
- No reconciliation runtime starts by default.
- No internal operator API starts by default.

## Binance Demo Smoke Tests

The repository includes opt-in live smoke tests for the configured Binance
active target.

Run the credential-free server-time smoke test first:

```bash
./gradlew :bot-exchange-binance:binanceLiveServerTimeSmokeTest
```

Run the guarded demo order lifecycle smoke test:

```bash
./gradlew :bot-exchange-binance:binanceLiveOrderSmokeTest
```

In demo mode, this task creates a passive USD-M `LIMIT GTX` order on `BTCUSDT`,
queries it by client order id, and cancels it. It is still an exchange order,
so use demo credentials only unless you are intentionally testing another
configured target.

Run the listen-key lifecycle smoke test:

```bash
./gradlew :bot-exchange-binance:binanceLiveUserDataStreamSmokeTest
```

This starts, renews, and closes the configured listen-key stream for the active
target.

Run the WebSocket smoke test:

```bash
./gradlew :bot-exchange-binance:binanceLiveWebSocketSmokeTest
```

This verifies that the configured WebSocket endpoint can be planned and opened
through the provider WebSocket transport.

## What A Demo USD-M Futures User Can Do Now

### Start In Safe Demo Mode

You can start the app with the active demo target and no trading runtime enabled.
This is the safest way to validate config loading, application wiring, and
provider startup boundaries.

Command:

```bash
./gradlew :bot-core:bootRun
```

Supported outcome:

- Config is loaded from catalog, demo environment overrides, active target, and
  runtime override file if present.
- Runtime target stays immutable for the process lifetime.
- Binance provider config validation runs.
- The app does not submit orders by default.

### Validate Binance Connectivity

You can validate Binance demo REST connectivity without credentials by running
the server-time smoke test.

Command:

```bash
./gradlew :bot-exchange-binance:binanceLiveServerTimeSmokeTest
```

Supported outcome:

- Calls the active server-time endpoint.
- Computes local/server time offset.
- Confirms the configured REST base URL is reachable.

### Validate Signed Demo Credentials

You can validate signed request wiring with the order smoke test and user-data
stream smoke test.

Commands:

```bash
./gradlew :bot-exchange-binance:binanceLiveOrderSmokeTest
./gradlew :bot-exchange-binance:binanceLiveUserDataStreamSmokeTest
```

Supported outcome:

- Confirms the API key and secret can sign active USD-M requests.
- Confirms listen-key lifecycle requests work for the active target.
- Confirms order create, query, and cancel client code works against demo.

### Validate WebSocket Connectivity

You can validate live WebSocket transport setup with:

```bash
./gradlew :bot-exchange-binance:binanceLiveWebSocketSmokeTest
```

Supported outcome:

- Builds the WebSocket endpoint from active config.
- Opens the configured WebSocket connection through Reactor Netty.
- Exercises the provider WebSocket transport boundary.

### Enable Private User-Data Stream Runtime

The private user-data stream runtime is disabled by default. When enabled, it
starts the Binance listen-key lifecycle, opens the private WebSocket, renews the
listen key, maps provider user-data events into core event envelopes, and
publishes those events through the normal event bus.

Runtime behavior:

- Requires `user_data.runtime_enabled=true`.
- Requires a `TradingEventBus`; private events are not consumed if they cannot
  be published.
- Uses `/fapi/v1/listenKey` for USD-M futures start, keepalive, and close.
- Uses a 60-minute validity window and a 30-minute renewal interval.
- Maps USD-M order-trade updates, account updates, balances, and positions into
  core events.

Example runtime override shape:

```json
{
  "exchange": {
    "providers": {
      "binance": {
        "environments": {
          "demo": {
            "accounts": {
              "main": {
                "markets": {
                  "usdm_futures": {
                    "user_data": {
                      "runtime_enabled": true
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

### Enable Public Market-Data Stream Runtime

The market-data stream runtime is disabled by default. When enabled, it opens
configured public streams and maps Binance market-data payloads into core market
data event envelopes.

Runtime behavior:

- Requires `market_data.runtime_enabled=true`.
- Requires at least one configured stream.
- Requires a `TradingEventBus`; market data is not consumed if it cannot be
  published.
- Supports raw or combined stream endpoint planning from config.
- Shares WebSocket rollover and reconnect behavior with the private stream
  runtime.
- Maps trade, aggregate trade, book ticker, depth snapshot, depth delta, mark
  price, and kline payloads.

Example runtime override shape:

```json
{
  "exchange": {
    "providers": {
      "binance": {
        "environments": {
          "demo": {
            "accounts": {
              "main": {
                "markets": {
                  "usdm_futures": {
                    "market_data": {
                      "runtime_enabled": true,
                      "connection_mode": "combined",
                      "route": "default",
                      "streams": [
                        "btcusdt@trade",
                        "btcusdt@bookTicker"
                      ]
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

### Enable Reconciliation Runtime

The reconciliation runtime is disabled by default. When enabled, it collects
configured REST snapshots and publishes reconciliation observations that can be
compared against projected stream and journal state.

Runtime behavior:

- Requires `reconciliation.runtime_enabled=true`.
- Uses an interval configured in seconds.
- Can publish open orders, order history, account trades, futures balances,
  futures account snapshots, and futures positions if each option is enabled.
- Can compare projections when `projection_comparison_enabled=true`.
- Does not fail startup on projection mismatch by default because
  `fail_on_projection_mismatch=false`.

Example runtime override shape:

```json
{
  "exchange": {
    "providers": {
      "binance": {
        "environments": {
          "demo": {
            "accounts": {
              "main": {
                "markets": {
                  "usdm_futures": {
                    "reconciliation": {
                      "runtime_enabled": true,
                      "interval_seconds": 60,
                      "open_orders_enabled": true,
                      "open_order_symbols": ["BTCUSDT"],
                      "futures_balances_enabled": true,
                      "futures_account_enabled": true,
                      "futures_positions_enabled": true
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

### Inspect Intervention State Through The Operator API

The operator API is disabled by default. If enabled, it exposes internal
intervention and remediation state under `/internal/interventions`.

Config requirements:

- `trading.intervention.operator_api.enabled=true`
- `trading.intervention.operator_api.operator_token` must be set to a nonblank
  value.

Example config shape:

```json
{
  "trading": {
    "intervention": {
      "operator_api": {
        "enabled": true,
        "operator_token": "<operator-token>"
      }
    }
  }
}
```

All requests require the token header:

```text
X-Operator-Token: <operator-token>
```

List external order interventions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/orders?provider=binance&environment=demo&account=main&market=usdm_futures'
```

List external position interventions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/positions?provider=binance&environment=demo&account=main&market=usdm_futures'
```

List manual-review decisions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/manual-reviews?provider=binance&environment=demo&account=main&market=usdm_futures'
```

List remediation recommendations:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation?provider=binance&environment=demo&account=main&market=usdm_futures'
```

List remediation decisions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/decisions?provider=binance&environment=demo&account=main&market=usdm_futures'
```

List pause governance:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/pauses?provider=binance&environment=demo&account=main&market=usdm_futures'
```

Pause responses include account-level and symbol-level pause state projected
from `PAUSE_ACCOUNT` and `PAUSE_SYMBOL` remediation decisions. This state is
durable and auditable. The strategy signal planner suppresses new planned order
commands for paused targets, and the order risk gate rejects non-cancel
commands for paused accounts or symbols. Cancel commands can still pass the
risk gate when their target-order checks pass, because cancelling can reduce
existing risk. Pause responses also include expiry state: `expiresAt`,
`expired`, and `effectiveActive`.

To create a time-bounded pause decision, include a `pause_expires_at` attribute
on the remediation decision:

```json
{
  "attributes": {
    "pause_expires_at": "2026-06-07T14:00:00Z"
  }
}
```

The timestamp must be an ISO-8601 instant. Invalid or missing expiry values do
not auto-release a pause; this is intentional so malformed expiry data does not
accidentally resume trading.

Pause override policy:

- `trading.execution.risk_gate.pause_governance.override_enabled` defaults to
  `false`.
- Overrides apply only in the order risk gate for explicitly constructed order
  commands.
- Strategy signal planning still suppresses paused targets; strategy code
  cannot self-authorize around pause governance.
- When override is enabled, a command must carry `pause_override=true`,
  `pause_override_by`, `pause_override_reason`, and
  `pause_override_expires_at`.
- `pause_override_expires_at` must be in the future and within
  `max_override_seconds`, which defaults to `900`.

Pause governance audit logging:

- Successful pause releases emit structured `pause_governance_released` audit
  records through `io.github.manu.audit.AuditLogger`.
- Explicit pause override attempts emit structured `pause_override_evaluated`
  audit records after the risk decision is built.
- Override audit records include command identity, decision identity, final risk
  decision, override actor, reason, expiry, and invalid reason when the override
  request is rejected by policy.
- Projected active pauses that cross a valid `pause_expires_at` are observed by
  the pause expiry monitor and emit structured `pause_governance_expired`
  records once per projected pause expiry.

Pause governance metrics:

- Actuator and the Prometheus registry are on the bot-core runtime classpath.
- If the application exposes management endpoints, Prometheus metrics are
  available at `/actuator/prometheus`.
- Pause release publication outcomes increment
  `trading.pause_governance.release.events`.
- Explicit pause override evaluations increment
  `trading.pause_governance.override.events`.
- Effective active account and symbol pauses are exposed through
  `trading.pause_governance.active.states` with a `scope` tag.
- Live pause activation decisions increment
  `trading.pause_governance.activation.events`.
- Pause activation decisions with a valid `pause_expires_at` increment
  `trading.pause_governance.expiry.configured.events`.
- Observed expiry transitions increment
  `trading.pause_governance.expiry.transitions`.
- Release metric tags include `provider`, `environment`, `account`, `market`,
  `scope`, and `outcome`.
- Override metric tags include `provider`, `environment`, `account`, `market`,
  `symbol`, `decision`, `outcome`, and `invalid_reason`.
- Expiry-transition metric tags include `provider`, `environment`, `account`,
  `market`, and `scope`.
- Activation and expiry-configured counters are live-only metrics handlers, so
  journal replay and snapshot restore do not increment them.
- The pause expiry monitor is enabled by default. Set
  `trading.observability.pause-governance.expiry-monitor.enabled=false` to
  disable it, or set
  `trading.observability.pause-governance.expiry-monitor.interval-millis` to
  change the scan interval from the default `30000`.
- Recent pause release, override, and expiry audit records can be queried
  through the operator API.
- Dashboards, external alert routing, and durable/searchable audit storage are
  still planned work.

List recent pause governance audit records:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/pauses/audit-events?provider=binance&environment=demo&account=main&market=usdm_futures&limit=100'
```

Release active pause governance:

```bash
curl -X POST \
  -H 'X-Operator-Token: <operator-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures",
    "pauseScope": "SYMBOL",
    "pauseTarget": "BTCUSDT",
    "releasedBy": "operator",
    "releaseReason": "risk cleared"
  }' \
  'http://localhost:8080/internal/interventions/pauses/releases'
```

The release endpoint requires a matching active pause. It publishes a
`REMEDIATION_DECISION` release event, and projection replay restores the pause
as inactive.

List remediation command plans for persisted remediation decisions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/plans?provider=binance&environment=demo&account=main&market=usdm_futures'
```

Plan responses include `status`, `operation`, `exchangeExecutable`, `reasons`,
and target attributes. Order `CLOSE` for a projected external order can now be
planned as `operation=CANCEL_ORDER` with `exchangeExecutable=true`; all
position, pause, adopt, amend, ignore, and replan plans remain non-executable
until their specific policies exist.

Dry-run the remediation executor reports for persisted remediation decisions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/executor/dry-run?provider=binance&environment=demo&account=main&market=usdm_futures'
```

Dry-run responses include `enabled`, `exchangeExecutionEnabled`, `dryRunOnly`,
batch counts, and per-plan reports with `planStatus`, `operation`, `status`,
`reasons`, `attributes`, and the original `plan`. The endpoint does not submit
exchange commands. It explains whether each plan is blocked, no-action, or
would remain report-only under the current executor policy.

Execute the remediation executor batch:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/executor/execute' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures"
  }'
```

This endpoint is still policy-gated. It submits only eligible `CANCEL_ORDER`
plans for external order `CLOSE` decisions, and only when
`remediation_executor_policy.enabled=true`,
`exchange_execution_enabled=true`, `dry_run_only=false`, `CANCEL_ORDER` is in
`allowed_operations`, the target identity is present, and the normal order
execution pipeline is enabled. It returns `SUBMITTED_TO_PIPELINE` after the
command has been accepted by the pipeline; the final risk decision and order
result are recorded through the normal event path.

Acknowledge an order intervention:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/orders/acknowledgements' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures",
    "symbol": "BTCUSDT",
    "clientOrderId": "client-order-id",
    "interventionReason": "EXTERNAL_ORDER",
    "acknowledgedBy": "operator",
    "acknowledgementReason": "Reviewed external order",
    "attributes": {}
  }'
```

Acknowledge a position intervention:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/positions/acknowledgements' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures",
    "symbol": "BTCUSDT",
    "positionSide": "BOTH",
    "interventionReason": "EXTERNAL_POSITION",
    "acknowledgedBy": "operator",
    "acknowledgementReason": "Reviewed external position",
    "attributes": {}
  }'
```

Submit a remediation decision:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/decisions' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures",
    "symbol": "BTCUSDT",
    "scope": "ORDER",
    "action": "MANUAL_REVIEW",
    "clientOrderId": "client-order-id",
    "positionSide": null,
    "decidedBy": "operator",
    "decisionReason": "Keep blocked until reviewed",
    "attributes": {}
  }'
```

Trigger automated remediation decisions for current recommendations:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/automated-decisions' \
  -d '{
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures"
  }'
```

This endpoint returns a batch result with `enabled`, `publishedCount`,
`skippedCount`, and per-recommendation outcomes. It only publishes remediation
decision events when `automated_decision_service.enabled=true`; it does not send
exchange commands.

Successful POST endpoints return HTTP `202 Accepted` after publishing the
corresponding event or accepting the automated batch trigger.

## Configuration Sources

Live-mode config is merged in this order:

```text
runtime override > environment config > default catalog config
```

Files:

- `config/catalog.json`: base schema-bearing provider, account, market,
  transport, timing, safety, execution, intervention, and reconciliation config.
- `config/application-demo.json`: demo endpoint and credential-reference
  overrides.
- `config/application-real.json`: real endpoint and credential-reference
  overrides.
- `config/active.json`: selected provider, environment, account, and market.
- `config/runtime/live/{provider}/{environment}/{account}/{market}.json`:
  optional runtime overrides for the selected live target.
- `config/application-backtest.json`: backtest-mode config.

Startup rejects unsupported schema ids, schema versions, config versions, and
migration policies. Unknown override paths are rejected.

## Active Target Options

The active target selects one runtime venue path:

```json
{
  "active": {
    "provider": "binance",
    "environment": "demo",
    "account": "main",
    "market": "usdm_futures"
  },
  "runtime": {}
}
```

Fields:

- `provider`: exchange provider key, currently `binance` for this manual.
- `environment`: `demo` or `real`; this manual covers `demo` only.
- `account`: configured account key, currently `main`.
- `market`: configured market key, currently `usdm_futures`.

Changing the active target affects the next process start. Runtime target
changes are immutable for a running process.

## USD-M Futures REST Configuration Options

The active USD-M futures REST config includes:

- `base_url`: demo override is `https://demo-fapi.binance.com`.
- `exchange_info_path`: `/fapi/v1/exchangeInfo`
- `server_time_path`: `/fapi/v1/time`
- `api_key_header`: `X-MBX-APIKEY`
- `signature_algorithm`: `HMAC_SHA256`
- `timestamp_unit`: `MILLISECONDS`
- `recv_window_millis`: `5000`
- `connect_timeout_millis`: `2000`
- `response_timeout_millis`: `5000`
- `max_retries`: `3`
- `retry_backoff_millis`: `200`
- `retry_status_codes`: `408`, `429`, `500`, `502`, `503`, `504`
- `weight_header_prefixes`: `X-MBX-USED-WEIGHT`
- `order_count_header_prefixes`: `X-MBX-ORDER-COUNT`
- `order_response_type_default`: `RESULT`
- `unknown_execution_status_retryable_message`: Binance unknown-status message
- `reconcile_before_retry_status_codes`: `503`

The provider parses Binance rate-limit headers after REST responses and keeps
the latest observed request weight, order count, and retry-after values for
later risk and observability wiring.

## USD-M Futures WebSocket Configuration Options

The active USD-M futures WebSocket config includes:

- `base_url`: demo override is `wss://fstream.binancefuture.com`.
- `raw_stream_path`: `/ws`
- `combined_stream_path`: `/stream`
- `public_path_prefix`: `/public`
- `market_path_prefix`: `/market`
- `private_path_prefix`: `/private`
- `max_connection_lifetime_hours`: `24`
- `reconnect_before_expiry_minutes`: `10`
- `server_ping_interval_minutes`: `3`
- `pong_timeout_minutes`: `10`
- `max_incoming_messages_per_second`: `10`
- `max_streams_per_connection`: `1024`
- `timestamp_unit`: `MILLISECONDS`

The WebSocket supervisor rolls connections before the configured lifetime
expires and schedules reconnects after active connection errors or unexpected
closes.

## USD-M Futures User-Data Options

The active user-data config includes:

- `mode`: `listen_key`
- `runtime_enabled`: `false` by default
- `start_path`: `/fapi/v1/listenKey`
- `keepalive_path`: `/fapi/v1/listenKey`
- `close_path`: `/fapi/v1/listenKey`
- `validity_minutes`: `60`
- `renewal_interval_minutes`: `30`
- `request_weight`: `1`

The listen-key lifecycle uses API-key authentication and does not use the API
secret for REST listen-key lifecycle calls.

## USD-M Futures Market-Data Options

The active market-data config includes:

- `runtime_enabled`: `false` by default
- `connection_mode`: `combined`
- `route`: `default`
- `streams`: empty by default

You must configure streams before enabling the runtime.

Common Binance futures stream names include examples such as:

- `btcusdt@trade`
- `btcusdt@aggTrade`
- `btcusdt@bookTicker`
- `btcusdt@markPrice`
- `btcusdt@kline_1m`

## USD-M Futures Reconciliation Options

The active reconciliation config includes:

- `runtime_enabled`: `false` by default
- `interval_seconds`: `60`
- `dedupe_window`: `10000`
- `projection_comparison_enabled`: `true`
- `fail_on_projection_mismatch`: `false`
- `open_orders_enabled`: `false`
- `open_order_symbols`: empty by default
- `order_history_enabled`: `false`
- `order_history_symbols`: empty by default
- `order_history_limit`: `1000`
- `account_trades_enabled`: `false`
- `account_trade_symbols`: empty by default
- `account_trades_limit`: `1000`
- `futures_balances_enabled`: `false`
- `futures_account_enabled`: `false`
- `futures_positions_enabled`: `false`

Enable only the snapshot families you need. For demo USD-M, a safe first
reconciliation target is one symbol such as `BTCUSDT` plus balances and
positions.

## USD-M Futures Account Options

The active futures account config includes:

- `position_mode`: `ONE_WAY`
- `supported_position_modes`: `ONE_WAY`, `HEDGE`
- `supported_margin_types`: `CROSSED`, `ISOLATED`
- `min_leverage`: `1`
- `max_leverage`: `125`
- `multi_assets_mode_expected`: `false`
- `portfolio_margin_expected`: `false`

Configured account paths include:

- Position mode: `/fapi/v1/positionSide/dual`
- Margin type: `/fapi/v1/marginType`
- Leverage: `/fapi/v1/leverage`
- Balance: `/fapi/v3/balance`
- Account info: `/fapi/v3/account`
- Position risk: `/fapi/v3/positionRisk`
- ADL quantile: `/fapi/v1/adlQuantile`
- Force orders: `/fapi/v1/forceOrders`
- Income: `/fapi/v1/income`
- Funding rate: `/fapi/v1/fundingRate`
- Multi-assets mode: `/fapi/v1/multiAssetsMargin`

## USD-M Futures Trading Capability Options

The active USD-M futures trading capability config declares what Binance order
features the provider accepts before signed submission.

Supported sides:

- `BUY`
- `SELL`

Supported order types:

- `LIMIT`
- `MARKET`
- `STOP`
- `STOP_MARKET`
- `TAKE_PROFIT`
- `TAKE_PROFIT_MARKET`
- `TRAILING_STOP_MARKET`

Supported time-in-force values:

- `GTC`
- `IOC`
- `FOK`
- `GTX`
- `GTD`

Supported response types:

- `ACK`
- `RESULT`

Supported self-trade-prevention modes:

- `EXPIRE_TAKER`
- `EXPIRE_MAKER`
- `EXPIRE_BOTH`

`NONE` is intentionally excluded for configured USD-M futures requests because
the demo API rejects that mode even though order responses can still report it.

Supported position sides:

- `BOTH`
- `LONG`
- `SHORT`

Feature flags:

- `supports_quote_order_qty`: `false`
- `supports_reduce_only`: `true`
- `supports_close_position`: `true`
- `supports_price_match`: `true`
- `supports_working_type`: `true`
- `supports_price_protect`: `true`
- `supports_pegged_orders`: `false`
- `supports_iceberg_qty`: `false`
- `supports_trailing_delta`: `false`
- `supports_isolated_margin_flag`: `false`
- `supports_market_maker_protection`: `false`
- `supports_post_only`: `false`
- `enforce_exchange_filters`: `true`
- `enforce_percent_price_filters`: `false`

Provider validation rejects known invalid combinations before submission,
including:

- `priceMatch` with `price`
- `GTD` without `goodTillDate`
- `goodTillDate` without `GTD`
- `reduceOnly` on hedge-mode `LONG` or `SHORT` orders
- close-position flags on non-close-all order types

## Execution Pipeline Options

The execution pipeline is disabled by default:

```json
{
  "trading": {
    "execution": {
      "pipeline": {
        "enabled": false
      }
    }
  }
}
```

When enabled, the pipeline handles `ORDER_COMMAND` events in live mode. It is
not a user-facing order-entry CLI. Commands must arrive through the configured
event bus and pass risk validation before exchange execution is allowed.

Default execution safety config includes:

- Idempotency is enabled.
- Maximum tracked idempotency keys: `100000`.
- Projected duplicate commands are rejected.
- Risk gate is enabled.
- Reconciliation is required before accepting new commands.
- No reconciliation observations can reject new commands.
- Degraded reconciliation can reject new commands.
- Unknown order status can reject new commands and request manual review.
- Pending order commands can reject new commands and request manual review.
- External order interventions can reject new commands.
- External position interventions can reject new commands.
- Target-order validation requires a target order id.
- Target-order validation requires a projected target.
- Target-order validation requires the target to be bot-managed.
- Closed targets are rejected.
- External target interventions trigger manual review.

## Signal Planner Options

The signal planner is disabled by default:

```json
{
  "trading": {
    "execution": {
      "signal_planner": {
        "enabled": false
      }
    }
  }
}
```

Current planner defaults include:

- `provider`: `null`
- `environment`: `null`
- `account`: `null`
- `market`: `null`
- `symbol`: `null`
- `limit_order_time_in_force`: `GTC`
- `client_order_id_prefix`: `tb`
- `feature_profiles`: empty list

When enabled, the planner handles `STRATEGY_SIGNAL` events in live mode. It is
not enabled by default because automated strategy-to-order planning still needs
operator-controlled rollout.

## Risk Gate Options

The risk gate is enabled by default inside execution config, but it only gates
commands when command handling is active.

Important defaults:

- Reconciliation gate enabled.
- Reject if there are no reconciliation observations.
- Reject if reconciliation is degraded.
- Reject external order interventions.
- Reject external position interventions.
- Reject unknown order status.
- Reject pending order command.
- Reject invalid numeric fields.
- Reject unbounded notional.
- No global `max_quantity` is configured by default.
- No global `max_notional` is configured by default.
- Target-specific limits are empty by default.

This means enabling execution without also configuring appropriate risk limits
is intentionally restrictive.

## Intervention And Remediation Options

Default intervention config:

- `operator_api.enabled`: `false`
- `operator_api.operator_token`: `null`
- `remediation_orchestrator.enabled`: `false`
- `operator_review_acknowledgement_enabled`: `true`
- `max_tracked_decision_ids`: `100000`
- `automated_policy.external_order_action`: `OPERATOR_REVIEW`
- `automated_policy.managed_order_change_action`: `REPLAN_FROM_PROJECTION`
- `automated_policy.flat_position_action`: `REPLAN_FROM_PROJECTION`
- `automated_policy.open_position_action`: `HEDGE_OR_REPLAN`
- `automated_policy.unknown_position_action`: `OPERATOR_REVIEW`
- `automated_decision_service.enabled`: `false`
- `automated_decision_service.include_operator_review_actions`: `false`
- `automated_decision_service.max_decisions_per_run`: `100`
- `automated_decision_service.decided_by`: `automated_remediation_policy`
- `automated_decision_service.decision_reason`: `automated policy selected
  remediation action`
- `remediation_executor_policy.enabled`: `false`
- `remediation_executor_policy.exchange_execution_enabled`: `false`
- `remediation_executor_policy.dry_run_only`: `true`
- `remediation_executor_policy.allow_real_environment`: `false`
- `remediation_executor_policy.require_ready_plan`: `true`
- `remediation_executor_policy.require_fresh_projection_match`: `true`
- `remediation_executor_policy.require_projection_target_identity`: `true`
- `remediation_executor_policy.require_managed_execution_pipeline`: `true`
- `remediation_executor_policy.reject_stale_projection`: `true`
- `remediation_executor_policy.reject_unsupported_plans`: `true`
- `remediation_executor_policy.reject_operator_review_plans`: `true`
- `remediation_executor_policy.reject_insufficient_data_plans`: `true`
- `remediation_executor_policy.max_plans_per_run`: `25`
- `remediation_executor_policy.allowed_operations`: empty list

The system can track and expose:

- External order interventions.
- External position interventions.
- Manual review decisions.
- Remediation recommendations.
- Remediation decisions.
- Automated remediation decisions.
- Operator acknowledgements.

The remediation orchestrator is disabled by default, so remediation decisions
remain operator-controlled unless explicitly enabled.

The automated policy block controls remediation recommendations, not direct
exchange-side action execution yet. Supported recommendation actions are
`OPERATOR_REVIEW`, `REPLAN_FROM_PROJECTION`, `HEDGE_OR_REPLAN`, `ADOPT`,
`AMEND`, `REDUCE`, `CLOSE`, `HEDGE`, `PAUSE_SYMBOL`, `PAUSE_ACCOUNT`, and
`IGNORE`. Demo and real can use the same codebase with different override
values, but real mode should not enable aggressive actions until hard limits,
reconciliation, journaling, and projection persistence are configured.

The automated decision service can publish `REMEDIATION_DECISION` events from
current recommendations when explicitly enabled. By default it skips
`OPERATOR_REVIEW`, deduplicates by `recommendation_event_id`, and limits each
run with `max_decisions_per_run`. It still does not send exchange commands; it
only records the selected automated decision so the command planner and future
executor can evaluate it.

Current automated remediation execution state:

- The bot can convert remediation recommendations into remediation decisions
  when `automated_decision_service.enabled=true`.
- The bot can convert remediation decisions into internal command plans.
- The planner revalidates the current projection before planning an action.
- Stale orders or positions are refused as `STALE_PROJECTION`.
- Invalid or missing position amount data is refused as `INSUFFICIENT_DATA`.
- Flat position close/reduce/hedge requests become `NO_ACTION`.
- `PAUSE_SYMBOL` and `PAUSE_ACCOUNT` decisions create durable pause governance
  state that can be listed through the operator API.
- Pause governance suppresses strategy-planned order commands and rejects
  non-cancel order commands at the risk gate for paused accounts or symbols.
- Pause governance can expire through a `pause_expires_at` decision attribute;
  expired pauses remain visible but are no longer effective admission blocks.
- Pause governance override exists as a disabled-by-default order risk-gate
  policy requiring explicit actor, reason, and bounded expiry command
  attributes.
- Active pause governance can be released through the operator API; release is
  recorded as a remediation decision event and projected as inactive pause
  state.
- Successful pause releases and explicit pause override attempts are written as
  structured audit log records.
- Pause release publication outcomes and explicit override evaluations are
  counted with Micrometer metrics for Prometheus scraping.
- Effective active pause counts are exposed as low-cardinality Micrometer
  gauges by pause scope.
- Live pause activation decisions and pause activation decisions with a valid
  expiry are counted through live-only Micrometer handlers.
- Projected active pauses crossing `pause_expires_at` are automatically observed
  by the pause expiry monitor, which emits one expiry audit record and one
  expiry-transition metric without mutating projection state or touching the
  exchange.
- Recent pause release, explicit pause override, and pause expiry audit records
  can be queried through the operator API.
- Order `CLOSE` becomes an exchange-executable `CANCEL_ORDER` plan with the
  projected target order identity.
- Position `CLOSE`, `REDUCE`, `HEDGE`, and `HEDGE_OR_REPLAN` become
  non-executable position intents with the projected absolute amount and an
  execution blocker explaining that bounded sizing policy is still missing.
- `PAUSE_SYMBOL`, `PAUSE_ACCOUNT`, `ADOPT`, `IGNORE`, and
  `REPLAN_FROM_PROJECTION` are governance or planning intents, not exchange
  commands yet.

As of this version, only external order `CLOSE` remediation can become
`exchangeExecutable=true`, and it can only submit a cancel through the existing
order execution pipeline. The codebase still does not directly amend, reduce,
close, or hedge positions from remediation plans.

The remediation executor policy is the configuration boundary for the future
executor. It is disabled by default and cannot allow exchange execution unless
the policy is enabled, `dry_run_only=false`, at least one operation is explicitly
allowlisted, and the strict ready-plan, fresh-projection, target-identity, and
managed-pipeline gates remain enabled. `allow_real_environment=false` means a
future executor must still refuse real-environment exchange execution unless a
real deployment deliberately overrides that guard.

The current codebase includes a remediation executor service. It consumes
persisted remediation decisions, regenerates current command plans, applies the
executor policy gates, caps each batch with `max_plans_per_run`, and returns
per-plan reports with statuses such as blocked, dry-run, submitted, or
no-action. Dry-run reports are exposed through
`GET /internal/interventions/remediation/executor/dry-run`. Policy-gated
execution is exposed through
`POST /internal/interventions/remediation/executor/execute` and currently
supports only external-order close as cancel.

## Event And Projection Capabilities

The core event model supports normalized envelopes for:

- Strategy signals.
- Order commands.
- Execution events.
- Balance events.
- Position events.
- Market-data events.
- Reconciliation observations.
- External order intervention acknowledgements.
- External position intervention acknowledgements.
- Manual review decisions.
- Remediation decisions.

The projection layer can maintain:

- Order state.
- Position state.
- Balance state.
- Reconciliation state.
- External order intervention state.
- External position intervention state.
- Manual-review decision state.
- Remediation decision state.

These capabilities are useful for demos and operator workflows only when events
are being published into the system.

## Supported Scenarios

### Scenario: Start Without Trading

Use this when you want to verify startup and config only.

Command:

```bash
./gradlew :bot-core:bootRun
```

Expected result:

- App starts in live profile.
- Active target resolves to demo USD-M futures.
- No order is placed.

### Scenario: Verify Demo REST Endpoint

Use this before any signed test.

Command:

```bash
./gradlew :bot-exchange-binance:binanceLiveServerTimeSmokeTest
```

Expected result:

- Server-time endpoint responds.
- Time offset calculation succeeds.

### Scenario: Verify Demo API Credentials

Use this after setting `BINANCE_DEMO_API_KEY` and `BINANCE_DEMO_API_SECRET`.

Command:

```bash
./gradlew :bot-exchange-binance:binanceLiveUserDataStreamSmokeTest
```

Expected result:

- Listen key can be started, renewed, and closed.

### Scenario: Verify Demo Order Lifecycle

Use this only when you accept that a demo exchange order will be submitted.

Command:

```bash
./gradlew :bot-exchange-binance:binanceLiveOrderSmokeTest
```

Expected result:

- Passive `BTCUSDT` USD-M futures `LIMIT GTX` demo order is created.
- The order is queried by client order id.
- The order is canceled.

### Scenario: Consume Private Account Events

Use this when you want the bot to observe demo account updates.

Config changes:

- Enable `user_data.runtime_enabled`.
- Provide demo credentials.
- Ensure event bus dependencies are available.

Expected result:

- The bot opens a listen-key stream.
- Private account events are normalized and published.

### Scenario: Consume Public Market Data

Use this when you want the bot to observe configured public market streams.

Config changes:

- Enable `market_data.runtime_enabled`.
- Configure `market_data.streams`.
- Ensure event bus dependencies are available.

Expected result:

- The bot opens configured public streams.
- Market data is normalized and published.

### Scenario: Run REST Reconciliation

Use this when you want the bot to compare REST snapshots with projected state.

Config changes:

- Enable `reconciliation.runtime_enabled`.
- Enable specific snapshot families.
- Configure symbol lists for symbol-scoped snapshots.

Expected result:

- The bot publishes reconciliation observations.
- Risk gates can use reconciliation confidence when execution is enabled.

### Scenario: Review External Orders

Use this when externally-created exchange orders are projected and flagged for
operator review.

Config changes:

- Enable operator API if HTTP inspection is needed.
- Keep external order intervention rejection enabled for safety.

Expected result:

- External orders can be listed through `/internal/interventions/orders`.
- Operator acknowledgements can be submitted through the order acknowledgement
  endpoint.

### Scenario: Review External Positions

Use this when externally-created or unexpected positions are projected and
flagged for operator review.

Config changes:

- Enable operator API if HTTP inspection is needed.
- Keep external position intervention rejection enabled for safety.

Expected result:

- External positions can be listed through `/internal/interventions/positions`.
- Operator acknowledgements can be submitted through the position
  acknowledgement endpoint.

### Scenario: Block New Commands On Unknown Order Status

Use this when an order reaches an unknown or ambiguous exchange state.

Default behavior:

- Unknown order status is treated as a safety issue.
- New commands can be rejected.
- Manual review can be requested.

Expected result:

- The bot avoids compounding ambiguous exchange state with new commands.

### Scenario: Block New Commands On Degraded Reconciliation

Use this when reconciliation is stale, absent, or degraded.

Default behavior:

- Reconciliation is required by the risk gate.
- No observations can reject new commands.
- Degraded reconciliation can reject new commands.

Expected result:

- Execution is blocked until state confidence is restored.

## Suggested Safe Demo Workflow

Follow this order for a first demo USD-M futures run:

1. Confirm `config/active.json` points to `binance/demo/main/usdm_futures`.
2. Set `BINANCE_DEMO_API_KEY` and `BINANCE_DEMO_API_SECRET`, or create local
   `api.env`.
3. Run `./gradlew check`.
4. Run `./gradlew :bot-exchange-binance:binanceLiveServerTimeSmokeTest`.
5. Run `./gradlew :bot-exchange-binance:binanceLiveUserDataStreamSmokeTest`.
6. Run `./gradlew :bot-exchange-binance:binanceLiveWebSocketSmokeTest`.
7. Run `./gradlew :bot-exchange-binance:binanceLiveOrderSmokeTest` only if you
   accept a demo order create/query/cancel cycle.
8. Start the app with `./gradlew :bot-core:bootRun`.
9. Enable one runtime switch at a time, starting with market data or user data.
10. Keep execution and signal planning disabled until risk limits,
    reconciliation, event bus wiring, and operator workflows are intentionally
    configured.

## Troubleshooting

If startup fails with a profile error:

- Run with `--args='--spring.profiles.active=live'`.
- Confirm `bot-core/src/main/resources/application.yaml` has `live` as the
  default profile.

If signed smoke tests fail with credential errors:

- Confirm `BINANCE_DEMO_API_KEY` and `BINANCE_DEMO_API_SECRET` are exported in
  the same shell.
- Confirm local `api.env` uses the exact variable names.
- Confirm the active target is still `demo`, not `real`.

If market-data runtime fails to start:

- Confirm `market_data.runtime_enabled=true`.
- Confirm at least one stream is configured.
- Confirm a `TradingEventBus` is available.

If user-data runtime fails to start:

- Confirm `user_data.runtime_enabled=true`.
- Confirm demo credentials are available.
- Confirm a `TradingEventBus` is available.

If reconciliation produces no observations:

- Confirm `reconciliation.runtime_enabled=true`.
- Enable the specific snapshot families you need.
- Add symbol lists for open orders, order history, or account trades.

If operator API returns `401 Unauthorized`:

- Confirm the `X-Operator-Token` header exactly matches the configured token.
- Confirm `trading.intervention.operator_api.enabled=true`.

If an order command is rejected after enabling execution:

- Check reconciliation confidence first.
- Check for unknown order statuses.
- Check for pending commands on the same target.
- Check for external order or position interventions.
- Check target-order requirements.
- Check numeric fields and notional bounds.
- Check Binance provider validation for unsupported parameter combinations.

## Related Documentation

Read these files next:

- `docs/current-state-and-scenarios.md` for a broader implementation-state and
  scenario inventory.
- `docs/binance-demo-smoke.md` for the focused Binance smoke-test guide.
- `docs/architecture.md` for module boundaries, config precedence, and delivery
  standards.
