# Google Cloud Operations Runbook

This runbook covers operating trading-bot on Google Cloud for the live profile.
It applies to both `demo` and `real`; the application code path is the same.
Only runtime configuration, credentials, endpoints, risk limits, alert receivers,
deployment approvals, and provider product availability may differ.

## Scope

This runbook covers:

- Google Cloud prerequisite bootstrap.
- GitHub environment setup for guarded deployment workflows.
- Image publication.
- Cloud Run deployment.
- Private readiness smoke.
- Alertmanager rendering.
- Rollback.
- Emergency stop and controlled drain posture.
- Incident evidence.
- Real-promotion constraints.

It does not replace the remediation executor runbook, the persistence recovery
runbook, or the scenario-specific incident response runbook. Use those runbooks
for remediation-specific ambiguous outcomes, backup/restore events, exchange
outages, stale streams, external interventions, credential events, alerting
outages, and cost incidents.

## Required Inputs

- A committed SHA on `main` whose `Security` workflow completed successfully.
- A local `gcloud` session authenticated to the target Google Cloud project.
- A local `gh` session authenticated to the GitHub repository if bootstrap
  GitHub environment automation is used.
- `api.env` with `BINANCE_DEMO_API_KEY` and `BINANCE_DEMO_API_SECRET` for demo.
- Real Binance credentials only when preparing real deployment.
- Alert receiver values when Alertmanager routing is required:
  PagerDuty routing keys, Slack webhooks, and Slack channels.
- Operator identity and timestamp for any deploy, rollback, incident, or
  promotion action.
- A live release evidence bundle produced with
  `ops/evidence/collect-live-release-evidence.sh` for every
  publish/deploy/smoke/rollback/promotion action, then completed with the actual
  live outcomes.
- For real promotion, a completed
  `ops/evidence/demo-burn-in-evidence-template.yml` showing that demo exercised
  the same intended real behavior.

## Bootstrap

Run bootstrap from a clean checkout:

```bash
set -a
source api.env
set +a
GITHUB_CONFIGURE_ENVIRONMENTS=true ./ops/google-cloud/bootstrap-deployment-prereqs.sh
```

Expected bootstrap outcomes:

- Required Google Cloud APIs enabled.
- Artifact Registry repository created or verified.
- Journal archive bucket created or verified.
- Cloud SQL PostgreSQL instance, demo database, real database, and database users
  created or verified.
- Secret Manager containers created or verified.
- Demo Binance key and secret versions added from `api.env`.
- Operator token and Cloud SQL JDBC secret versions generated when absent.
- Service accounts and IAM roles created or verified.
- GitHub OIDC Workload Identity Federation configured.
- Optional GitHub `demo` and `real` environments configured when
  `GITHUB_CONFIGURE_ENVIRONMENTS=true`.

If bootstrap reports missing alert secret values, deployment can still proceed
only for paths that do not require Alertmanager rendering. Alerting is not
production-ready until all required alert receiver secrets have enabled versions.

## Pre-Deploy Gate

Before publishing or deploying a commit:

1. Confirm the target commit SHA is a full 40-character SHA.
2. Confirm GitHub `Security` completed successfully for that SHA.
3. Confirm the target environment is either `demo` or `real`.
4. Confirm the GitHub environment has the required OIDC and Cloud Run secrets:
   `GCP_WORKLOAD_IDENTITY_PROVIDER`,
   `GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT`,
   `GCP_CLOUD_RUN_DEPLOY_SERVICE_ACCOUNT`,
   `GCP_CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT`,
   `GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT`, and
   `GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT`.
5. Confirm the GitHub environment has the required variables:
   `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ARTIFACT_REGISTRY_LOCATION`,
   `GCP_ARTIFACT_REGISTRY_REPOSITORY`, `GCP_CLOUD_RUN_CPU`,
   `GCP_CLOUD_RUN_MEMORY`, `GCP_CLOUD_RUN_MIN_INSTANCES`,
   `GCP_CLOUD_RUN_MAX_INSTANCES`, `GCP_CLOUD_RUN_TIMEOUT`, and
   `GCP_CLOUD_SQL_INSTANCE`.
6. Confirm Secret Manager has enabled versions for the secrets bound by the
   selected deployment contract.
7. For real, confirm demo promotion evidence exists and real exchange execution
   policies remain disabled unless explicitly approved by promotion gates.

## Publish Image

Use `.github/workflows/publish-google-cloud-image.yml`.

Inputs:

- `environment`: `demo` or `real`.
- `commit_sha`: full commit SHA to publish.

Expected evidence:

- Published Artifact Registry image tagged with the full commit SHA.
- Environment-specific image tag updated for the selected environment.
- Uploaded publish metadata artifact.
- OCI source/revision/version labels tied to the commit.
- Updated live release evidence bundle with the publish workflow run id and
  image digest.

Do not publish an unverified commit. The workflow checks `Security` success, but
operators should still record the source SHA and workflow run id.

Create or update the release evidence bundle after publish:

```bash
ops/evidence/collect-live-release-evidence.sh demo \
  --release-id demo-YYYYMMDD-N \
  --operator github-actions \
  --decision deploy \
  --security-workflow-run-id SECURITY_RUN_ID \
  --publish-workflow-run-id PUBLISH_RUN_ID \
  --artifact-image ARTIFACT_REGISTRY_IMAGE \
  --image-digest IMAGE_DIGEST
```

Use `real` instead of `demo` only for the real runtime contract. The script
records contract/config checksums and secret binding names without reading
secret values.

Archive completed evidence bundles with
`.github/workflows/archive-google-cloud-evidence.yml`. Supply the environment,
evidence type, evidence id, source workflow run id, and artifact name. The
workflow validates the bundle for secret-like content and stores it in the
versioned evidence bucket under
`gs://$GCP_EVIDENCE_ARCHIVE_BUCKET/<environment>/<evidence-type>/<evidence-id>/`.
Do not use archive success as promotion approval; it only proves the evidence
bundle was preserved.

## Deploy Cloud Run

Use `.github/workflows/deploy-google-cloud-cloud-run.yml`.

Inputs:

- `environment`: `demo` or `real`.
- `commit_sha`: full commit SHA whose image was already published.

Expected deployment behavior:

- The workflow verifies `Security` success for the commit.
- The workflow verifies the commit-tagged image exists in Artifact Registry.
- The Cloud Run revision uses the selected deployment contract runtime variables.
- Secret Manager bindings are applied by name; secret values are not committed.
- The configured Cloud SQL instance is attached.
- Unauthenticated access is blocked.
- Revision labels include app, environment, and source commit SHA.
- Deployment metadata is uploaded as an artifact.
- Updated live release evidence bundle with Cloud Run service, revision, runtime
  contract path, config diff artifact, and secret binding proof without secret
  values.

After deploy, do not enable new automation solely because Cloud Run is ready.
Strategy and remediation exchange execution remain governed by runtime config,
policy gates, reconciliation, projection, and risk gates.

## Smoke

Use `.github/workflows/smoke-google-cloud-cloud-run.yml` after deploy.

Inputs:

- `environment`: `demo` or `real`.
- `commit_sha`: full commit SHA expected on the ready revision.

Expected smoke behavior:

- Latest ready revision is labeled with the expected commit.
- Revision image uses the matching commit tag.
- Private `/actuator/health/readiness` returns `UP` through an identity token.
- Smoke evidence is uploaded as an artifact.
- Updated live release evidence bundle with readiness result and Cloud Run smoke
  workflow artifact.

Additional live validation before promotion:

- Binance server-time smoke.
- Binance order endpoint smoke.
- Binance user-data stream smoke.
- Binance market-data WebSocket smoke.
- Config-load evidence for the selected runtime.
- No unintended exchange action on startup.

## Alertmanager Rendering

Render Alertmanager config only from deployment secrets:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh demo \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/demo-alertmanager.yml
```

For real:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh real \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/real-alertmanager.yml
```

Expected behavior:

- The renderer validates that the template placeholder set is exact.
- Required Secret Manager versions must exist.
- Rendered output must not contain unresolved `${ALERTMANAGER_*}` placeholders.
- Rendered output is written with `0600` permissions.
- Secret values are never printed.

If alert rendering fails, treat alerting as unavailable and do not promote to
real until receiver secrets and routing are fixed.

## Rollback

Use `.github/workflows/rollback-google-cloud-cloud-run.yml`.

Inputs:

- `environment`: `demo` or `real`.
- `target_revision`: existing Cloud Run revision to receive 100 percent traffic.
- `expected_commit_sha`: full commit SHA expected on that revision.

Rollback preconditions:

- Rollback commit passed `Security`.
- Target revision belongs to the selected service.
- Target revision labels match app, environment, and expected commit.
- Target revision image uses the expected commit tag.
- Operator recorded why rollback is safer than forward fix.

Expected rollback behavior:

- 100 percent traffic is routed to the target revision.
- Private readiness is verified after traffic shift.
- Rollback evidence is uploaded as an artifact.

Rollback does not repair exchange state by itself. After rollback, check
projection, reconciliation confidence, open orders, open positions, remediation
executor reports, strategy lifecycle state, and pause governance before enabling
or resuming automation.

## Emergency Stop And Controlled Drain

Emergency stop objective: prevent additional risk while preserving enough state
to reconcile safely.

Immediate actions:

1. Use operator controls to move strategy lifecycle to `EMERGENCY_STOP` when the
   operator API is healthy.
2. If operator API is unavailable, deploy or roll back to a runtime config where
   strategy signal runner is disabled, remediation exchange execution is
   disabled, and report-only mode is enabled.
3. Keep cancel/reduce risk-reducing remediation enabled only if current policy,
   projection, reconciliation, and operator intent support it.
4. Do not start a second bot instance against the same provider/environment/
   account/market.
5. Preserve logs, Cloud Run revision, commit SHA, rendered config, metrics,
   alerts, and current projection state.

Controlled drain objective: stop new entries while allowing known safe exits or
risk-reducing remediation.

Drain checks:

- Strategy lifecycle is `DRAINING`.
- No new strategy entry signals are admitted.
- Projected open orders and positions are visible.
- Reconciliation confidence is healthy.
- Exit/reduce operations are policy-allowed only where proven safe.
- Transition from `DRAINING` to `STOPPED` is blocked while projected exposure
  remains open.

## Incident Handling

Create an incident record when any of these occur:

- Failed deployment or failed smoke.
- Unexpected external order or position.
- Unknown order result.
- Degraded reconciliation.
- Stale user-data or market-data stream.
- Alertmanager unavailable.
- Cloud SQL, journal, or projection persistence failure.
- Emergency stop or rollback.
- Any real-environment policy change.

Evidence to capture:

- Environment, provider, account, market, and commit SHA.
- GitHub workflow run ids and artifacts.
- Cloud Run service, revision, image, labels, and traffic split.
- Runtime config diff from the previous deployment.
- Secret binding proof without secret values.
- Alert timeline and rendered Alertmanager config checksum.
- Projection snapshot status and reconciliation observations.
- Open orders, open positions, pause governance, strategy lifecycle, and
  remediation executor reports.
- Operator decisions and timestamps.

## Real Promotion Rules

Real trading must not be enabled just because the same workflow can deploy real.

Required real-promotion evidence:

- Demo deployed and smoked from the same code path.
- Demo burn-in proves the intended real strategy/remediation/symbol-universe
  behavior, not a reduced toy behavior.
- Completed `ops/evidence/live-release-evidence-template.yml` for the relevant
  demo releases.
- Completed `ops/evidence/demo-burn-in-evidence-template.yml` with required
  continuous-operation metrics and incident drills.
- Real credentials are isolated in real Secret Manager secrets.
- Real alert routing is rendered and verified.
- Rollback workflow has been tested for the target.
- Emergency-stop procedure has evidence.
- Real runtime config diff is reviewed and intentionally conservative.
- Real remediation exchange execution remains disabled until explicit operation
  allowlists, risk caps, and promotion evidence permit it.

If any required evidence is missing, keep real execution disabled and continue
demo validation.

## Completion Criteria

A Google Cloud deployment is operationally complete only when:

- Bootstrap completed for the selected project.
- GitHub environments are configured.
- Image publish, deploy, smoke, and rollback workflows have evidence.
- Cloud SQL/JDBC secrets are present and bound.
- Alertmanager renders successfully from Secret Manager.
- Persistence recovery gates are understood and documented.
- Emergency stop and rollback procedures have been rehearsed in demo.
- Demo and real remain the same codebase, with differences limited to config,
  credentials, endpoints, approval gates, provider availability, and calibrated
  risk values.
