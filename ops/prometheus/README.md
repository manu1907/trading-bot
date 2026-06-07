# Prometheus Operations

This directory contains Prometheus-compatible operational rules for the trading
bot.

Micrometer exports dotted meter names as Prometheus names by replacing dots with
underscores and adding `_total` for counters. For example,
`trading.pause_governance.activation.events` is scraped as
`trading_pause_governance_activation_events_total`.

## Pause Governance Alerts

`pause-governance-alerts.yml` defines alert rules for:

- Active account-level pauses.
- Active symbol-level pauses.
- Pause activation decisions.
- Failed pause release publication.
- Allowed and rejected pause overrides.
- Observed pause expiry transitions.
- Pause audit-store record or query failures.

The rules intentionally use low-cardinality labels and generic `routing_hint`
values. Deployment-specific routing, for example PagerDuty, Slack, email, or
Google Cloud Monitoring notification channels, should map `routing_hint` and
`severity` to the actual destination in the deployment layer.
