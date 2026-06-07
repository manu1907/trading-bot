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
