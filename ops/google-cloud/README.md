# Google Cloud Operations

This directory contains deployment contracts for running trading-bot on Google
Cloud without committing runtime secrets.

The contract implements the neutral schema in
`ops/deployment/deployment-contract.yml`. Google Cloud-specific choices must not
change the app-facing runtime variables or trading behavior.

Operational procedures for bootstrap, image publication, Cloud Run deployment,
private smoke, alert rendering, rollback, emergency stop, incident evidence, and
real promotion live in `ops/runbooks/google-cloud-operations.md`. Scenario-specific
exchange, stream, reconciliation, external intervention, credential, persistence,
alerting, and cost incident procedures live in `ops/runbooks/incident-response.md`.

## Bootstrap Script

`bootstrap-deployment-prereqs.sh` prepares the Google Cloud foundation expected
by the current GitHub Actions workflows. It is idempotent and can be rerun after
partial setup.

Required local tools and access:

- `gcloud`, `git`, and `openssl` installed.
- `gh` installed and authenticated only if
  `GITHUB_CONFIGURE_ENVIRONMENTS=true` is used.
- `gcloud` authenticated as an account allowed to manage the target project, IAM,
  APIs, Artifact Registry, Secret Manager, Cloud Storage, and Workload Identity
  Federation.
- If budget alerts are enabled, access to the target Cloud Billing account to
  list and create billing budgets.
- If GitHub automation is enabled, `gh` must be authenticated as a GitHub user
  or token allowed to create/update repository environments, environment
  secrets, and environment variables.
- A Google Cloud project with billing enabled, or `GCP_CREATE_PROJECT=true` plus
  `GCP_BILLING_ACCOUNT`.
- Binance demo API credentials exported as `BINANCE_DEMO_API_KEY` and
  `BINANCE_DEMO_API_SECRET`. The checked-in ignored `api.env` already uses these
  names.

Minimal invocation:

```bash
set -a
source api.env
set +a
./ops/google-cloud/bootstrap-deployment-prereqs.sh
```

Defaults used when you do not override them:

- `GCP_PROJECT_ID`: current `gcloud` project.
- `GCP_REGION`: `europe-west1`.
- `GCP_ARTIFACT_REGISTRY_LOCATION`: same as `GCP_REGION`.
- `GCP_ARTIFACT_REGISTRY_REPOSITORY`: `trading-bot`.
- `GITHUB_OWNER` and `GITHUB_REPO`: inferred from the Git remote, falling back to
  `manu1907/trading-bot`.
- `GITHUB_CONFIGURE_ENVIRONMENTS`: `false`.
- `GCP_CLOUD_SQL_INSTANCE`: `trading-bot-postgres`.
- `GCP_CLOUD_SQL_DATABASE_VERSION`: `POSTGRES_16`.
- `GCP_CLOUD_SQL_TIER`: `db-custom-1-3840`.
- `GCP_CLOUD_SQL_STORAGE_GB`: `20`.
- `GCP_CLOUD_SQL_AVAILABILITY_TYPE`: `ZONAL`.
- `GCP_BUDGET_ALERTS_ENABLED`: `false`.
- `GCP_BUDGET_DISPLAY_NAME`: `trading-bot-${GCP_PROJECT_ID}-monthly`.
- `GCP_BUDGET_AMOUNT`: `250USD`.
- `GCP_BUDGET_CALENDAR_PERIOD`: `month`.
- `GCP_BUDGET_THRESHOLD_RULES`:
  `percent=0.50;percent=0.80;percent=1.00;percent=1.00,basis=forecasted-spend`.
- `GCP_BUDGET_FILTER_PROJECTS`: `projects/${GCP_PROJECT_ID}`.
- `GCP_BUDGET_DISABLE_DEFAULT_IAM_RECIPIENTS`: `false`.
- `DEMO_CLOUD_SQL_DATABASE` and `REAL_CLOUD_SQL_DATABASE`:
  `trading_bot_demo` and `trading_bot_real`.
- Audit database users: `DEMO_AUDIT_CLOUD_SQL_USERNAME` and
  `REAL_AUDIT_CLOUD_SQL_USERNAME`, defaulting to `trading_bot_demo_audit` and
  `trading_bot_real_audit`.
- Projection database users: `DEMO_PROJECTION_CLOUD_SQL_USERNAME` and
  `REAL_PROJECTION_CLOUD_SQL_USERNAME`, defaulting to
  `trading_bot_demo_projection` and `trading_bot_real_projection`.
- Operator tokens: generated automatically if no enabled secret version already
  exists.

The script enables required APIs, creates the Artifact Registry repository,
creates the journal archive bucket, creates the GitHub Actions and Cloud Run
service accounts, creates the evidence archive bucket and archiver service
account, grants the IAM roles required by the publish/deploy/smoke/rollback and
evidence-archive workflows, configures GitHub OIDC Workload Identity Federation,
creates or verifies the Cloud SQL PostgreSQL instance, creates demo and real
databases and users, creates the deployment Secret Manager secrets, adds Binance
demo secret versions from `api.env`, generates operator-token and Cloud SQL
password versions when needed, generates Cloud SQL JDBC URL/username/password
secrets when no overrides exist, optionally creates a project-scoped monthly
billing budget alert, and adds optional secret versions only for other values
supplied through environment variables. It never prints secret values.

If `GITHUB_CONFIGURE_ENVIRONMENTS=true`, the script also uses GitHub CLI to
create or update the `demo` and `real` GitHub environments, writes the required
Google Cloud OIDC/service-account values as environment secrets, and writes the
deployment region, Artifact Registry, Cloud Run sizing, timeout, and Cloud SQL
instance values as environment variables. This removes the copy/paste step for
the GitHub values printed by the bootstrap output. It does not create Binance,
Alertmanager, or database secrets in GitHub; those values live in Google Secret
Manager and are bound by name during Cloud Run deployment.

After it completes, copy the printed values into the `demo` and `real` GitHub
environment secrets/variables if you did not enable GitHub automation. Real
Binance credentials and alert receiver values remain intentionally unset unless
you provide their environment variables. The Cloud SQL resources are billable
Google Cloud resources; override the Cloud SQL tier, storage, availability, and
instance names before running the script if you want a different cost or
separation model.

Invocation with GitHub environment automation:

```bash
set -a
source api.env
set +a
GITHUB_CONFIGURE_ENVIRONMENTS=true ./ops/google-cloud/bootstrap-deployment-prereqs.sh
```

Optional budget alert provisioning:

```bash
set -a
source api.env
set +a
GCP_BILLING_ACCOUNT=000000-000000-000000 \
GCP_BUDGET_ALERTS_ENABLED=true \
./ops/google-cloud/bootstrap-deployment-prereqs.sh
```

The budget alert is idempotent by display name. By default it is scoped to the
Google Cloud project, uses a monthly `250USD` amount, keeps default Billing IAM
recipients enabled, and alerts at current-spend 50 percent, 80 percent, and 100
percent plus forecasted-spend 100 percent. Optional
`GCP_BUDGET_PUBSUB_TOPIC` and
`GCP_BUDGET_MONITORING_NOTIFICATION_CHANNELS` can add Pub/Sub or Cloud
Monitoring notification targets. Budget alerts are cost observability; they do
not stop the bot or replace trading risk controls.

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
  assigned to deployment migration through
  `.github/workflows/migrate-google-cloud-postgresql-state.yml` and
  `ops/database/migrate-postgresql-state.sh`.
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
- `trading-bot-demo-alert-smtp-smarthost`
- `trading-bot-demo-alert-smtp-from`
- `trading-bot-demo-alert-smtp-auth-username`
- `trading-bot-demo-alert-smtp-auth-password`
- `trading-bot-demo-alert-operator-email-to`
- `trading-bot-demo-alert-platform-email-to`
- `trading-bot-demo-alert-fallback-email-to`
- `trading-bot-demo-alert-operator-slack-webhook`
- `trading-bot-demo-alert-operator-slack-channel`
- `trading-bot-demo-alert-platform-slack-webhook`
- `trading-bot-demo-alert-platform-slack-channel`
- `trading-bot-demo-alert-fallback-slack-webhook`
- `trading-bot-demo-alert-fallback-slack-channel`

Render the Google Cloud demo Alertmanager config from Secret Manager with:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh demo \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/demo-alertmanager.yml
```

For one-person demo operation, `DEMO_ALERT_EMAIL_TO` can be set once during
bootstrap to populate the operator, platform, and fallback email receiver
secrets with the same address. SMTP still requires
`DEMO_ALERT_SMTP_SMARTHOST`, `DEMO_ALERT_SMTP_FROM`,
`DEMO_ALERT_SMTP_AUTH_USERNAME`, and `DEMO_ALERT_SMTP_AUTH_PASSWORD`; for Gmail,
use an app password rather than the normal account password.

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

Render the Google Cloud real Alertmanager config from Secret Manager with:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh real \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/real-alertmanager.yml
```

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
through `roles/run.invoker`.

`rollback-google-cloud-cloud-run.yml` rolls Cloud Run traffic back to an
existing revision when manually dispatched. It uses the same `demo` and `real`
GitHub environment gates, requires the operator to provide both the target
revision name and the expected rollback commit SHA, verifies that the rollback
commit passed `Security`, verifies that the target revision belongs to the
selected service and carries the expected app/environment/commit labels and
commit-tagged image, shifts 100 percent traffic to that revision, calls the
private readiness endpoint with an identity token, and uploads rollback evidence.
It requires `GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT`; that service account must
be able to describe services/revisions, update Cloud Run service traffic, and
invoke the private service through `roles/run.invoker`.

`archive-google-cloud-evidence.yml` archives generated evidence artifacts to
Cloud Storage when manually dispatched. It downloads a named artifact from a
source workflow run, validates the bundle with
`ops/evidence/archive-google-cloud-evidence.sh`, uploads it to
`gs://$GCP_EVIDENCE_ARCHIVE_BUCKET/<environment>/<evidence-type>/<evidence-id>/`,
and uploads the archive manifest. It requires
`GCP_EVIDENCE_ARCHIVE_SERVICE_ACCOUNT` and `GCP_EVIDENCE_ARCHIVE_BUCKET`; the
bootstrap script creates the service account, grants storage write access, and
can configure both GitHub environment values.

`archive-google-cloud-journal.yml` archives produced raw journal artifacts to
Cloud Storage when manually dispatched. It downloads a journal artifact from a
source workflow run, validates it with `ops/database/archive-journal-segments.sh`,
uploads it to
`gs://$GCP_JOURNAL_ARCHIVE_BUCKET/<environment>/<provider>/<account>/<market>/journal-segments/v1/<archive-id>/`,
and uploads the journal manifest. It requires
`GCP_JOURNAL_ARCHIVE_SERVICE_ACCOUNT` and `GCP_JOURNAL_ARCHIVE_BUCKET`; the
bootstrap script creates the service account, grants storage write access, and
can configure both GitHub environment values.

## Cloud Monitoring Alert Policies

`provision-monitoring-alert-policies.sh` renders and creates Google Cloud
Monitoring alert policies from the templates in
`ops/google-cloud/monitoring/alert-policies`. It is parameterized by
environment and uses the same policy templates for demo and real.

Current managed platform policies:

- Cloud Run 5xx responses for the selected trading-bot service.
- Cloud SQL CPU utilization above 80 percent for five minutes.
- Cloud SQL disk utilization above 85 percent for five minutes.

Validate templates without Google Cloud access:

```bash
ops/google-cloud/provision-monitoring-alert-policies.sh demo --validate-only
```

Provision demo policies with optional Cloud Monitoring notification channels:

```bash
ops/google-cloud/provision-monitoring-alert-policies.sh demo \
  --project "$GCP_PROJECT_ID" \
  --region "$GCP_REGION" \
  --cloud-sql-instance "$GCP_CLOUD_SQL_INSTANCE" \
  --notification-channels "$GCP_MONITORING_NOTIFICATION_CHANNELS"
```

The script is idempotent by policy `displayName`; reruns skip policies that
already exist. The notification channel input must be Cloud Monitoring
notification channel resource names, not email addresses, webhook URLs, or
secrets.
