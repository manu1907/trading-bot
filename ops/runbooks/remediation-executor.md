# Remediation Executor Runbook

This runbook covers the operational response for remediation executor alerts and
dashboard signals. It applies to both demo and real environments because the code
path is the same; only runtime config, credentials, endpoint, risk limits, and
deployment approval differ.

## Primary Signals

- Metric: `trading_remediation_executor_outcome_events_total`
- Alert rules: `ops/prometheus/remediation-executor-alerts.yml`
- Dashboard: `ops/grafana/remediation-executor-dashboard.json`
- Preview endpoint:
  `GET /internal/interventions/remediation/executor/preview`
- Execute endpoint:
  `POST /internal/interventions/remediation/executor/execute`

## Immediate Checks

1. Identify the alert labels: `provider`, `environment`, `account`, `market`,
   `operation`, `status`, and `reason`.
2. Query the executor preview endpoint for the same target before changing any
   runtime policy.
3. Inspect current remediation decisions and command plans through the operator
   API.
4. Check reconciliation confidence for the affected target before allowing a
   second execution attempt.
5. Confirm journal and projection persistence are healthy before relying on
   restart recovery.

## Status Handling

- `DISABLED`: expected during conservative startup. If automation should be
  active, verify `remediation_executor_policy.enabled`,
  `exchange_execution_enabled`, `report_only`, and `allowed_operations`.
- `BLOCKED`: inspect the `reason` label and the preview report. Do not widen
  policy until the blocker is understood and current projection still matches
  exchange state.
- `PREVIEW_ONLY`: execute-mode calls are not submitting. Verify whether
  `report_only=true`, `exchange_execution_enabled=false`, or preview mode was
  intentionally requested.
- `SUBMITTED_TO_PIPELINE`: confirm the related risk decision, order result,
  projection update, and reconciliation confidence. This status means the
  command entered the normal pipeline, not that exchange risk is fully resolved.
- `NO_ACTION`: verify whether reconciliation already removed the risk or whether
  decisions are stale/noisy.

## Critical Failure: Pipeline Submission Failed

When `reason=executor:pipeline_submission_failed`:

1. Stop repeated execute attempts for the affected target until reconciliation is
   current.
2. Query order and position projection state for the affected target.
3. Check user-data stream freshness and reconciliation confidence.
4. Check whether the command may have reached the exchange before the local
   failure was observed.
5. Resume only after projection and exchange state agree.

## Real Environment Rules

- Real trading must remain disabled until demo-live promotion gates pass.
- Real executor execution requires explicit real config, real credentials, real
  alert routing, deployment approval, and real operation allowlists.
- Never bypass the normal `OrderExecutionPipeline`, `OrderRiskGate`,
  idempotency, event journal, projection, reconciliation confidence, or provider
  gateway.
