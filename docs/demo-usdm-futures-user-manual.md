# Demo USD-M Futures User Manual

This manual describes how to run and use the bot in its current state for the
active demo USD-M futures target:

```text
binance/demo/main/usdm_futures
```

It is intentionally conservative. The bot can start in live mode, load and
validate the active Binance demo configuration, run Binance live smoke tests
against the active demo target, consume configured runtime streams when enabled,
maintain projections from published events, and expose internal intervention
state when the operator API is enabled. It is not yet a finished end-user
auto-trading product.

## Current Readiness

The bot is currently a trading-system foundation, not a turnkey trading UI.

What is usable now:

- Start the Spring Boot application in live mode.
- Load the active demo USD-M futures config from `config/catalog.json`,
  `config/application-demo.json`, `config/active.json`, and optional runtime
  overrides.
- Validate Binance provider config and USD-M futures trading capability config.
- Run read-only and guarded Binance live smoke tests against the active demo target.
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
- The LFA strategy module has a conservative top-of-book imbalance analyzer
  that can emit explicit symbol-linked `ENTER_LONG` or `ENTER_SHORT` strategy
  signals from projected market data. It also has a config-gated live signal
  runner that can filter and rank candidate projected market data before
  publishing those signals into the event bus when explicitly enabled. This
  manual does not treat it as a complete production trading
  strategy or portfolio manager.
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
- `trading.intervention.remediation_orchestrator.order_adoption_acknowledgement_enabled`:
  `false`
- `trading.intervention.remediation_executor_policy.enabled`: `false`
- `trading.intervention.remediation_executor_policy.exchange_execution_enabled`:
  `false`
- `trading.intervention.remediation_executor_policy.report_only`: `true`

The Binance live order smoke test is the exception: it is an opt-in Gradle task
that deliberately submits a passive demo USD-M futures order, queries it, and
cancels it.

The `report_only=true` catalog value is a disabled safety default, not the
intended demo-live operating mode. When the bot is deliberately configured to
execute eligible remediation against the Binance demo exchange, the runtime
override must enable the executor policy, enable exchange execution, set
`report_only=false`, and explicitly allow the operation being executed. Today
the supported demo exchange-executable remediation operations are `CANCEL_ORDER`
for an external-order `CLOSE` decision plus one-way `CLOSE_POSITION` and
`REDUCE_POSITION` as bounded reduce-only market orders for external-position
remediation, plus bounded managed-order `AMEND_ORDER` for policy-qualified
limit-order price/quantity amendments.

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
./gradlew :bot-app:bootRun
```

With credentials in the same shell:

```bash
BINANCE_DEMO_API_KEY="<demo-api-key-from-binance>" \
BINANCE_DEMO_API_SECRET="<demo-api-secret-from-binance>" \
./gradlew :bot-app:bootRun
```

Because the default Spring profile is `live`, an explicit profile is normally
not required. If you want to be explicit:

```bash
./gradlew :bot-app:bootRun --args='--spring.profiles.active=live'
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
./gradlew :bot-app:bootRun
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
configured public streams and can also derive additional public streams from
Binance exchange metadata. It maps Binance market-data payloads into core
market-data event envelopes.

Runtime behavior:

- Requires `market_data.runtime_enabled=true`.
- Requires at least one configured stream unless
  `market_data.derive_streams_from_exchange_metadata=true` and derived stream
  templates are configured.
- Requires a `TradingEventBus`; market data is not consumed if it cannot be
  published.
- Supports raw or combined stream endpoint planning from config.
- When metadata derivation is enabled, refreshes Binance exchange metadata,
  filters symbols by provider-level status, quote asset, contract type, and max
  symbol cap, renders stream templates such as `{symbol_lower}@bookTicker`, and
  deduplicates those streams with configured `streams`.
- Shares WebSocket rollover and reconnect behavior with the private stream
  runtime.
- Maps trade, aggregate trade, book ticker, depth snapshot, depth delta, mark
  price, and kline payloads.

For the checked-in Binance USD-M demo/real baseline, the catalog already owns
the high-liquidity `bookTicker`, `aggTrade`, and `kline_1d` stream list. The
runtime file should normally only enable the market-data service or override
operational settings such as route and connection mode.

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

For the checked-in Binance USD-M demo/real baseline, the catalog already owns
the high-liquidity open-order symbol coverage. The runtime file should normally
enable reconciliation and select cadence/snapshot families, not duplicate the
symbol list.

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

Inspect the live runtime status for the active configured target:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/runtime/status'
```

Inspect a specific target and require at least three fresh projected
market-data symbols within 30 seconds:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/runtime/status?provider=binance&environment=demo&account=main&market=usdm_futures&maxMarketDataAgeMillis=30000&minFreshMarketDataSymbols=3&strategyId=lfa-top-book-imbalance'
```

The status response includes:

- `readiness`: `READY`, `ATTENTION`, or `BLOCKED`.
- Runtime target identity from the active config.
- Reconciliation confidence status and observation counts.
- Projected open orders, open positions, external interventions, unknown
  orders, unresolved commands, active pauses, and market-data freshness counts.
- Optional projected strategy lifecycle state when `strategyId` is supplied.
- Explicit blocker strings such as `reconciliation:no_observations`,
  `reconciliation:degraded`, `orders:unknown_status`,
  `orders:unresolved_command`, `interventions:external_orders`,
  `interventions:external_positions`, `governance:active_pause`,
  `market_data:no_symbols`, or `market_data:fresh_symbols_below_minimum`.

This endpoint is read-only. It does not approve trades or bypass any existing
admission path. The execution pipeline, risk gate, idempotency, journal,
projection, reconciliation, and provider gateway remain authoritative.

The same status surface is exported as Prometheus gauges when Micrometer is
active:

- `trading_runtime_readiness_states`
- `trading_runtime_reconciliation_states`
- `trading_runtime_projection_states`
- `trading_runtime_market_data_states`
- `trading_runtime_blocker_states`

Runtime readiness alert rules live in
`ops/prometheus/runtime-readiness-alerts.yml`, and the importable Grafana
dashboard lives in `ops/grafana/runtime-readiness-dashboard.json`.

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

- Pause activation decisions emit structured `pause_governance_activated` audit
  records on the live event path.
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
- The pause audit trail keeps a bounded in-memory buffer by default.
- Enable the optional append-only JSONL store with
  `trading.audit.pause-governance.file-store.enabled=true`.
- The default file path is
  `data/audit/pause-governance-audit.jsonl`; override it with
  `trading.audit.pause-governance.file-store.path`.
- When the file store is enabled, the operator audit endpoint reads persisted
  pause audit records, so recent activation, release, override, and expiry
  records remain queryable after restart.
- Enable the optional indexed JDBC store with
  `trading.audit.pause-governance.jdbc-store.enabled=true` when deployment
  provides a database URL and credentials.
- The JDBC store keeps the full audit event payload plus indexed provider,
  environment, account, market, event, remediation, pause target, actor, and
  occurrence-time columns for faster operational queries.
- JDBC schema initialization is controlled by
  `trading.audit.pause-governance.jdbc-store.initialize-schema`; keep it
  deployment-controlled for managed production databases.

Pause governance metrics:

- Actuator and the Prometheus registry are on the app runtime classpath through
  core configuration.
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
- Audit store persistence or query failures increment
  `trading.pause_governance.audit_store.failures`.
- Release metric tags include `provider`, `environment`, `account`, `market`,
  `scope`, and `outcome`.
- Override metric tags include `provider`, `environment`, `account`, `market`,
  `symbol`, `decision`, `outcome`, and `invalid_reason`.
- Expiry-transition metric tags include `provider`, `environment`, `account`,
  `market`, and `scope`.
- Audit-store failure metric tags include `operation` and `store`; expected
  operations are `record` and `query`, and the JSONL store reports `store=file`.
- Activation and expiry-configured counters are live-only metrics handlers, so
  journal replay and snapshot restore do not increment them.
- The pause expiry monitor is enabled by default. Set
  `trading.observability.pause-governance.expiry-monitor.enabled=false` to
  disable it, or set
  `trading.observability.pause-governance.expiry-monitor.interval-millis` to
  change the scan interval from the default `30000`.
- Recent pause activation, release, override, and expiry audit records can be
  queried through the operator API.
- Prometheus-compatible alert rules for pause governance live in
  `ops/prometheus/pause-governance-alerts.yml`.
- Prometheus-compatible alert rules for remediation executor outcomes live in
  `ops/prometheus/remediation-executor-alerts.yml`.
- Prometheus-compatible alert rules for LFA signal-runner outcomes live in
  `ops/prometheus/strategy-lfa-alerts.yml`. The runner emits
  `trading.strategy.lfa.signal_runner.run.events` counters when it is disabled,
  blocked by lifecycle/reconciliation/budget/allocation policy, or publishes
  strategy signals.
- Alertmanager routing for those pause governance alerts lives in
  `ops/alertmanager/pause-governance-alertmanager.yml`.
- An importable Grafana dashboard for pause governance lives in
  `ops/grafana/pause-governance-dashboard.json`.
- An importable Grafana dashboard for remediation executor outcomes lives in
  `ops/grafana/remediation-executor-dashboard.json`.
- An importable Grafana dashboard for LFA signal-runner outcomes lives in
  `ops/grafana/strategy-lfa-dashboard.json`.
- The remediation executor operator runbook lives in
  `ops/runbooks/remediation-executor.md`; it covers disabled, blocked,
  preview-only, submitted-to-pipeline, no-action, pipeline-submission-failure,
  ambiguous/unknown reconciliation, and hedge-mode remediation outcomes.
- The Alertmanager profile maps alert labels such as `severity` and
  `routing_hint` to operator/platform PagerDuty, Slack, and email receivers.
- Real webhook URLs, Slack channels, PagerDuty routing keys, SMTP credentials,
  and email addresses must be injected through deployment secrets. For a
  single-operator demo setup, set `DEMO_ALERT_EMAIL_TO` during bootstrap to use
  the same email address for operator, platform, and fallback receivers.
- For Google Cloud, render the demo Alertmanager config from Secret Manager with
  `ops/alertmanager/render-google-cloud-alertmanager.sh demo --project "$GCP_PROJECT_ID" --output build/alertmanager/demo-alertmanager.yml`.
  The renderer validates placeholders, fails if a required alert secret is
  missing, refuses unresolved placeholders, and does not print secret values.
- The first Google Cloud demo deployment contract lives in
  `ops/google-cloud/demo-usdm-futures-deployment.yml`.
- That contract maps Binance credentials, operator token, audit JDBC
  credentials, and Alertmanager receiver substitutions to Google Secret Manager
  names.
- For Cloud Run, that contract disables ephemeral JSONL audit persistence and
  selects indexed JDBC pause-governance audit persistence.
- For Cloud Run/ECS, deployment contracts disable ephemeral file projection
  snapshots and select indexed JDBC trading-state projection persistence through
  `TRADING_PROJECTION_JDBC_*` secrets.
- The demo JDBC audit backend is specified as Cloud SQL PostgreSQL with
  deployment-owned schema migration, 180-day retention, Cloud SQL automated
  backups, at least 7 recovery days, and a 90-day restore-drill interval.
- The demo projection backend is specified as Cloud SQL PostgreSQL with
  180-day retention, compaction that preserves the latest snapshot and applied
  event ids, Cloud SQL automated backups, at least 7 recovery days, and a
  90-day restore-drill interval.
- The Google Cloud bootstrap script now creates or verifies the Cloud SQL
  PostgreSQL instance, creates demo and real databases, creates separate audit
  and projection database users, generates missing database passwords, and writes
  the matching JDBC URL/username/password Secret Manager versions unless you
  provide explicit overrides. When `GCP_BUDGET_ALERTS_ENABLED=true` and
  `GCP_BILLING_ACCOUNT` are supplied, it also creates or reuses a project-scoped
  monthly Google Cloud budget alert; the default is `250USD` with current-spend
  thresholds at 50 percent, 80 percent, and 100 percent plus a forecasted-spend
  100 percent threshold.
- When `GITHUB_CONFIGURE_ENVIRONMENTS=true` is set and GitHub CLI is
  authenticated, the same bootstrap script creates or updates the `demo` and
  `real` GitHub environments with the required Google Cloud OIDC/service-account
  secrets and deployment variables.
- The Google Cloud PostgreSQL state migration workflow is manual and
  environment-gated. Run it before Cloud Run deployment or promotion when
  projection/audit schema changes are introduced. It applies the checked-in
  projection and pause-governance audit SQL with a dedicated Cloud SQL migration
  service account while the bot runtime keeps JDBC schema initialization
  disabled.
- The Google Cloud journal archive workflow is manual and environment-gated. It
  archives a produced journal artifact into the configured journal archive
  bucket with a SHA-256 manifest and secret-pattern scan. It preserves raw
  journal files for restore/replay evidence and does not modify live runtime
  state.
- The persistence recovery runbook lives at
  `ops/runbooks/persistence-recovery.md`; it defines restore sequencing,
  reconciliation gates, compaction constraints, and restore-drill evidence.
- The guarded Google Cloud CI/CD path now covers image publication, Cloud Run
  deployment with Cloud SQL attachment, private readiness smoke, and rollback.
- Google Cloud managed alert policy templates live in
  `ops/google-cloud/monitoring/alert-policies`; provision them with
  `ops/google-cloud/provision-monitoring-alert-policies.sh demo`. They currently
  cover Cloud Run 5xx responses and Cloud SQL CPU/disk utilization.
- Google Cloud operational procedures live in
  `ops/runbooks/google-cloud-operations.md`; use it for bootstrap, publish,
  deploy, smoke, rollback, emergency stop, controlled drain, incident evidence,
  and real-promotion gates. Scenario-specific incident response procedures live
  in `ops/runbooks/incident-response.md`; use it for exchange outages, stale
  streams, reconciliation degradation, external orders/positions, unknown order
  outcomes, failed deployments, persistence failures, alerting outages,
  credential rotation or compromise, cost spikes, and real-environment incident
  rules.
- Operational evidence templates, the live release collector, and the demo
  burn-in collector live in `ops/evidence`. Use
  `collect-live-release-evidence.sh demo` to create the initial sanitized bundle
  for each demo release, then complete the remaining live fields with
  deployment, smoke, trading-state, risk-policy, alerting, rollback, and
  emergency-stop outcomes. Use `collect-demo-burn-in-evidence.sh` to assemble
  the sanitized demo burn-in bundle from release evidence, market-universe
  coverage, continuous metrics, trading metrics, drills, observability, and
  incident evidence before any real execution policy is enabled. Evidence must
  prove the same intended real behavior was exercised in demo and must not
  contain secret values.
- Deployment contracts use the neutral schema in
  `ops/deployment/deployment-contract.yml`, so another cloud must keep the same
  app-facing runtime variables and secret keys.
- The root `Dockerfile` is the current production runtime image contract. It
  packages the `bot-app` boot jar, copies `/app/config`, defaults to
  `SPRING_PROFILES_ACTIVE=live`, sets `BOT_CONFIG_DIR=/app/config`, applies the
  Chronicle Queue JVM access flags, runs as a non-root user, and exposes the
  readiness health endpoint. It also carries OCI source, revision, version, and
  build-created labels.
- GitHub Actions builds that image after the Gradle quality gate and uploads
  Buildx metadata as a CI artifact, but does not publish the image yet.
- The Google Cloud image-publish workflow is manual and environment-gated. It
  verifies that the requested commit passed the `Security` workflow, then
  publishes the same image to Artifact Registry with commit-SHA tags, using OIDC
  Workload Identity and GitHub environment variables for project, location, and
  repository. It does not deploy Cloud Run yet.
- The Google Cloud Cloud Run deploy workflow is also manual and
  environment-gated. It verifies the requested commit passed `Security`, checks
  that the commit-tagged image exists in Artifact Registry, maps the checked-in
  deployment contract runtime variables and Secret Manager bindings into Cloud
  Run, blocks unauthenticated access, and records deployment metadata.
- The Google Cloud Cloud Run smoke workflow is manual and environment-gated. It
  verifies that the latest ready private Cloud Run revision belongs to the
  requested commit, runs the matching commit-tagged image, returns `UP` from
  `/actuator/health/readiness` through an authenticated identity-token request,
  and records smoke metadata.
- The Binance live smoke workflow is manual and environment-gated. It verifies
  that the requested commit passed `Security`, selects the requested demo or
  real active target inside the CI workspace, runs server-time, passive order
  lifecycle, user-data stream, and market-data websocket smoke tasks with
  environment-scoped Binance credentials, records smoke metadata, and requires
  `confirm_real_smoke=RUN_REAL_BINANCE_SMOKE` before any real target can run.
- The Google Cloud evidence archive workflow is manual and environment-gated.
  It downloads a generated evidence artifact from a source workflow run, scans it
  for secret-like content, writes an archive manifest, and stores it in the
  versioned evidence archive bucket.
- The real promotion evidence validator is offline and fail-closed. Run
  `ops/evidence/validate-real-promotion-evidence.sh` against completed demo
  burn-in, demo live-release, and real live-release evidence before requesting
  any real execution policy change. It blocks unresolved placeholders, reduced
  demo behavior, BTC-only demo evidence unless real is intentionally BTC-only,
  missing smoke/drill/observability proof, unhealthy reconciliation, unresolved
  unknown outcomes, unreviewed risk caps, and missing explicit promotion
  approval.
- The real promotion evidence validation workflow is manual and uses the `real`
  GitHub environment gate. It downloads the completed evidence artifacts from
  prior workflow runs, verifies the requested commit passed `Security`, runs the
  validator, and uploads a pass/fail report. This workflow validates the
  evidence package; it does not itself enable real exchange execution.
- The Google Cloud Cloud Run rollback workflow is manual and environment-gated.
  It requires the target revision and expected rollback commit SHA, verifies that
  the rollback commit passed `Security`, checks that the existing revision
  belongs to the selected service and matches the expected app, environment,
  commit label, and image tag, shifts 100 percent traffic to that revision,
  verifies private readiness, and records rollback metadata.
- AWS equivalent contracts already exist at
  `ops/aws/demo-usdm-futures-deployment.yml` and
  `ops/aws/real-usdm-futures-deployment.yml`; they map the same bot runtime
  surface to ECS Fargate, AWS Secrets Manager, RDS PostgreSQL, and S3 journal
  archive policy.
- A Google Cloud real contract exists at
  `ops/google-cloud/real-usdm-futures-deployment.yml`; it uses the same code and
  contract shape but switches to `BOT_ENVIRONMENT=real`, real Binance credential
  bindings, real audit/alert secrets, mandatory approval metadata, and explicit
  real-startup guardrails that keep remediation exchange execution disabled.
- `ops/google-cloud/bootstrap-deployment-prereqs.sh` can prepare the current
  Google Cloud foundation for the GitHub workflows. It enables required APIs,
  creates Artifact Registry, the journal archive bucket, service accounts, IAM
  bindings, Workload Identity Federation, and Secret Manager secret containers.
  It defaults the current Google Cloud project, Europe region, registry
  repository, and GitHub repository when not overridden, reads
  `BINANCE_DEMO_API_KEY` and `BINANCE_DEMO_API_SECRET` from `api.env`, generates
  operator-token secret versions when needed, and adds other secret versions only
  when values are supplied through environment variables.

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
planned as `operation=CANCEL_ORDER` with `exchangeExecutable=true`. Position
`CLOSE` and bounded one-way position `REDUCE` decisions can now be planned as
`CLOSE_POSITION` or `REDUCE_POSITION` with `exchangeExecutable=true`. Hedge-mode
`LONG` or `SHORT` position `CLOSE` and bounded `REDUCE` can also be planned as
exchange-executable when `hedge_mode_execution_enabled=true`; the checked-in
demo runtime leaves that switch off. Managed-order `AMEND` decisions can now be
planned as `AMEND_ORDER` with `exchangeExecutable=true` when the active
`managed_order_amendment_policy` admits the target and the projection has
side, order type, current price, current quantity, and target identity. Pause,
ignore, and replan plans remain non-executable until their specific policies
exist. Order `ADOPT` is not exchange-executable; it can publish a projection
ownership-transfer acknowledgement only when
`order_adoption_acknowledgement_enabled=true`.

Preview the remediation executor reports for persisted remediation decisions:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/interventions/remediation/executor/preview?provider=binance&environment=demo&account=main&market=usdm_futures'
```

Preview responses include `enabled`, `exchangeExecutionEnabled`, `reportOnly`,
batch counts, and per-plan reports with `planStatus`, `operation`, `status`,
`reasons`, `attributes`, and the original `plan`. The endpoint does not submit
exchange commands. It explains whether each plan is blocked, no-action, or
would remain report-only under the current executor policy.

Every preview evaluation also increments
`trading.remediation_executor.outcome.events` with bounded labels for provider,
environment, account, market, mode, operation, status, and executor reason. The
metric does not tag remediation ids or order ids.

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

This endpoint is still policy-gated. It submits only eligible `CANCEL_ORDER`,
`CLOSE_POSITION`, `REDUCE_POSITION`, and `AMEND_ORDER` plans, and only when
`remediation_executor_policy.enabled=true`,
`exchange_execution_enabled=true`, `report_only=false`, the exact operation is
in `allowed_operations`, the target identity is present, and the normal order
execution pipeline is enabled. Position close/reduce submissions are `NEW`
`MARKET` orders with the opposite side, bounded quantity, `positionSide=BOTH`,
`reduceOnly=true`, and `closePosition=false`. Managed amendment submissions are
`MODIFY` commands with the projected side/order type and requested or retained
price/quantity. It returns
`SUBMITTED_TO_PIPELINE` after the command has been accepted by the pipeline; the
final risk decision and order result are recorded through the normal event path.
Execute evaluations increment the same
`trading.remediation_executor.outcome.events` metric, including blocked,
preview-only, submitted-to-pipeline, and no-action outcomes.

For the current demo USD-M futures target, the checked-in first-start runtime
override lives here:

```text
config/runtime/live/binance/demo/main/usdm_futures.json
```

It selects the demo instance id, enables local journal/recovery and projection
snapshot persistence, enables the event-driven runtime switches intended for
demo operation, enables the execution pipeline, configures the signal planner's
default target as `binance/demo/main/usdm_futures`, enables the
strategy instrument-universe gate for a broad high-liquidity USD-M futures
candidate list, enables
automated remediation policy for external order close decisions, enables the
scheduled remediation runner, and enables the executor policy for the currently
supported `CANCEL_ORDER`, `CLOSE_POSITION`, `REDUCE_POSITION`, and
`AMEND_ORDER` remediation paths. The same checked-in runtime file is loaded
before Spring creates
conditional runtime services, so these `trading.*` values drive both external
runtime config and Spring-managed service activation.
Command-line arguments and OS environment variables still override the checked-in
file.

The remediation execution portion of that runtime override is:

```json
{
  "trading": {
    "execution": {
      "pipeline": {
        "enabled": true
      }
    },
    "intervention": {
      "automated_policy": {
        "external_order_action": "CLOSE",
        "open_position_action": "CLOSE"
      },
      "remediation_executor_policy": {
        "enabled": true,
        "exchange_execution_enabled": true,
        "report_only": false,
        "allowed_operations": [
          "CANCEL_ORDER",
          "CLOSE_POSITION",
          "REDUCE_POSITION",
          "AMEND_ORDER"
        ],
        "position_order_policy": {
          "one_way_reduce_only_enabled": true,
          "allowed_symbols": [
            "BTCUSDT",
            "ETHUSDT"
          ],
          "max_position_quantity": "0.001",
          "chunk_close_when_max_quantity_exceeded": true,
          "max_position_notional": "250",
          "required_margin_type": "cross",
          "min_leverage": "1",
          "max_leverage": "5"
        },
        "managed_order_amendment_policy": {
          "enabled": true,
          "allowed_symbols": [
            "BTCUSDT",
            "ETHUSDT"
          ],
          "max_quantity_decrease_fraction": "0.50",
          "max_price_drift_fraction": "0.02",
          "max_projection_age_millis": 30000
        }
      }
    }
  }
}
```

This checked-in override does not make every remediation action executable. It
only allows the executor to submit currently supported cancel, one-way
reduce-only position, and bounded managed-order amendment plans through the
normal order execution pipeline when all projection, identity, freshness, risk,
policy, and idempotency gates pass. The remaining position-order policy fields
use active catalog values because the runtime file does not override them:
`provider=binance`, `market=usdm_futures`, `position_side=BOTH`,
`order_type=MARKET`, `require_reduce_only=true`,
`require_close_position_false=true`,
`reject_unbounded_position_notional=true`, and
`hedge_mode_execution_enabled=false`, and
`max_account_position_notional=null`, `max_symbol_position_notional=null`,
`max_account_unrealized_loss=null`, `max_symbol_unrealized_loss=null`,
`min_account_margin_balance=null`, `max_account_margin_drawdown_fraction=null`,
`max_account_margin_utilization=null`, `max_account_daily_realized_loss=null`,
and `max_symbol_daily_realized_loss=null`. The checked-in demo runtime explicitly
limits automated position remediation to `BTCUSDT` and `ETHUSDT`,
`max_position_quantity=0.001`, `chunk_close_when_max_quantity_exceeded=true`,
and `max_position_notional=250`, and requires projected futures account
metadata to show `margin_type=cross` with leverage between `1` and `5`. It does
not set account/symbol exposure, current unrealized-loss, account margin-balance
floor, account margin drawdown, account margin-utilization, or account daily
realized-loss caps yet; set them only after calibrating limits for the target.
Change those runtime values before
first start if the demo target should use a different symbol, cap, margin mode,
leverage, exposure budget, loss budget, account equity floor, margin drawdown
budget, or account margin-utilization limit.

Managed-order amendment policy fields also use active catalog values unless a
higher-precedence config source overrides them. The checked-in demo runtime enables amendments only for
bot-created `BTCUSDT` and `ETHUSDT` `LIMIT` orders, permits `PRICE` and `QUANTITY`, rejects
quantity increases, permits quantity decreases up to 50%, caps price drift at
2%, rejects projections older than 30 seconds, and allows only projected
`ACCEPTED` or `PARTIALLY_FILLED` target orders. The runtime file overrides only
values that differ from the catalog value; catalog values still define allowed
order type, allowed fields, quantity direction, stale-projection rejection,
open-order status enforcement, exchange-order-id requirement, and allowed
statuses.

Hedge-mode `LONG` or `SHORT` position close/reduce command construction is
implemented, but the checked-in demo runtime keeps it disabled. Enabling it
requires an explicit runtime override of
`position_order_policy.hedge_mode_execution_enabled=true`. The generated
hedge-mode remediation order is `NEW MARKET` on the opposite side with the
projected `positionSide`, bounded quantity, `reduceOnly=false`, and
`closePosition=false`.

Operator API authentication is not hardcoded in that runtime file. Set operator
tokens and exchange credentials through environment variables or the deployment
secret system, not source-controlled JSON.

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

Live-mode config is merged in this order. Catalog values execute when no
higher-precedence source overrides them:

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
- `config/application-backtest.json`: deferred v2 backtest-mode config surface.

Startup rejects unsupported schema ids, schema versions, config versions, and
migration policies. Unknown override paths are rejected.

## Trading Runtime Catalog Defaults

The checked-in `config/catalog.json` is the source-controlled default catalog.
Every value below is runtime-overridable through the normal config merge path
unless a validator rejects an unsafe combination.

Journal defaults:

- `trading.journal.enabled`: `false`
- `trading.journal.directory`: `data/journal/trading-events`
- `trading.journal.recovery.enabled`: `false`

Projection defaults:

- `trading.projection.snapshot_store.enabled`: `false`
- `trading.projection.snapshot_store.path`:
  `data/projection/trading-state-snapshot.json`
- `trading.projection.jdbc_store.enabled`: `false`
- `trading.projection.jdbc_store.url`: `null`
- `trading.projection.jdbc_store.username`: `null`
- `trading.projection.jdbc_store.password`: empty string
- `trading.projection.jdbc_store.table_prefix`: `trading_projection_`
- `trading.projection.jdbc_store.initialize_schema`: `false`

Messaging defaults:

- `trading.messaging.enabled`: `false`
- `trading.messaging.bootstrap_servers`: `localhost:19092`
- `trading.messaging.schema_registry_url`: `http://localhost:18081`
- `trading.messaging.client_id_prefix`: `trading-bot`
- `trading.messaging.topics.auto_create`: `false`
- `trading.messaging.topics.replication_factor`: `1`
- `trading.messaging.consumers.enabled`: `false`
- `trading.messaging.consumers.auto_start`: `false`
- `trading.messaging.consumers.group_id_suffix`: `dispatcher`
- `trading.messaging.consumers.poll_timeout_millis`: `250`

Pause governance audit and observability defaults:

- `trading.audit.pause_governance.file_store.enabled`: `false`
- `trading.audit.pause_governance.file_store.path`:
  `data/audit/pause-governance-audit.jsonl`
- `trading.audit.pause_governance.jdbc_store.enabled`: `false`
- `trading.audit.pause_governance.jdbc_store.url`: `null`
- `trading.audit.pause_governance.jdbc_store.username`: `null`
- `trading.audit.pause_governance.jdbc_store.password`: empty string
- `trading.audit.pause_governance.jdbc_store.table_prefix`:
  `trading_audit_pause_governance_`
- `trading.audit.pause_governance.jdbc_store.initialize_schema`: `false`
- `trading.observability.pause_governance.expiry_monitor.enabled`: `true`
- `trading.observability.pause_governance.expiry_monitor.interval_millis`:
  `30000`

Remediation executor observability has no dedicated runtime switch. When
Micrometer/Actuator metrics are available, the executor emits:

- `trading.risk_gate.decision.events` for every order risk-gate decision before
  provider mapping, with tags `provider`, `environment`, `account`, `market`,
  `action`, `decision`, `primary_reason`, `reduce_only`, and `close_position`
- Prometheus alert rules at `ops/prometheus/risk-gate-alerts.yml`
- Importable Grafana dashboard at `ops/grafana/risk-gate-dashboard.json`
- `trading.remediation_executor.outcome.events` with tags `provider`,
  `environment`, `account`, `market`, `mode`, `operation`, `status`, and
  `reason`
- Disabled policy evaluations as `operation=NONE`, `status=DISABLED`, and
  `reason=executor:policy_disabled`
- Per-plan blocked, preview-only, submitted-to-pipeline, and no-action
  evaluations

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
- `streams`: for Binance USD-M demo/real, the catalog baseline includes
  `bookTicker`, `aggTrade`, and `kline_1d` streams for the configured
  high-liquidity universe
- `derive_streams_from_exchange_metadata`: `false` by default; checked-in demo
  runtime sets `true`
- `derived_stream_templates`: checked-in demo runtime uses
  `{symbol_lower}@bookTicker`, `{symbol_lower}@aggTrade`, and
  `{symbol_lower}@kline_1d`
- `derived_allowed_quote_assets`: checked-in demo runtime uses `USDT`
- `derived_allowed_contract_types`: checked-in demo runtime uses `PERPETUAL`
- `derived_required_status`: checked-in demo runtime uses `TRADING`
- `derived_max_symbols`: checked-in demo runtime uses `250`

When metadata-derived streams are enabled, scheduled exchange-metadata refresh
also refreshes the active provider runtime. Binance compares the current public
market-data websocket plan with the newly rendered metadata-derived plan and
reconnects only if the stream set, route, or connection mode changed.

Do not duplicate the baseline stream list in the runtime file. Override
`streams` only when intentionally replacing catalog stream coverage for a
specific target. Prefer metadata derivation when expanding exchange-polled
coverage for the same demo/real behavior.

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
- `open_order_symbols`: for Binance USD-M demo/real, the catalog baseline
  covers the configured high-liquidity universe
- `order_history_enabled`: `false`
- `order_history_symbols`: empty by default
- `order_history_limit`: `1000`
- `account_trades_enabled`: `false`
- `account_trade_symbols`: empty by default
- `account_trades_limit`: `1000`
- `futures_balances_enabled`: `false`
- `futures_account_enabled`: `false`
- `futures_positions_enabled`: `false`

Enable only the snapshot families you need. For demo USD-M, the checked-in
runtime enables open orders, balances, account snapshots, and positions while
inheriting the high-liquidity symbol coverage from the catalog.

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

The execution pipeline also calls provider preflight after the risk gate
approves a command but before gateway submission. For Binance `NEW` orders,
preflight reuses the same configured capability checks and exchange-filter
validation used by the gateway. For Binance `CANCEL` commands, preflight
requires a target client order id or exchange order id before the gateway can
send a delete request. For Binance futures `MODIFY` commands, preflight checks
the target identity, side, quantity, price, and price-match combination before
submission. If preflight fails, the pipeline publishes a rejected risk decision
with `execution:provider_preflight_rejected` and does not call the exchange
gateway. This applies to remediation-generated cancels and position `MARKET`
orders as well as strategy-generated new or modify orders.

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
- `instrument_universe.enabled`: `false`
- `instrument_universe.included_symbols`: catalog baseline `BTCUSDT`, `ETHUSDT`,
  `BNBUSDT`, `SOLUSDT`, `XRPUSDT`, `DOGEUSDT`, `ADAUSDT`, `LINKUSDT`,
  `AVAXUSDT`, `BCHUSDT`, `LTCUSDT`, `TRXUSDT`, `DOTUSDT`
- `instrument_universe.excluded_symbols`: empty list
- `instrument_universe.refresh_exchange_metadata_before_planning`: `false`
- `instrument_universe.require_exchange_metadata`: `false`
- `instrument_universe.require_included_symbol`: `false`
- `instrument_universe.require_symbol_enabled`: `true`
- `instrument_universe.require_promotion_ready`: `false`
- `instrument_universe.required_status`: `TRADING`
- `instrument_universe.required_order_type`: `null`
- `instrument_universe.allowed_quote_assets`: empty list
- `instrument_universe.allowed_contract_types`: empty list
- `instrument_universe.max_eligible_symbols`: `null`
- `instrument_universe.require_market_data`: `false`
- `instrument_universe.require_top_of_book`: `false`
- `instrument_universe.max_market_data_age_millis`: `60000`
- `instrument_universe.max_spread_bps`: `null`
- `instrument_universe.min_top_of_book_quote_notional`: `null`
- `instrument_universe.min_daily_quote_volume`: `null`
- `instrument_universe.symbol_policies`: catalog baseline policies for the same
  USD-M futures candidate list, each enabled and promotion-ready with
  `min_daily_quote_volume=100000000`, `max_spread_bps=5`,
  `min_top_of_book_quote_notional=250`, and `max_order_notional=50`

When enabled, the planner handles `STRATEGY_SIGNAL` events in live mode. It is
not enabled by default because automated strategy-to-order planning still needs
operator-controlled rollout. When `instrument_universe.enabled=true`, strategy
signals are suppressed before command publication if their resolved symbol is
excluded, missing from a required include list, disabled by a matching symbol
policy, or not marked `promotion_ready=true` while promotion readiness is
required. If a matching symbol policy has `max_order_notional`, unbounded or
oversized strategy orders are suppressed before publication. When
`instrument_universe.min_daily_quote_volume` is configured, every admissible
candidate must have projected market-data attribute `quoteVolume` greater than
or equal to that floor. A matching symbol policy can override the universe floor
for that symbol. The catalog `kline_1d` streams provide the `quoteVolume`
attribute, and projection preserves it across later top-of-book/trade updates.
When the quote-volume floor cannot be proven, the command is suppressed instead
of guessing liquidity. When
`refresh_exchange_metadata_before_planning=true`, the planner refreshes provider
exchange metadata before admission and only allows symbols that are currently
present, tradable, compatible with the configured quote asset and contract type,
and compatible with the order type being planned.
When `require_market_data=true`, the planner requires a projected latest market
data snapshot for the resolved symbol. When `require_top_of_book=true`, it
requires projected best bid and best ask. `max_market_data_age_millis` bounds
the maximum age of the required snapshot, and `max_spread_bps` rejects symbols
whose top-of-book spread is wider than the configured limit. A symbol policy
`max_spread_bps` overrides the universe-level spread limit for that symbol.
`min_top_of_book_quote_notional` rejects symbols whose best bid or best ask
does not carry enough projected quote notional; the effective depth is the
smaller of best-bid quote notional and best-ask quote notional. A symbol policy
`min_top_of_book_quote_notional` overrides the universe-level depth limit for
that symbol. When a strategy signal omits `symbol`, the planner evaluates
admissible universe candidates and selects the highest ranked symbol using
projected freshness, spread, and top-of-book quote depth. When a signal provides
`symbol`, that explicit symbol is used and must pass the same gates.
Planned order commands include `planner_symbol_selection` and
`planner_selected_symbol` attributes so operators can distinguish default,
explicit, and ranked-universe symbol selection.

## LFA Signal Analyzer And Runner

The current LFA module can analyze projected top-of-book market data for a
target provider, environment, account, and market. It evaluates each instrument
independently and only emits a strategy signal when the instrument passes the
configured freshness, spread, depth, and imbalance gates.

Current conservative request defaults for USD-M futures are:

- `strategyId`: caller supplied, normally `lfa`
- `provider`: caller supplied, normally `binance`
- `environment`: caller supplied, normally `demo` or `real`
- `account`: caller supplied, normally `main`
- `market`: caller supplied, normally `usdm_futures`
- `minImbalanceRatio`: `1.50`
- `maxSpreadBps`: `5`
- `minTopOfBookQuoteNotional`: `250`
- `maxMarketDataAgeMillis`: `30000`
- `targetQuantity`: caller supplied
- `targetNotional`: `null` unless the caller chooses notional sizing

The config-gated runner is controlled by `trading.strategy.lfa.signal_runner`.
Catalog defaults are:

- `enabled`: `false`
- `initial_delay_millis`: `30000`
- `interval_millis`: `30000`
- `signal_ttl_millis`: `30000`
- `strategy_id`: `lfa`
- `provider`: `null`
- `environment`: `null`
- `account`: `null`
- `market`: `null`
- `lifecycle_state`: `STOPPED`
- `allowed_lifecycle_states`: `["ACTIVE"]`
- `allowed_lifecycle_transitions`: `STARTING->[PAUSED,STOPPED,EMERGENCY_STOP]`, `PAUSED->[ACTIVE,DRAINING,STOPPED,EMERGENCY_STOP]`, `ACTIVE->[PAUSED,DRAINING,EMERGENCY_STOP]`, `DRAINING->[PAUSED,STOPPED,EMERGENCY_STOP]`, `STOPPED->[STARTING,PAUSED,EMERGENCY_STOP]`, `EMERGENCY_STOP->[STOPPED]`
- `allow_emergency_stop_reactivation`: `false`
- `require_warmup_market_data`: `true`
- `min_warmup_market_data_symbols`: `1`
- `min_warmup_top_of_book_symbols`: `1`
- `warmup_max_market_data_age_millis`: `30000`
- `use_signal_planner_instrument_universe`: `true`
- `max_candidate_market_data_symbols`: `null`
- `min_imbalance_ratio`: `1.50`
- `max_spread_bps`: `5`
- `min_top_of_book_quote_notional`: `250`
- `max_market_data_age_millis`: `30000`
- `target_quantity`: `null`
- `target_notional`: `null`
- `target_notional_margin_balance_fraction`: `null`
- `min_allocated_target_notional`: `null`
- `max_allocated_target_notional`: `null`
- `max_strategy_run_notional`: `null`
- `reject_missing_allocation_balance`: `true`
- `allocation_weighting_mode`: `EQUAL`
- `market_quality_quote_volume_baseline`: `100000000`
- `market_quality_trade_count_baseline`: `100000`
- `market_quality_taker_buy_quote_volume_baseline`: `50000000`
- `expected_profit_bps_baseline`: `1`
- `expected_profit_score_cap`: `10`
- `min_expected_profit_bps`: `null`
- `min_expected_profit_score`: `null`
- `min_risk_money_management_fit_score`: `null`
- `max_signals_per_run`: `1`
- `max_account_open_orders`: `null`
- `max_symbol_open_orders`: `null`
- `max_account_open_order_notional`: `null`
- `max_symbol_open_order_notional`: `null`
- `max_account_open_positions`: `null`
- `max_symbol_open_positions`: `null`
- `max_account_position_notional`: `null`
- `max_symbol_position_notional`: `null`
- `max_account_unrealized_loss`: `null`
- `max_symbol_unrealized_loss`: `null`
- `min_account_margin_balance`: `null`
- `max_account_margin_drawdown_fraction`: `null`
- `max_account_margin_utilization`: `null`
- `max_account_daily_realized_loss`: `null`
- `max_symbol_daily_realized_loss`: `null`
- `reject_missing_account_risk_metadata`: `true`
- `require_signal_planner_enabled`: `true`
- `require_reconciliation_confidence`: `true`

Valid `lifecycle_state` values are `STARTING`, `ACTIVE`, `PAUSED`,
`DRAINING`, `STOPPED`, and `EMERGENCY_STOP`. Unknown values are rejected during
configuration binding. The runner publishes new signals only in `ACTIVE`; the
non-entry states `STARTING`, `PAUSED`, `DRAINING`, `STOPPED`, and
`EMERGENCY_STOP` fail closed even if accidentally added to
`allowed_lifecycle_states`. Lifecycle transitions are also policy-gated by
`allowed_lifecycle_transitions`; leaving `EMERGENCY_STOP` requires
`allow_emergency_stop_reactivation=true`, and the default path only permits
returning from emergency stop to `STOPPED`, not directly to `ACTIVE`. The
`DRAINING -> STOPPED` transition is accepted only when projected open order and
open position counts for the runner target are both zero.

If the LFA runner is enabled and the internal operator API is enabled, the
effective in-process lifecycle can be inspected and changed through the same
operator token used by remediation endpoints:

```bash
curl -H 'X-Operator-Token: <operator-token>' \
  'http://localhost:8080/internal/strategy/lfa/lifecycle'
```

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Token: <operator-token>' \
  -d '{"lifecycleState":"ACTIVE","changedBy":"operator","reason":"promotion gate passed"}' \
  'http://localhost:8080/internal/strategy/lfa/lifecycle'
```

The lifecycle endpoint is runtime control, not a code fork. It behaves the same
for demo and real when the same code and corresponding runtime config are used.
Successful transitions publish a durable `STRATEGY_LIFECYCLE` event and apply it
to projection state, so restart recovery can restore the latest projected
lifecycle before status checks or scheduled signal runs. Status responses include
`openOrderCount`, `openPositionCount`, and `drainComplete` so an operator can see
whether controlled drain can move to `STOPPED`. Each successful lifecycle event
records those counts plus drain readiness, allowed next states, and
emergency-stop flags in durable event attributes for audit/restart evidence.

The checked-in demo runtime does not override `enabled`, so the active effective
value remains the catalog value `enabled=false`. It sets only actual first-start
overrides for `binance/demo/main/usdm_futures`:

- `initial_delay_millis`: `10000`
- `signal_ttl_millis`: `15000`
- `provider`: `binance`
- `environment`: `demo`
- `account`: `main`
- `market`: `usdm_futures`
- `lifecycle_state`: `PAUSED`
- `min_warmup_market_data_symbols`: `3`
- `min_warmup_top_of_book_symbols`: `3`
- `max_candidate_market_data_symbols`: `13`
- `target_quantity`: `0.001`
- `target_notional_margin_balance_fraction`: `0.01`
- `max_allocated_target_notional`: `50`
- `max_strategy_run_notional`: `50`
- `allocation_weighting_mode`: `MARKET_QUALITY`
- `min_expected_profit_bps`: `1`
- `min_expected_profit_score`: `1`
- `min_risk_money_management_fit_score`: `0.25`
- `max_account_open_positions`: `3`
- `max_symbol_open_positions`: `1`

The analyzer emits no signal when market data is stale, incomplete, crossed,
too wide, too thin, or not imbalanced enough. If bid-side quote notional
dominates, it emits `ENTER_LONG` with `symbol` set to that instrument and
`limitPrice` set to the best ask. If ask-side quote notional dominates, it emits
`ENTER_SHORT` with `symbol` set to that instrument and `limitPrice` set to the
best bid. Signal features include `order_type=LIMIT` and `time_in_force=GTC`.
Signal attributes include:

- `source=lfa_market_signal_analyzer`
- `lfa_signal_reason=top_of_book_imbalance`
- `lfa_market_data_age_millis`
- `lfa_spread_bps`
- `lfa_top_of_book_quote_notional`
- `lfa_bid_quote_notional`
- `lfa_ask_quote_notional`
- `lfa_imbalance_ratio`
- `lfa_daily_quote_volume` when projected daily quote volume is available
- `lfa_daily_number_of_trades` when projected daily trade count is available
- `lfa_daily_taker_buy_quote_volume` when projected daily taker-buy quote
  volume is available

When enabled, the runner reads the current projection snapshot, requires the
current lifecycle state to be allowed, requires projected market-data and
top-of-book warm-up thresholds to be met, applies account budget gates, filters
candidate symbols through the core signal-planner instrument universe when
`use_signal_planner_instrument_universe=true`, ranks candidate market data by
projected spread, daily quote volume, daily trade count, daily taker-buy quote
volume, provider capability score, reconciliation availability, top-of-book
quote depth, freshness, and symbol, applies `max_candidate_market_data_symbols`
when configured, passes only those candidate market-data states to the analyzer,
annotates emitted signals with `lfa_expected_edge_score`, sorts analyzed signals
by that score before applying `max_signals_per_run`, optionally replaces fixed
signal sizing with target notional allocated from the latest projected account
margin balance, applies symbol budget gates to candidate signals, publishes at
most `max_signals_per_run` ranked signals as symbol-keyed `STRATEGY_SIGNAL`
events, and then the existing signal planner and risk gates decide whether any
order command can be built and admitted. The expected-edge ranking now includes
`lfa_risk_money_management_fit_score`, a projected 0-to-1 fit score for
remaining account/symbol open-order capacity, open-position capacity, notional
capacity, current unrealized-loss capacity, daily realized-loss capacity, and
account margin-health capacity, and `lfa_expected_profit_score`, a capped
top-of-book expected-profit score derived from excess imbalance over
`min_imbalance_ratio`, the configured spread threshold, and observed spread. A
lifecycle block returns
`lfa_signal_runner:lifecycle_blocked`, a warm-up block returns
`lfa_signal_runner:warmup_incomplete`, an allocation block returns
`lfa_signal_runner:allocation_blocked`, and a budget block returns
`lfa_signal_runner:budget_blocked`. A reconciliation-confidence block returns
`lfa_signal_runner:reconciliation_blocked`. A blocked runner does not publish
the signal.

When `target_notional_margin_balance_fraction` is configured, the runner reads
the latest projected account-level risk state for the same provider,
environment, account, and market with `risk_scope=ACCOUNT`. If that projection
contains a positive `margin_balance`, the runner computes target notional as
`margin_balance * target_notional_margin_balance_fraction`, applies
`max_allocated_target_notional` when configured, applies
`max_strategy_run_notional` when configured, divides that total run budget
across the candidate publish slots for the run using `allocation_weighting_mode`,
enforces `min_allocated_target_notional` on each target notional when configured,
clears `targetQuantity`, sets `targetNotional`, and records allocation
attributes on each signal. `EQUAL` preserves flat allocation,
`CONFIDENCE` weights by analyzer confidence, and `MARKET_QUALITY` weights by
confidence, top-of-book imbalance, effective quote depth, spread, projected
daily `quoteVolume`, projected daily trade count, projected daily taker-buy
quote volume, and freshness. Daily quote volume, trade count, and taker-buy
quote volume are compared with their `market_quality_*_baseline` values and
capped inside the quality score so highly active markets are preferred without
allowing one raw liquidity number to consume the whole run allocation.
Before analysis, candidate market data is also ranked by provider capability score when exchange metadata is available and by symbol-level reconciliation availability when comparable spread, volume, and trade-statistic conditions exist.
When the reconciliation tracker has confident observations whose entity key
references the candidate symbol, emitted signals include
`lfa_reconciliation_availability_score` for auditability.
After analysis, each emitted signal receives `lfa_risk_money_management_fit_score`,
`lfa_expected_profit_bps`, `lfa_expected_profit_score`,
`lfa_expected_profit_model`, optional `lfa_expected_profit_notional`,
`signal_ttl_millis`, `signal_expires_at`, and `lfa_expected_edge_score`. The expected-edge score is the runner's current
auditable ranking measure for the publish cap and combines analyzer confidence,
imbalance, spread, effective quote depth, projected daily quote volume, projected
daily trade count, projected daily taker-buy quote volume, freshness, provider
capability score, reconciliation availability, projected risk/money-management
fit, and the capped expected-profit score. The expected-profit model currently
uses `(imbalanceRatio - minImbalanceRatio) * maxSpreadBps - observedSpreadBps`,
floored at zero, then divides by `expected_profit_bps_baseline` and caps at
`expected_profit_score_cap`. If target notional or quantity/limit-price notional
is known, the runner also records estimated expected notional profit. The risk
fit score uses the same projected account and symbol budgets that can block
publication, but as a ranking penalty before the publish cap. If
`min_expected_profit_bps`, `min_expected_profit_score`, or
`min_risk_money_management_fit_score` is configured, the runner blocks a
candidate signal whose expected-profit estimate or projected risk fit is below
the configured floor instead of publishing a marginal signal.
Signals carrying an expired or malformed `signal_expires_at` attribute are suppressed by the core signal planner before order-command construction, so stale LFA signals do not reach execution admission. This is a first signal ordering, freshness, and admission layer, not the complete v1 portfolio manager, position
lifecycle, take-profit/stop-loss, realized-PnL model, or live promotion evidence
layer.

For exit and reduction intents, the core signal planner also enforces a bounded
reduce-only shape before publishing an order command. `EXIT_LONG`, `EXIT_SHORT`,
`REDUCE_LONG`, and `REDUCE_SHORT` can pass only when the signal carries an
explicit `targetQuantity`, or when the signal is an unsized
`close_position=true` full-close intent. Quote-notional reduce-only signals are
suppressed, and close-position signals with explicit size are suppressed, so the
planner does not mix partial-reduction sizing with full-close semantics.
The order risk gate applies the same reduce-only and close-position shape rule
to every non-cancel order command before provider mapping, so direct command
sources cannot bypass the strategy planner's safety constraint.
With the catalog default
`reject_missing_allocation_balance=true`, missing account margin balance blocks
publication instead of falling back to stale or ambiguous sizing.
If `target_notional_margin_balance_fraction` is not configured and
`max_strategy_run_notional` is configured, the runner verifies the fixed or
pre-supplied signal notional for the publish slots. It blocks instead of
publishing if projected total run notional exceeds the cap or if any signal
notional is unbounded.

Allocation attributes include:

- `lfa_allocation_source=account_margin_balance`
- `lfa_allocation_base`
- `lfa_allocation_fraction`
- `lfa_allocation_total_target_notional`
- `lfa_allocated_target_notional`
- `lfa_allocation_strategy_run_notional_cap` when the strategy-run cap is configured for allocated signals
- `lfa_allocation_weighting_mode`
- `lfa_allocation_weight`
- `lfa_allocation_weight_sum`
- `lfa_risk_money_management_fit_score`
- `lfa_expected_profit_model`
- `lfa_expected_profit_bps`
- `lfa_expected_profit_score`
- `lfa_expected_profit_notional` when signal notional is known
- `lfa_expected_edge_score`
- `lfa_daily_quote_volume` when projected daily quote volume is available
- `lfa_daily_number_of_trades` when projected daily trade count is available
- `lfa_daily_taker_buy_quote_volume` when projected daily taker-buy quote
  volume is available
- `lfa_provider_capability_score` when exchange metadata contributed to
  candidate ranking
- `lfa_reconciliation_availability_score` when symbol-level reconciliation
  availability contributed to candidate ranking

Current runner lifecycle and warm-up blockers can block on:

- `lfa_lifecycle:starting`
- `lfa_lifecycle:paused`
- `lfa_lifecycle:draining`
- `lfa_lifecycle:stopped`
- `lfa_lifecycle:emergency_stop`
- `lfa_lifecycle:not_allowed`
- `lfa_warmup:market_data_symbols_below_min`
- `lfa_warmup:top_of_book_symbols_below_min`

Current runner budget gates can block on:

- `lfa_budget:max_account_open_orders`
- `lfa_budget:max_symbol_open_orders`
- `lfa_budget:max_account_open_order_notional`
- `lfa_budget:max_symbol_open_order_notional`
- `lfa_budget:open_order_notional_metadata_missing`
- `lfa_budget:max_account_open_positions`
- `lfa_budget:max_symbol_open_positions`
- `lfa_budget:max_account_position_notional`
- `lfa_budget:max_symbol_position_notional`
- `lfa_budget:min_expected_profit_bps`
- `lfa_budget:min_expected_profit_score`
- `lfa_budget:min_risk_money_management_fit_score`
- `lfa_budget:max_account_unrealized_loss`
- `lfa_budget:max_symbol_unrealized_loss`
- `lfa_budget:min_account_margin_balance`
- `lfa_budget:max_account_margin_drawdown`
- `lfa_budget:max_account_margin_utilization`
- `lfa_budget:max_account_daily_realized_loss`
- `lfa_budget:max_symbol_daily_realized_loss`
- `lfa_budget:account_risk_missing`
- `lfa_budget:margin_balance_missing`
- `lfa_budget:max_margin_balance_missing`
- `lfa_budget:maintenance_margin_missing`
- `lfa_budget:position_notional_metadata_missing`
- `lfa_budget:unrealized_pnl_missing`
- `lfa_budget:daily_realized_pnl_missing`
- `lfa_budget:signal_notional_unbounded`

When `max_account_open_order_notional` or `max_symbol_open_order_notional` is
configured, the runner estimates projected open-order exposure from remaining
quantity and limit price, then blocks either current excess exposure or a
proposed signal that would push exposure above the cap. If relevant open-order
price or quantity metadata is missing and
`reject_missing_account_risk_metadata=true`, the runner blocks with
`lfa_budget:open_order_notional_metadata_missing` instead of assuming exposure is
safe.

Current runner allocation gates can block on:

- `lfa_allocation:account_margin_balance_missing`
- `lfa_allocation:max_strategy_run_notional`
- `lfa_allocation:strategy_run_notional_unbounded`
- `lfa_allocation:target_notional_below_min`
- `lfa_allocation:target_notional_non_positive`

Current runner reconciliation gates can block on:

- `lfa_reconciliation:no_observations`
- `lfa_reconciliation:degraded`

The checked-in demo runtime keeps the runner disabled because full position
lifecycle and broader money-management controls are still incomplete. It already
sets `lifecycle_state=PAUSED`, three-symbol projected-data warm-up thresholds,
`max_candidate_market_data_symbols=13`, open-position caps,
`target_notional_margin_balance_fraction=0.01`, and
`max_allocated_target_notional=50`, `max_strategy_run_notional=50`, and
`allocation_weighting_mode=MARKET_QUALITY` for first-start demo operation;
notional and daily-loss caps remain inherited as `null` until calibrated for the
target account.

The checked-in catalog owns the bounded candidate baseline of `BTCUSDT`,
`ETHUSDT`, `BNBUSDT`, `SOLUSDT`, `XRPUSDT`, `DOGEUSDT`, `ADAUSDT`,
`LINKUSDT`, `AVAXUSDT`, `BCHUSDT`, `LTCUSDT`, `TRXUSDT`, and `DOTUSDT`.
The checked-in demo runtime opts into that catalog baseline and sets
`refresh_exchange_metadata_before_planning=true`,
`require_exchange_metadata=true`, `allowed_quote_assets=["USDT"]`,
`allowed_contract_types=["PERPETUAL"]`, `require_market_data=true`,
`require_top_of_book=true`, `max_market_data_age_millis=30000`, and
`max_spread_bps="5"`, `min_top_of_book_quote_notional="250"`, and
`min_daily_quote_volume="100000000"`. It inherits catalog
`require_included_symbol=false` and `max_eligible_symbols=null`. The catalog
baseline still provides first-start stream coverage, reconciliation symbols, and
symbol policies, but effective strategy admission in this runtime is
exchange-polled rather than static-list bound.

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
- Target-order commands require projected managed targets by default.
- External remediation cancel is allowed only for executor-originated
  `ORDER`/`CLOSE`/`CANCEL_ORDER` commands with remediation attributes.
- Adopted target orders are not allowed for ordinary target commands by default
  (`target_order.allow_adopted_target_orders=false`). Runtime config must
  explicitly set that switch to true before normal cancel/modify target commands
  can operate on adopted orders.
- Adopted remediation executor commands have a narrower policy path:
  `adopted_order_lifecycle_policy` must explicitly allow cancel or amend before
  the executor can submit a policy-qualified adopted-order cancel or amendment
  through the normal execution pipeline. Pending or unknown adopted modify state
  blocks execution and reports the reconciliation action, rollback policy state,
  and rollback blocker before any retry.

This means enabling execution without also configuring appropriate risk limits
is intentionally restrictive.

## Intervention And Remediation Options

Default intervention config:

- `operator_api.enabled`: `false`
- `operator_api.operator_token`: `null`
- `remediation_orchestrator.enabled`: `false`
- `operator_review_acknowledgement_enabled`: `true`
- `order_adoption_acknowledgement_enabled`: `false`
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
- `automated_remediation_runner.enabled`: `false`
- `automated_remediation_runner.interval_millis`: `30000`
- `automated_remediation_runner.initial_delay_millis`: `30000`
- `automated_remediation_runner.publish_decisions`: `true`
- `automated_remediation_runner.execute_remediation`: `true`
- `automated_remediation_runner.require_target_reconciliation_confidence`: `true`
- `automated_remediation_runner.target.provider`: `null`
- `automated_remediation_runner.target.environment`: `null`
- `automated_remediation_runner.target.account`: `null`
- `automated_remediation_runner.target.market`: `null`
- `remediation_executor_policy.enabled`: `false`
- `remediation_executor_policy.exchange_execution_enabled`: `false`
- `remediation_executor_policy.report_only`: `true`
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
- `remediation_executor_policy.position_order_policy.one_way_reduce_only_enabled`:
  `false`
- `remediation_executor_policy.position_order_policy.provider`: `binance`
- `remediation_executor_policy.position_order_policy.market`: `usdm_futures`
- `remediation_executor_policy.position_order_policy.position_side`: `BOTH`
- `remediation_executor_policy.position_order_policy.order_type`: `MARKET`
- `remediation_executor_policy.position_order_policy.require_reduce_only`:
  `true`
- `remediation_executor_policy.position_order_policy.require_close_position_false`:
  `true`
- `remediation_executor_policy.position_order_policy.hedge_mode_execution_enabled`:
  `false`
- `remediation_executor_policy.position_order_policy.hedge_position_order_enabled`:
  `false`
- `remediation_executor_policy.position_order_policy.allowed_symbols`: empty
  list
- `remediation_executor_policy.position_order_policy.max_position_quantity`:
  `null`
- `remediation_executor_policy.position_order_policy.chunk_close_when_max_quantity_exceeded`:
  `false`
- `remediation_executor_policy.position_order_policy.max_position_notional`:
  `null`
- `remediation_executor_policy.position_order_policy.reject_unbounded_position_notional`:
  `true`
- `remediation_executor_policy.position_order_policy.required_margin_type`:
  `null`
- `remediation_executor_policy.position_order_policy.required_position_mode`:
  `HEDGE`
- `remediation_executor_policy.position_order_policy.min_leverage`: `null`
- `remediation_executor_policy.position_order_policy.max_leverage`: `null`
- `remediation_executor_policy.position_order_policy.max_account_position_notional`:
  `null`
- `remediation_executor_policy.position_order_policy.max_symbol_position_notional`:
  `null`
- `remediation_executor_policy.position_order_policy.max_account_unrealized_loss`:
  `null`
- `remediation_executor_policy.position_order_policy.max_symbol_unrealized_loss`:
  `null`
- `remediation_executor_policy.position_order_policy.min_account_margin_balance`:
  `null`
- `remediation_executor_policy.position_order_policy.max_account_margin_drawdown_fraction`:
  `null`
- `remediation_executor_policy.position_order_policy.max_account_margin_utilization`:
  `null`
- `remediation_executor_policy.position_order_policy.max_account_daily_realized_loss`:
  `null`
- `remediation_executor_policy.position_order_policy.max_symbol_daily_realized_loss`:
  `null`
- `remediation_executor_policy.position_order_policy.reject_missing_account_risk_metadata`:
  `true`
- `remediation_executor_policy.managed_order_amendment_policy.enabled`:
  `false`
- `remediation_executor_policy.managed_order_amendment_policy.provider`:
  `binance`
- `remediation_executor_policy.managed_order_amendment_policy.market`:
  `usdm_futures`
- `remediation_executor_policy.managed_order_amendment_policy.allow_bot_created_orders`:
  `true`
- `remediation_executor_policy.managed_order_amendment_policy.allow_adopted_orders`:
  `false`
- `remediation_executor_policy.managed_order_amendment_policy.allowed_symbols`:
  empty list
- `remediation_executor_policy.managed_order_amendment_policy.allowed_order_types`:
  `LIMIT`
- `remediation_executor_policy.managed_order_amendment_policy.allowed_fields`:
  `PRICE`, `QUANTITY`
- `remediation_executor_policy.managed_order_amendment_policy.allow_quantity_increase`:
  `false`
- `remediation_executor_policy.managed_order_amendment_policy.allow_quantity_decrease`:
  `true`
- `remediation_executor_policy.managed_order_amendment_policy.max_quantity_increase_fraction`:
  `null`
- `remediation_executor_policy.managed_order_amendment_policy.max_quantity_decrease_fraction`:
  `null`
- `remediation_executor_policy.managed_order_amendment_policy.max_price_drift_fraction`:
  `null`
- `remediation_executor_policy.managed_order_amendment_policy.cancel_replace_on_unsupported_change`:
  `false`
- `remediation_executor_policy.managed_order_amendment_policy.reject_stale_projection`:
  `true`
- `remediation_executor_policy.managed_order_amendment_policy.max_projection_age_millis`:
  `null`
- `remediation_executor_policy.managed_order_amendment_policy.require_open_order_status`:
  `true`
- `remediation_executor_policy.managed_order_amendment_policy.require_exchange_order_id`:
  `false`
- `remediation_executor_policy.managed_order_amendment_policy.allowed_statuses`:
  `ACCEPTED`, `PARTIALLY_FILLED`
- `remediation_executor_policy.adopted_order_lifecycle_policy.enabled`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.provider`:
  `binance`
- `remediation_executor_policy.adopted_order_lifecycle_policy.market`:
  `usdm_futures`
- `remediation_executor_policy.adopted_order_lifecycle_policy.preserve_by_default`:
  `true`
- `remediation_executor_policy.adopted_order_lifecycle_policy.allow_cancel`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.allow_amend`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.allow_replace`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.rollback_on_ambiguous_outcome`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.reject_stale_projection`:
  `true`
- `remediation_executor_policy.adopted_order_lifecycle_policy.max_projection_age_millis`:
  `null`
- `remediation_executor_policy.adopted_order_lifecycle_policy.require_open_order_status`:
  `true`
- `remediation_executor_policy.adopted_order_lifecycle_policy.require_exchange_order_id`:
  `false`
- `remediation_executor_policy.adopted_order_lifecycle_policy.reject_pending_or_unknown_modify`:
  `true`
- `remediation_executor_policy.adopted_order_lifecycle_policy.allowed_symbols`:
  empty list
- `remediation_executor_policy.adopted_order_lifecycle_policy.allowed_statuses`:
  `ACCEPTED`, `PARTIALLY_FILLED`

The executor policy defaults describe a safe startup state. Demo-live exchange
execution is a runtime override state: set `enabled=true`,
`exchange_execution_enabled=true`, `report_only=false`, and allow the exact
operation, currently `CANCEL_ORDER`, `CLOSE_POSITION`, `REDUCE_POSITION`, or
`AMEND_ORDER`, plus `position_order_policy.one_way_reduce_only_enabled=true` for
one-way position remediation and
`managed_order_amendment_policy.enabled=true` for bounded managed-order
amendments. For the checked-in demo runtime, position remediation is
also restricted to `allowed_symbols=["BTCUSDT","ETHUSDT"]`, `max_position_quantity=0.001`,
`chunk_close_when_max_quantity_exceeded=true`, `max_position_notional=250`,
`required_margin_type=cross`, `min_leverage=1`, and `max_leverage=5` before
using
`POST /internal/interventions/remediation/executor/execute`.
The checked-in demo runtime also enables
`adopted_order_lifecycle_policy.enabled=true`, `allow_cancel=true`,
`allow_amend=true`, `allowed_symbols=["BTCUSDT","ETHUSDT"]`, and
`max_projection_age_millis=30000`, while inheriting `allow_replace=false`, so
adopted BTCUSDT or ETHUSDT orders can be automatically canceled or amended only
through the same executor, risk gate, and pipeline. Adopted replace and
exchange-executable rollback are not enabled; ambiguous adopted modify state is
reconciled and re-previewed before any new command.

Managed order amendment planning and execution are also configurable. An
`AMEND` remediation decision can be policy-qualified or blocked by
`managed_order_amendment_policy`, including allowed fields, order type, symbol,
ownership, drift limits, and stale projection age. When qualified, the executor
builds a `MODIFY` command with projected side/order type and requested or
retained price/quantity, then submits it through the normal order execution
pipeline. If a previous `MODIFY` is still pending or produced an unknown result,
the planner blocks another amendment until reconciliation refreshes the target.
Adopted-order amendments must also pass `adopted_order_lifecycle_policy`; if the
adopted target itself is pending or unknown after a modify, the executor report
uses `executor_ambiguous_outcome_action=reconcile_projection_then_repreview` and
includes any rollback blocker.
Cancel/replace fallback remains unimplemented, so unsupported amendment shapes
are blocked with an explicit fallback blocker instead of replaced.
Executor preview and execute reports include `executor_plan_summary`,
`executor_target_summary`, and `executor_policy_summary` attributes so the
operator API exposes the evaluated plan, target, and policy state without log
parsing. If a pending, unknown, or ambiguous exchange state is involved, reports
also include `executor_ambiguous_outcome_detected=true`,
`executor_ambiguous_outcome_reason`, and
`executor_ambiguous_outcome_action=reconcile_before_retry`.

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
run with `max_decisions_per_run`. It still does not send exchange commands by
itself; it records the selected automated decision so the command planner and
executor can evaluate it.

The automated remediation runner can run this workflow without an operator API
call when `automated_remediation_runner.enabled=true`. Each tick resolves the
configured target, or the active target when the target fields are `null`,
requires target-level reconciliation confidence when
`automated_remediation_runner.require_target_reconciliation_confidence=true`,
executes already-projected eligible remediation decisions first, and then
publishes new automated decisions for the next projected tick. This avoids
racing asynchronous event consumers and prevents restart resume before the
projection and exchange observations are reconciled, while still allowing
unattended demo-live remediation once the executor policy is configured for
exchange execution.

Current automated remediation execution state:

- The bot can convert remediation recommendations into remediation decisions
  when `automated_decision_service.enabled=true`.
- The bot can run automated remediation on a schedule when
  `automated_remediation_runner.enabled=true`.
- The scheduled runner executes already-projected remediation decisions before
  publishing new automated decisions, so newly published decisions are executed
  after they are projected by the event pipeline.
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
- Recent pause activation, pause release, explicit pause override, and pause
  expiry audit records can be queried through the operator API.
- Order `CLOSE` becomes an exchange-executable `CANCEL_ORDER` plan with the
  projected target order identity.
- One-way position `CLOSE` and bounded one-way position `REDUCE` become
  exchange-executable position intents with bounded sizing metadata and an
  `order_execution_pipeline` execution path.
- Position `CLOSE` targets the full projected absolute position amount and
  submits a `MARKET` order on the opposite side with `reduceOnly=true` and
  `closePosition=false` when executor policy allows `CLOSE_POSITION`.
- Position `REDUCE` requires explicit `reduce_quantity` or `reduce_fraction`
  decision attributes, and the planner rejects missing, invalid, or oversized
  reduce requests as `INSUFFICIENT_DATA`; eligible reduce submissions use the
  same reduce-only market-order path when executor policy allows
  `REDUCE_POSITION`.
- Hedge-mode position `CLOSE` and bounded `REDUCE` use the same sizing rules but
  submit `MARKET` orders with `positionSide=LONG` or `SHORT`,
  `reduceOnly=false`, and `closePosition=false` when
  `hedge_mode_execution_enabled=true` and projected position metadata proves
  the account position mode matches `required_position_mode=HEDGE`.
- Position `HEDGE` and `HEDGE_OR_REPLAN` can construct opposite-position-side
  hedge-mode `MARKET` orders only when both `hedge_mode_execution_enabled=true`
  and `hedge_position_order_enabled=true`, and the projected account position
  mode matches `required_position_mode=HEDGE`. These commands use
  `reduceOnly=false`, `closePosition=false`, and
  `position_execution_mode=hedge_mode_position_side_hedge`.
- Position close/reduce plans remain non-executable when the projected symbol is
  not in `allowed_symbols`, the target remediation quantity exceeds
  `max_position_quantity`, the estimated mark-price notional exceeds
  `max_position_notional`, or notional cannot be computed while
  `reject_unbounded_position_notional=true`. For `CLOSE` only,
  `chunk_close_when_max_quantity_exceeded=true` converts an oversized full close
  into a capped close chunk; explicit `REDUCE` decisions still block when they
  exceed `max_position_quantity`.
- Position close/reduce plans remain non-executable when a configured
  `required_margin_type`, `min_leverage`, or `max_leverage` does not match the
  projected futures account metadata. If the metadata is missing and
  `reject_missing_account_risk_metadata=true`, the plan is also blocked.
- Position close/reduce/hedge plans remain non-executable when
  `max_account_position_notional` or `max_symbol_position_notional` is
  configured and the projected gross account or symbol position notional would
  exceed the configured cap without reducing the current exposure. The planner
  uses projected open positions and mark prices for this check, so risk-reducing
  close/reduce plans can still proceed even if current exposure is already above
  the cap. If required mark-price data is missing or invalid and
  `reject_unbounded_position_notional=true`, the plan is blocked.
- Position hedge plans remain non-executable when
  `max_account_unrealized_loss` or `max_symbol_unrealized_loss` is configured
  and current projected open-position unrealized loss is already above the
  configured cap. Risk-reducing close/reduce plans can still proceed so the bot
  can lower exposure instead of freezing loss reduction. If unrealized PnL is
  missing or invalid and `reject_missing_account_risk_metadata=true`, the plan
  is blocked.
- Position hedge plans remain non-executable when `min_account_margin_balance`
  is configured and projected account margin balance is below the configured
  floor. Risk-reducing close/reduce plans can still proceed when account risk
  metadata is valid. If account risk or margin balance metadata is missing or
  invalid and `reject_missing_account_risk_metadata=true`, the plan is blocked.
- Position hedge plans remain non-executable when
  `max_account_margin_drawdown_fraction` is configured and projected account
  margin balance has drawn down from the stored max margin-balance high-watermark
  by more than the configured fraction. Risk-reducing close/reduce plans can
  still proceed when account risk metadata is valid. The projection persists
  `maxMarginBalance` in snapshots so this high-watermark survives restart. If
  account risk, margin balance, or max margin-balance metadata is missing or
  invalid and `reject_missing_account_risk_metadata=true`, the plan is blocked.
- Position close/reduce plans remain non-executable when
  `max_account_margin_utilization` is configured and the projected account
  maintenance-margin-to-margin-balance ratio exceeds that cap. If account
  margin risk metadata is missing or invalid and
  `reject_missing_account_risk_metadata=true`, the plan is also blocked. The
  checked-in demo runtime leaves this cap unset until account-level risk
  projection is configured for the target.
- Execution reports can contribute realized PnL into projection state when they
  carry `realizedProfit`, `realizedPnl`, or `realized_pnl` attributes. The bot
  accumulates those values per UTC trading day at account and symbol scope, then
  persists them in file/JDBC projection snapshots. When
  `max_account_daily_realized_loss` or `max_symbol_daily_realized_loss` is
  configured, position hedge plans remain non-executable once the matching
  current UTC daily realized loss exceeds that cap. Risk-reducing close/reduce
  plans can still proceed. If daily realized PnL state is missing or invalid and
  `reject_missing_account_risk_metadata=true`, additive hedge plans are blocked.
  The checked-in demo runtime leaves these caps unset until daily loss limits
  are calibrated for the target.
- Hedge-mode plans remain non-executable when projected position-mode metadata
  is missing or does not match `required_position_mode`; the catalog default is
  `HEDGE`.
- Position `HEDGE` and `HEDGE_OR_REPLAN` default to the projected absolute
  position amount and mark `hedge_mode_required=true`.
- Hedge-mode close/reduce and hedge-order execution remain disabled in the
  checked-in demo runtime until that runtime explicitly opts into the relevant
  hedge policies.
- `PAUSE_SYMBOL`, `PAUSE_ACCOUNT`, `IGNORE`, and `REPLAN_FROM_PROJECTION` are
  governance or planning intents, not exchange commands yet. `ADOPT` is also not
  an exchange command; when `order_adoption_acknowledgement_enabled=true`, an
  order-scope `ADOPT` decision for a matching external order publishes an
  auditable acknowledgement with adoption metadata. Projection replay then marks
  that order as bot-managed and clears the external intervention.

As of this version, external order `CLOSE`, position `CLOSE`, bounded position
`REDUCE`, and managed order `AMEND` remediation can become
`exchangeExecutable=true` when the executor and matching operation policy are
enabled. External order `ADOPT` can become a projection ownership transfer when
its orchestrator switch is enabled. Hedge-position command construction also
exists behind explicit hedge-mode policy switches.

The remediation executor policy is the configuration boundary for the future
executor. It is disabled by default and cannot allow exchange execution unless
the policy is enabled, `report_only=false`, at least one operation is explicitly
allowlisted, and the strict ready-plan, fresh-projection, target-identity, and
managed-pipeline gates remain enabled. `allow_real_environment=false` means the
executor must still refuse real-environment exchange execution unless a real
deployment deliberately overrides that guard.

The current codebase includes a remediation executor service. It consumes
persisted remediation decisions, regenerates current command plans, applies the
executor policy gates, caps each batch with `max_plans_per_run`, and returns
per-plan reports with statuses such as blocked, preview, submitted, or
no-action. Preview reports are exposed through
`GET /internal/interventions/remediation/executor/preview`. Policy-gated
execution is exposed through
`POST /internal/interventions/remediation/executor/execute` and currently
supports external-order close as cancel, one-way position close/reduce,
config-gated hedge-mode position close/reduce or hedge orders, and bounded
managed-order amendment when the matching executor policies allow them. Preview
and execute evaluations emit `trading.remediation_executor.outcome.events` so
operators can alert on blocked, preview-only, submitted, no-action, and disabled
executor outcomes without parsing logs.

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
- Daily realized PnL state by provider, environment, account, market, and UTC
  trading day.

These capabilities are useful for demos and operator workflows only when events
are being published into the system.

## Supported Scenarios

### Scenario: Start Without Trading

Use this when you want to verify startup and config only.

Command:

```bash
./gradlew :bot-app:bootRun
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
- Use the catalog-provided USD-M stream baseline, or intentionally override
  `market_data.streams` for a target-specific stream set.
- Ensure event bus dependencies are available.

Expected result:

- The bot opens configured public streams.
- Market data is normalized and published.
- If metadata-derived streams are enabled, later metadata refreshes can rebalance
  the public websocket subscription without changing demo/real code.

### Scenario: Run REST Reconciliation

Use this when you want the bot to compare REST snapshots with projected state.

Config changes:

- Enable `reconciliation.runtime_enabled`.
- Enable specific snapshot families.
- Use catalog-provided USD-M symbol coverage for open orders, or intentionally
  override symbol lists for target-specific snapshots such as order history and
  trades.

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
- Remediation executor reports mark ambiguous cases with
  `executor_ambiguous_outcome_detected=true` and instruct reconciliation before
  retry instead of submitting another command.

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
8. Start the app with `./gradlew :bot-app:bootRun`.
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
- `docs/binance-live-smoke.md` for the focused Binance live smoke-test guide.
- `docs/architecture.md` for module boundaries, config precedence, and delivery
  standards.
