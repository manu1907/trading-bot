# Persistence, Backup, And Recovery Runbook

This runbook covers the trading-event journal, trading-state projection stores,
pause-governance audit persistence, backup retention, restore drills, and
post-restore validation for live demo and live real profiles.

The bot uses the same codebase for demo and real. Demo and real may differ by
credentials, endpoint availability, risk limits, deployment approvals, and
retention targets, but the recovery workflow and post-restore safety gates must
stay equivalent.

## State Surfaces

- Trading event journal: append-only local Chronicle Queue records for typed
  trading events. The journal is the crash-recovery source for ordered event
  replay.
- Journal archive: deployment-owned object storage export using
  `TradingEventArchiveLayout` object names:
  `{prefix}/trading-events/v1/event_type={event-type}/topic={topic}/date={yyyy-MM-dd}/hour={HH}/{journal-index}.avrobin`.
- Projection snapshot store: current projected balances, positions, orders,
  risk state, realized PnL, manual-review decisions, remediation decisions,
  pause governance, strategy lifecycle, market data, and applied event ids.
- Pause-governance audit store: append-only audit evidence for pause/release
  decisions.
- Deployment logs, metrics, alerts, and live-validation evidence: operational
  evidence used to prove what happened around a restore or incident.

## Required Deployment Policy

Every cloud deployment contract must declare:

- JDBC projection persistence enabled for Cloud Run/ECS style deployments.
- File projection snapshots disabled for ephemeral container filesystems.
- Projection JDBC credentials bound through secrets.
- Projection retention days.
- Projection compaction policy.
- Projection automated backups and restore-drill interval.
- Journal archive destination, prefix, layout, and retention days.
- Pause-governance audit JDBC retention and backups.

Current policy defaults by deployment:

- Google Cloud demo: Cloud SQL PostgreSQL projection/audit, 180-day retention,
  at least 7 recovery days, 90-day restore drill, Cloud Storage journal archive.
- Google Cloud real: Cloud SQL PostgreSQL projection/audit, 365-day retention,
  at least 35 recovery days, 30-day restore drill, Cloud Storage journal
  archive.
- AWS demo: RDS PostgreSQL projection/audit, 180-day retention, at least 7
  recovery days, 90-day restore drill, S3 journal archive.

Schema creation and destructive retention jobs are deployment-owned. The bot
runtime must not run production schema migration or retention cleanup from the
hot trading process.

## Normal Startup Recovery

1. Start the service with the intended live profile and target runtime.
2. Load projection snapshot state first.
3. Replay the journal if `trading.journal.recovery.enabled=true`.
4. Keep live side-effecting components disabled during replay.
5. Start messaging and scheduled runtime loops only after recovery completes.
6. Require reconciliation confidence before order admission, remediation
   execution, or strategy signal publication can resume.

A recovery failure must fail startup. Partial recovery is not acceptable for a
trading process.

## Restore Procedure

Use this procedure after data loss, database corruption, bad deployment state,
or a failed restore drill.

1. Declare an incident or restore drill with timestamp, target, commit SHA,
   runtime profile, config artifact, and operator identity.
2. Stop or scale down the bot instance for the affected target.
3. Confirm no second bot instance is running against the same account/market.
4. Preserve current evidence before changing state: logs, metrics, journal
   directory, projection database dump if available, alert timeline, and latest
   deployment contract.
5. Restore the projection database from the selected managed database backup or
   point-in-time recovery target.
6. Restore or attach the journal archive segment needed to bridge the restored
   projection to the intended recovery point.
7. Confirm the restored projection table prefix matches the deployment contract.
8. Confirm applied event ids are present after restore. If they are missing,
   do not resume automation because duplicate command suppression cannot be
   trusted.
9. Start the bot with exchange execution disabled if the restore target is real
   or if restore confidence is incomplete.
10. Let startup load the projection and replay the journal.
11. Run REST reconciliation for open orders, account balances, account state,
    and positions.
12. Wait for reconciliation confidence to be healthy for the target.
13. Inspect remediation executor reports, strategy runner reports, and risk-gate
    blockers. Expected temporary blockers after restore include missing or
    degraded reconciliation confidence.
14. Resume automation only through normal config/operator controls after
    reconciliation and risk gates are healthy.
15. Record final evidence: restored backup id, recovery point, journal archive
    range, reconciliation observations, startup logs, smoke-test results, and
    operator decision.

## Post-Restore Acceptance Gates

The target is not recovered until all applicable checks pass:

- Application starts without journal or projection exceptions.
- Projection state contains expected balances, positions, orders, risks,
  realized PnL, pause governance, remediation decisions, strategy lifecycle,
  market data, and applied event ids.
- REST reconciliation has at least one current observation for every active
  target used by order admission, remediation execution, and strategy signal
  publication.
- No unresolved ambiguous managed-order or adopted-order lifecycle state is
  executable without reconciliation.
- Duplicate remediation command identity remains blocked after restart.
- Active pause governance still blocks order admission until released by policy.
- Strategy signal runner remains blocked until lifecycle, market-data warm-up,
  risk budget, and reconciliation gates all pass.
- Binance server-time, order endpoint, user-data stream, and market-data
  WebSocket live smoke checks pass for the selected profile.
- Alertmanager, Prometheus, and Grafana evidence is available for the recovery
  window.

## Compaction And Retention

Projection compaction is a deployment maintenance job, not a bot hot-path task.
It may remove obsolete historical rows only when all of these are true:

- The latest valid projection snapshot is preserved.
- Applied event ids needed for duplicate suppression are preserved.
- Open orders, positions, pauses, remediation decisions, strategy lifecycle, and
  current risk/PnL states are preserved.
- A fresh backup exists before compaction starts.
- A restore drill has passed within the deployment contract interval.
- The compaction job records before/after counts and retention cutoff evidence.

Journal retention must not delete hot local journal segments until the archive
export is confirmed durable and restorable. For live demo and real, retained
journal/archive windows must cover at least the declared deployment retention
period unless a stricter incident-hold policy applies.

## Restore Drill Evidence

Each drill must produce an evidence bundle containing:

- Deployment contract path and commit SHA.
- Backup id or point-in-time recovery timestamp.
- Journal archive prefix and restored index range.
- Projection row counts by table before and after restore.
- Bot startup log showing projection load and journal replay outcome.
- REST reconciliation observations after restore.
- Live smoke command results.
- Any remaining blockers and the decision to keep paused, resume demo, or defer
  real promotion.

## Failure Handling

If any acceptance gate fails:

- Keep exchange execution disabled or keep the target paused.
- Do not bypass reconciliation blockers manually.
- Preserve the failed restored state for investigation.
- Restore from an earlier backup or rebuild projection from the journal if the
  backup is suspected corrupt.
- Treat missing applied event ids, missing active orders, inconsistent positions,
  or unresolved ambiguous outcomes as blockers for autonomous trading.

The objective is not to restart quickly at any cost. The objective is to resume
only when restored state is coherent enough for the bot to manage orders,
positions, loss limits, risk reduction, and strategy automation safely.
