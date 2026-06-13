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

Executor reports include audit attributes that should be read before any policy
change or second execution attempt:

- `executor_plan_summary`: compact plan status, operation, exchange-executable
  flag, and evaluation mode.
- `executor_target_summary`: provider, environment, account, market, symbol,
  order target, position side, scope, and action when available.
- `executor_policy_summary`: enabled/execution/report-only/real-environment
  policy state at evaluation time.
- `executor_ambiguous_outcome_detected`: `true` when the plan or blocker
  indicates an unknown, pending-reconciliation, or ambiguous state.
- `executor_ambiguous_outcome_reason`: the blocker or reason that made the
  state ambiguous.
- `executor_ambiguous_outcome_action`: currently `reconcile_before_retry`.

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
6. Read `executor_plan_summary`, `executor_target_summary`, and
   `executor_policy_summary` from the preview report. These are the first-line
   audit facts for deciding whether the plan, target, and policy match the
   intended runtime.

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

## Unknown Or Ambiguous Outcomes

Use this procedure when a report contains
`executor_ambiguous_outcome_detected=true`, a blocker such as
`managed_order_amendment_modify_unknown_reconciliation_required`, a pending
modify blocker, an unknown order result, or an alert that references ambiguous
state.

1. Do not retry execute immediately. A second command can compound the unknown
   exchange state.
2. Read `executor_ambiguous_outcome_reason` and
   `executor_ambiguous_outcome_action`. Generic ambiguous outcomes use
   `reconcile_before_retry`; adopted-order lifecycle ambiguity uses
   `reconcile_projection_then_repreview`.
3. Query the latest projected order or position state for the target in
   `executor_target_summary`.
4. Check user-data stream freshness for the target account and market.
5. Check reconciliation confidence for the same provider, environment, account,
   and market.
6. Compare projected client/exchange order identity with the exchange state.
7. Wait for a fresh reconciliation observation or force a safe reconciliation
   cycle through the configured reconciliation path. Do not manually mark a
   command successful.
8. After projection and exchange state agree, preview the executor again.
9. Execute only if the new preview report is not ambiguous, the plan is still
   for the same intended target, and executor policy still allows the operation.

Expected safe outcomes:

- If reconciliation proves the previous command succeeded, the next preview
  should become `NO_ACTION` or produce a different stale/resolved blocker.
- If reconciliation proves the target is still open and safe to act on, the next
  preview can become exchange-executable again.
- If reconciliation remains degraded or absent, keep execution blocked and
  preserve the evidence in the incident notes.

Do not use cancel/replace as an implicit rollback. Cancel/replace fallback and
adopted-order ambiguous rollback are not executable behaviors in the current
codebase. If `executor_ambiguous_outcome_rollback_blocker` is present, preserve
it with the incident evidence; it explains whether rollback is disabled by
policy or blocked because executable rollback is not implemented.

## Hedge-Mode Remediation

Hedge-mode position remediation is config-gated and must not be treated as the
same operation as one-way reduce-only remediation.

Before enabling hedge-mode close/reduce:

1. Verify the runtime explicitly sets
   `position_order_policy.hedge_mode_execution_enabled=true`.
2. Verify projected account risk metadata proves
   `position_mode=HEDGE` for the target account and market.
3. Verify `position_order_policy.required_position_mode=HEDGE`.
4. Verify the target position side is `LONG` or `SHORT`, not `BOTH`.
5. Verify configured symbol, quantity, notional, leverage, margin-type,
   margin-balance, drawdown, utilization, unrealized-loss, and daily-loss caps
   are appropriate for the target environment.
6. Preview the executor and confirm the plan operation is `CLOSE_POSITION` or
   `REDUCE_POSITION`, the position side matches the projected exposure, and the
   plan is exchange-executable only because hedge-mode policy admitted it.
7. Execute only after reconciliation is confident and the preview target matches
   the intended external position.

Before enabling hedge orders:

1. Verify both `hedge_mode_execution_enabled=true` and
   `hedge_position_order_enabled=true`.
2. Verify the operation is intentionally `HEDGE_POSITION`, not a close or
   reduce action.
3. Treat hedge orders as risk-additive unless the configured exposure, margin,
   loss, and drawdown caps prove the resulting portfolio state is acceptable.
4. Use the smallest configured quantity/notional limits that still meet the
   remediation objective.
5. Keep hedge-order execution disabled in real until demo evidence proves the
   same behavior, symbol universe, reconciliation, and rollback procedures.

If hedge-mode metadata is missing or mismatched, expected blockers include
position-mode, margin, leverage, or policy mismatch reasons. Do not override
those blockers without first fixing projection/reconciliation or runtime config.

## Audit Evidence To Keep

For every blocked, ambiguous, preview-only, or submitted remediation incident,
record:

- Alert labels and timestamp.
- `executor_plan_summary`.
- `executor_target_summary`.
- `executor_policy_summary`.
- `executor_reason` and full report reasons.
- `exchange_execution_blocker` when present.
- `executor_ambiguous_outcome_*` attributes when present.
- Relevant remediation id, command id, idempotency key, client order id, and
  exchange order id.
- Reconciliation status before and after the incident.
- Final projection state and exchange state.
- Whether the incident happened in demo or real, and the deployment commit.

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
