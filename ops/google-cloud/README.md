# Google Cloud Operations

This directory contains deployment contracts for running trading-bot on Google
Cloud without committing runtime secrets.

The contract implements the neutral schema in
`ops/deployment/deployment-contract.yml`. Google Cloud-specific choices must not
change the app-facing runtime variables or trading behavior.

## Demo USD-M Futures

`demo-usdm-futures-deployment.yml` is the first deployment contract for the
Binance USD-M futures demo target:

- Cloud Run service: `trading-bot-demo-main-usdm-futures`.
- Runtime image: root `Dockerfile`, built from the `bot-app` boot jar and
  source-controlled config.
- Active target: `binance / demo / main / usdm_futures`.
- Runtime config directory: `/app/config`.
- Operator API enabled with `X-Operator-Token` bound from Secret Manager.
- Audit backend: indexed JDBC, backed by a Google Cloud SQL PostgreSQL database.
- JSONL pause-governance audit persistence is disabled for Cloud Run because the
  container filesystem is not a durable audit backend.
- JDBC schema initialization is disabled in the app runtime; schema ownership is
  assigned to deployment migration.
- JDBC audit retention is 180 days.
- Audit backups use Cloud SQL automated backups with at least 7 recovery days
  and a restore drill every 90 days.
- Projection persistence uses Cloud SQL PostgreSQL through
  `TRADING_PROJECTION_JDBC_*` secrets, with file snapshots disabled in Cloud
  Run.
- Projection state has 180-day retention, weekly-or-slower compaction that
  preserves the latest snapshot and applied event ids, Cloud SQL automated
  backups with at least 7 recovery days, and a 90-day restore drill.
- Journal archives use Cloud Storage with `trading_event_archive_layout_v1`.

The contract maps secret-bearing values to Google Secret Manager names only. It
does not contain real Binance credentials, operator tokens, Slack webhooks,
Slack channels, PagerDuty routing keys, or database credentials.

Required application secrets:

- `trading-bot-demo-binance-api-key`
- `trading-bot-demo-binance-api-secret`
- `trading-bot-demo-operator-token`
- `trading-bot-demo-audit-jdbc-url`
- `trading-bot-demo-audit-jdbc-username`
- `trading-bot-demo-audit-jdbc-password`
- `trading-bot-demo-projection-jdbc-url`
- `trading-bot-demo-projection-jdbc-username`
- `trading-bot-demo-projection-jdbc-password`

Required Alertmanager substitution secrets:

- `trading-bot-demo-alert-operator-pagerduty-routing-key`
- `trading-bot-demo-alert-platform-pagerduty-routing-key`
- `trading-bot-demo-alert-operator-slack-webhook`
- `trading-bot-demo-alert-operator-slack-channel`
- `trading-bot-demo-alert-platform-slack-webhook`
- `trading-bot-demo-alert-platform-slack-channel`
- `trading-bot-demo-alert-fallback-slack-webhook`
- `trading-bot-demo-alert-fallback-slack-channel`

## Real USD-M Futures

`real-usdm-futures-deployment.yml` is the matching Google Cloud contract for
the Binance USD-M futures real target:

- Cloud Run service: `trading-bot-real-main-usdm-futures`.
- Active target: `binance / real / main / usdm_futures`.
- Real Binance credentials are isolated behind `BINANCE_REAL_API_KEY` and
  `BINANCE_REAL_API_SECRET` Secret Manager bindings.
- Operator token, audit JDBC credentials, and alert receiver secrets use
  `trading-bot-real-*` Secret Manager names.
- The remediation executor policy is explicitly disabled for real startup:
  executor disabled, exchange execution disabled, report-only true, and
  real-environment execution not allowed.
- Real deployment metadata requires manual approval, demo promotion evidence,
  real secret isolation, and an empty initial real-operation allowlist.
- The real audit backend uses Cloud SQL PostgreSQL with 365-day retention, at
  least 35 recovery days, and a restore drill every 30 days.
- The real projection backend uses Cloud SQL PostgreSQL with 365-day retention,
  at least 35 recovery days, and a restore drill every 30 days.
- Real journal archives use the same archive layout as demo with a real-specific
  prefix and 365-day retention.

Deployment automation must render the Alertmanager profile from
`ops/alertmanager/pause-governance-alertmanager.yml` by substituting these
Secret Manager values outside source control.

The ordinary `Security` GitHub Actions workflow validates that the runtime image
builds without publishing to Artifact Registry. The image build already carries
OCI source/revision/version/created metadata and uploads Buildx metadata as a CI
artifact.

`publish-google-cloud-image.yml` publishes the image to Artifact Registry when
manually dispatched. Before pushing, it verifies that the source commit has a
successful `Security` workflow run. It requires a GitHub environment named
`demo` or `real`, OIDC Workload Identity secrets
`GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT`,
and environment variables `GCP_PROJECT_ID`,
`GCP_ARTIFACT_REGISTRY_LOCATION`, and
`GCP_ARTIFACT_REGISTRY_REPOSITORY`. It tags images with the full commit SHA and
`{environment}-{sha}`, then uploads publish metadata as a workflow artifact.

`deploy-google-cloud-cloud-run.yml` deploys an already published image to Cloud
Run when manually dispatched. It verifies the requested commit passed
`Security`, verifies the Artifact Registry image exists, applies the deployment
contract runtime variables and Secret Manager bindings, uses the configured
Cloud Run deploy/runtime service accounts, blocks unauthenticated access, labels
the revision with the source commit, and uploads deployment metadata.

`smoke-google-cloud-cloud-run.yml` verifies an already deployed Cloud Run service
when manually dispatched. It uses the same `demo` and `real` GitHub environment
gates, verifies that the requested commit passed `Security`, authenticates to
Google Cloud through OIDC, checks that the latest ready revision is labeled with
the requested commit SHA and is running the matching commit-tagged image, calls
the private `/actuator/health/readiness` endpoint with an identity token, and
uploads smoke evidence. It requires
`GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT`; that service account must have permission
to describe the target Cloud Run service/revision and invoke the private service
through `roles/run.invoker`. Rollback automation remains open.
