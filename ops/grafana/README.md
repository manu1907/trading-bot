# Grafana Operations

This directory contains Grafana dashboard artifacts for operating the trading
bot.

## Pause Governance Dashboard

`pause-governance-dashboard.json` is an importable Grafana dashboard for the
pause governance control plane.

It expects a Prometheus datasource and displays:

- Effective active pauses by scope.
- Pause activation rates.
- Pause release publication outcomes.
- Pause override evaluations.
- Pause expiry transition rates.
- Pause audit-store failures.

The dashboard intentionally uses aggregate, low-cardinality queries. Deployment
work should bind it to the production Prometheus datasource and pair it with the
Prometheus rules in `ops/prometheus/pause-governance-alerts.yml`.


## Runtime Readiness Dashboard

`runtime-readiness-dashboard.json` is an importable Grafana dashboard for the
active runtime readiness surface.

It expects a Prometheus datasource and displays:

- Runtime readiness state.
- Reconciliation confidence state.
- Active readiness blockers.
- Projection safety counts for unsafe orders, unresolved commands,
  interventions, pauses, and exposure.
- Projected market-data total, fresh, and stale symbol counts.

The dashboard intentionally uses aggregate, low-cardinality queries. It does not
tag or group by signal ids, order ids, exchange ids, or symbols. Pair it with
`ops/prometheus/runtime-readiness-alerts.yml` and the read-only
`/internal/runtime/status` endpoint.

## Remediation Executor Dashboard

`remediation-executor-dashboard.json` is an importable Grafana dashboard for the
remediation executor control plane.

It expects a Prometheus datasource and displays:

- Outcome rates by status.
- Blocked outcomes by operation and reason.
- Submitted command rates by operation.
- Preview-only execute-mode outcomes by reason.
- Disabled policy evaluations.
- No-action outcomes by operation.

The dashboard intentionally avoids remediation ids, order ids, and symbols so it
stays compatible with the low-cardinality metric contract.

## Strategy LFA Dashboard

`strategy-lfa-dashboard.json` is an importable Grafana dashboard for the LFA
signal-runner control surface.

It expects a Prometheus datasource and displays:

- LFA signal-runner outcomes by status.
- Published signal-runner outcomes.
- Blocked signal-runner outcomes.
- Blocked outcomes by reason and primary blocker.
- Lifecycle and reconciliation blockers.
- Budget and allocation blockers.
- Published outcomes by runtime target.
- Disabled evaluations.

The dashboard intentionally uses aggregate, low-cardinality queries. It does not
tag or group by signal ids, order ids, or symbols. Pair it with the Prometheus
rules in `ops/prometheus/strategy-lfa-alerts.yml`.
