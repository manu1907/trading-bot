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
values. `ops/alertmanager/pause-governance-alertmanager.yml` provides the
source-controlled Alertmanager routing profile for mapping `routing_hint` and
`severity` to operator/platform PagerDuty and Slack receivers. Deployment should
inject the real receiver secrets through the selected secret system.

## Remediation Executor Alerts

`remediation-executor-alerts.yml` defines alert rules for:

- Disabled executor policy evaluations.
- Blocked remediation plans.
- Pipeline submission failures.
- Commands submitted to the order execution pipeline.
- Repeated no-action outcomes.
- Execute-mode evaluations that remain preview-only.

The rules use the bounded labels exported by
`trading.remediation_executor.outcome.events`. Micrometer exposes that counter as
`trading_remediation_executor_outcome_events_total`.

The Alertmanager profile routes these alerts through the same `service`,
`routing_hint`, and `severity` labels used by pause governance alerts. The
operational response is documented in
`ops/runbooks/remediation-executor.md`.

## Strategy LFA Alerts

`strategy-lfa-alerts.yml` defines alert rules for:

- Disabled LFA signal-runner evaluations.
- Lifecycle-blocked runner evaluations.
- Reconciliation-blocked runner evaluations.
- Budget-blocked runner evaluations.
- Allocation-blocked runner evaluations.
- Published strategy signals.

The rules use the bounded labels exported by
`trading.strategy.lfa.signal_runner.run.events`. Micrometer exposes that counter
as `trading_strategy_lfa_signal_runner_run_events_total`.

These alerts are intentionally operational. They do not claim profitability.
They tell the operator whether the live strategy runner is disabled, blocked by
policy/risk/reconciliation, or publishing signals through the normal
provider-agnostic event path.
