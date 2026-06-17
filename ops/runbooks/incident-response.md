# Incident Response Runbook

This runbook covers live-profile incident response for trading-bot. It applies
to both `demo` and `real`; the code path is the same. Environment differences
must be limited to runtime configuration, credentials, endpoints, provider
availability, deployment approvals, alert receivers, and calibrated risk limits.

The objective of an incident response is to preserve capital, preserve evidence,
keep the bot's event/projection/reconciliation state coherent, and resume only
when automation can safely manage orders and positions again. Do not start a
second bot instance against the same provider/environment/account/market.

## Common Triage

Use this sequence for every production incident:

1. Identify target identity: provider, environment, account, market, optional
   symbol, Cloud Run service, revision, commit SHA, and runtime config artifact.
2. Preserve evidence before changing state: logs, alerts, metrics, Cloud Run
   revision labels, traffic split, rendered config checksum, deployment
   workflow artifacts, projection snapshot status, reconciliation observations,
   open orders, open positions, pause governance, strategy lifecycle, and
   remediation executor preview reports.
3. Check readiness and liveness, then verify that the running revision matches
   the expected commit SHA.
4. Check user-data stream freshness, market-data stream freshness,
   reconciliation confidence, journal health, and projection persistence.
5. Check `GET /internal/strategy/lfa/lifecycle` when the strategy runner and
   operator API are enabled.
6. Check `GET /internal/interventions/remediation/executor/preview` for the
   affected target before any execute-mode remediation.
7. Prefer `DRAINING` when the bot is coherent and can safely stop new entries;
   prefer `EMERGENCY_STOP` when new risk must stop immediately or state
   confidence is degraded.
8. Resume only after reconciliation, projection, risk gates, strategy lifecycle,
   and remediation executor reports agree with the intended target state.

## Severity

- `SEV1`: possible capital loss, unmanaged real exposure, unknown exchange
  outcome, degraded reconciliation with open exposure, credential compromise, or
  automation submitting unintended exchange commands.
- `SEV2`: demo capital at risk, failed deployment or rollback, stale user-data
  stream, stale market data, persistence degradation, alerting unavailable, or
  repeated remediation executor blockers.
- `SEV3`: non-critical dashboard, documentation, cost, or alert-noise issue with
  no unmanaged exchange exposure.

Escalate the severity if the incident affects `real`, has open positions, has
open orders, or has uncertain exchange outcome.

## Exchange Outage Or Binance Connectivity Failure

Signals:

- REST order, account, exchange-info, or position calls time out or fail.
- Binance server-time smoke fails.
- User-data stream listen-key keepalive fails.
- Market-data WebSocket reconnects repeatedly or misses freshness thresholds.

Response:

1. Move the strategy lifecycle to `DRAINING` if the operator API is healthy and
   reconciliation is still confident. Move to `EMERGENCY_STOP` if state
   confidence is degraded or order outcomes are unknown.
2. Keep new entry signal publication blocked while connectivity is unstable.
3. Do not retry order submission blindly. Unknown or timed-out submissions must
   be reconciled before another command targets the same order or position.
4. Preserve the latest exchange error payloads and timeout timestamps.
5. Wait for fresh REST reconciliation and user-data stream updates before
   allowing any risk-adding automation.
6. Permit cancel, close, or reduce remediation only when the executor preview is
   not ambiguous, the operation is policy-allowed, and the risk gate accepts the
   command through `OrderExecutionPipeline`.

Exit criteria:

- REST and WebSocket live checks pass for the target.
- Reconciliation confidence is healthy.
- No unknown order result or pending modify blocker remains for the affected
  target.
- Strategy lifecycle is intentionally left in `PAUSED`, `DRAINING`, `STOPPED`,
  or moved back to `ACTIVE` only through normal policy-gated controls.

## Stale User-Data Stream

Signals:

- Account, order, or position updates are stale or absent.
- Reconciliation confidence degrades because user-data freshness is missing.
- Projection does not reflect recent exchange-side fills or cancels.

Response:

1. Stop new entries by moving the strategy lifecycle to `DRAINING` or
   `EMERGENCY_STOP` depending on exposure and confidence.
2. Treat projected order and position state as incomplete until REST
   reconciliation refreshes it.
3. Do not execute remediation that depends on the stale stream unless REST
   reconciliation has made the target current and the executor preview is safe.
4. Preserve listen-key events, reconnect counts, and the last received event
   timestamp.
5. Restart the stream through the configured runtime path; do not run a parallel
   bot instance to obtain another stream.

Exit criteria:

- A fresh user-data event or REST reconciliation observation exists after the
  incident window.
- Projection agrees with exchange open orders and positions.
- Remediation executor reports are no longer blocked by stale projection or
  reconciliation reasons.

## Stale Market-Data Stream

Signals:

- Top-of-book, aggregate trade, or kline projection age exceeds configured
  freshness thresholds.
- Strategy instrument-universe admission blocks on missing or stale market data.
- Spread/depth/quote-volume cannot be evaluated for candidate symbols.

Response:

1. Keep strategy signal publication blocked until projected market data is fresh
   for the configured warm-up threshold.
2. Do not widen freshness thresholds to force trading through stale data.
3. Preserve stream names, symbols, last event time, and reconnect evidence.
4. Verify that catalog-owned stream defaults and runtime overrides still match
   the intended high-liquidity USD-M futures universe.
5. Resume signal publication only after the same universe intended for real is
   fresh in demo and the strategy lifecycle is explicitly allowed.

Exit criteria:

- Market-data projection freshness is within configured thresholds.
- Top-of-book bid/ask, spread, depth, and quote-volume gates can be evaluated.
- Strategy candidate ranking is based on current projection state.

## Reconciliation Degradation

Signals:

- Target reconciliation confidence is missing, stale, or degraded.
- Automated remediation runner skips decisions because target reconciliation is
  required.
- Strategy runner blocks with reconciliation-related reasons.

Response:

1. Keep new strategy entries blocked.
2. Keep remediation exchange execution blocked unless the specific operation can
   prove a current target through reconciliation and risk gates.
3. Force or wait for the configured reconciliation path rather than manually
   editing projection state.
4. Inspect open orders, open positions, balances, margin state, realized PnL,
   and account risk metadata.
5. If real exposure is open and reconciliation cannot recover, use exchange UI
   only as the last-resort emergency control, then let the bot detect the
   external intervention and evaluate it through automated policy.

Exit criteria:

- Reconciliation observations are current for every active target used by order
  admission, remediation, and strategy publication.
- Projection and exchange state agree for open orders, positions, balances, and
  account risk metadata.
- No unresolved stale-projection or insufficient-data executor blockers remain.

## External Order Or Position Detected

Signals:

- The bot projects an order or position that was created outside the bot.
- External-intervention recommendations are created for orders or positions.
- The target has no bot command id or is marked adopted/external.

Response:

1. Do not ignore the external object. The bot must evaluate risk automatically.
2. Confirm target identity, symbol, side, quantity, notional, leverage, margin
   type, unrealized PnL, and liquidation/margin risk when available.
3. Let automated policy classify the target and publish remediation decisions
   when configured.
4. Use executor preview to verify whether the safest policy action is preserve,
   adopt, cancel, amend, reduce, close, or no action.
5. Execute only through the normal pipeline. External-order cancel, adopted-order
   amendment, position close, and position reduce must pass executor policy,
   projection freshness, reconciliation confidence, idempotency, and
   `OrderRiskGate`.
6. Preserve any user-side reason for the intervention if known, but do not
   require manual review as the only control path.

Exit criteria:

- The external object is either safely preserved, adopted into projection,
  cancelled, amended, reduced, closed, or intentionally left as no-action by
  policy.
- Projection and exchange state agree after the action.
- Any account/symbol pause or lifecycle state reflects the remaining risk.

## Unknown Order Result Or Ambiguous Modify Outcome

Signals:

- Exchange submission or modify result is unknown.
- Projection contains pending or unknown modify state.
- Executor preview reports `executor_ambiguous_outcome_detected=true`.

Response:

1. Do not retry the same action immediately.
2. Keep new commands against the affected order blocked until reconciliation
   resolves the command outcome.
3. Inspect command id, idempotency key, client order id, exchange order id,
   order action, and projection target state.
4. Wait for user-data or REST reconciliation to prove whether the command
   succeeded, failed, partially filled, remained open, or disappeared.
5. Re-preview the remediation executor after reconciliation. Execute only if the
   new report is not ambiguous and policy still permits the operation.

Exit criteria:

- Unknown or pending markers have been replaced by reconciled order state.
- Duplicate command protection remains intact.
- The target no longer has ambiguous-outcome blockers.

## Failed Deployment, Failed Smoke, Or Bad Runtime Config

Signals:

- GitHub deploy, smoke, or rollback workflow fails.
- Cloud Run readiness is not `UP`.
- Running revision label or image tag does not match the requested full commit
  SHA.
- Runtime configuration does not match the intended environment.

Response:

1. Do not enable strategy or remediation execution because deployment completed
   partially.
2. Preserve workflow artifacts, revision labels, image digest, rendered runtime
   config, and Cloud Run logs.
3. Roll back only to an existing revision whose service, environment, app label,
   commit label, and image tag match the explicit rollback inputs.
4. After rollback, verify readiness and then check projection,
   reconciliation confidence, open orders, open positions, strategy lifecycle,
   and executor reports.
5. Treat a bad config that could widen execution policy, risk limits, or real
   environment permissions as `SEV1`.

Exit criteria:

- The running revision matches the intended commit and environment.
- Readiness smoke is `UP`.
- Runtime policy is conservative until state checks are complete.

## Persistence, Journal, Or Cloud SQL Failure

Signals:

- Projection JDBC writes or reads fail.
- Journal append or replay fails.
- Pause-governance audit store fails.
- Cloud SQL instance, credentials, or socket connectivity fails.

Response:

1. Keep side-effecting automation disabled if event persistence or projection
   state cannot be trusted.
2. Follow `ops/runbooks/persistence-recovery.md` for restore and replay.
3. Preserve database errors, Cloud SQL events, journal archive status, applied
   event id status, and backup identifiers.
4. Do not compact or delete journal/projection data during the incident.
5. Resume only after projection load, journal replay, REST reconciliation, and
   duplicate command suppression are proven healthy.

Exit criteria:

- Projection and journal state can be read and written.
- Applied event ids are present.
- Backup/restore evidence is attached if a restore occurred.
- Reconciliation confidence is healthy before order admission resumes.

## Alerting Unavailable

Signals:

- Alertmanager render fails.
- Alert receiver secret versions are missing.
- PagerDuty or Slack delivery fails.
- Prometheus or dashboard data is unavailable during an incident.

Response:

1. Treat real promotion as blocked while alerting is unavailable.
2. Fix Secret Manager receiver values through the deployment-owned path; do not
   commit webhooks, routing keys, or tokens.
3. Render the Alertmanager profile again and verify no unresolved placeholders
   remain.
4. Preserve the rendered config checksum and delivery-test evidence.
5. Use direct workflow/operator checks while alert delivery is degraded.

Exit criteria:

- Alertmanager renders from Secret Manager for the target environment.
- Operator and platform receivers receive test alerts.
- Incident evidence includes alert timeline or documented alert outage window.

## Credential Compromise Or Rotation

Signals:

- Binance API key, Google Cloud service-account credential, GitHub token,
  operator token, database password, PagerDuty routing key, or Slack webhook is
  suspected exposed or due for rotation.

Response:

1. Treat suspected exposure as `SEV1` for real and at least `SEV2` for demo.
2. Move strategy lifecycle to `EMERGENCY_STOP` if compromised exchange
   credentials may submit orders.
3. Disable or revoke the compromised credential at the provider first when
   immediate risk exists.
4. Add a new Secret Manager version for the replacement value. Do not edit
   source-controlled contracts to hold the value.
5. Redeploy or restart the runtime so it consumes the intended secret version.
6. Verify Binance account state, Cloud Run revision, GitHub environment
   secrets/variables, IAM bindings, and Alertmanager rendering after rotation.
7. Record old secret version disabled time and new version activation time.

Exit criteria:

- Compromised secret is revoked or disabled.
- Runtime uses the replacement secret version.
- Exchange account has no unexplained orders, positions, withdrawals, or API key
  permission changes.

## Cost Or Budget Spike

Signals:

- Google Cloud budget alert fires.
- Cloud Run, Cloud SQL, Artifact Registry, Cloud Logging, or network cost grows
  unexpectedly.
- Scaling behavior differs from the deployment contract.

Response:

1. Confirm the running Cloud Run min/max instances, CPU, memory, timeout, and
   region match the environment variables configured for the deployment.
2. Check log volume, failed restart loops, repeated workflow dispatches,
   artifact retention, Cloud SQL tier/storage, and unexpected egress.
3. Do not scale down a live target in a way that risks losing reconciliation,
   user-data, or market-data continuity without first moving lifecycle to a safe
   state.
4. If cost pressure requires stopping, use controlled drain or emergency stop,
   then preserve evidence and scale only after exposure is understood.

Exit criteria:

- The cost driver is identified.
- Runtime sizing or retention is intentionally adjusted through configuration or
   deployment settings.
- No open exposure was left unmanaged by cost mitigation.

## Real Environment Rules

Real trading must remain disabled until demo has proven the same intended
strategy, remediation, symbol universe, lifecycle, recovery, alerting, and
rollback behavior. Real may use different credentials, endpoints, approvals,
provider availability, and calibrated risk values, but it must not use a forked
code path or a reduced demo behavior as promotion evidence.

During a real incident:

- Prefer capital preservation over fast resumption.
- Keep exchange execution disabled if reconciliation, projection, persistence,
  alerting, or credential confidence is incomplete.
- Use exchange UI only as emergency last resort when the bot cannot safely reduce
  risk itself; the resulting external actions must then be reconciled and
  governed by bot policy.
- Do not widen real operation allowlists or risk caps during an incident unless
  the change itself is the safest risk-reducing action and is captured as
  incident evidence.

## Evidence Bundle

Every incident must retain:

- Incident id, severity, owner, timestamps, environment, provider, account,
  market, and symbols.
- Commit SHA, Cloud Run service, revision, image tag/digest, deployment contract,
  and runtime config diff.
- GitHub workflow run ids and artifacts for publish, deploy, smoke, rollback, or
  failed attempts.
- Alert timeline, rendered Alertmanager checksum, and receiver delivery status.
- Market-data freshness, user-data freshness, reconciliation observations,
  projection snapshot status, journal status, and applied event id status.
- Open orders, open positions, balances, margin state, risk metadata, realized
  PnL, and pause governance.
- Strategy lifecycle state, allowed transitions, and drain readiness.
- Remediation executor preview or execute reports, including ambiguous-outcome
  attributes, target summary, plan summary, policy summary, blockers, command id,
  idempotency key, client order id, and exchange order id when available.
- Final decision, final state, residual risk, follow-up work, and promotion
  impact.

## Incident Review

After resolution:

1. Confirm the incident is closed only after exit criteria are met.
2. Add missing tests, runbooks, alerts, dashboards, or config validation that
   would have reduced the incident.
3. Update `plan.txt` if the incident changes remaining work or readiness.
4. Do not promote real execution if the same scenario has not passed in demo.
