# Deployment Contracts

This directory defines the cloud-neutral deployment contract for trading-bot.

`deployment-contract.yml` describes the runtime surface that must remain stable
across cloud providers:

- Active target environment variables.
- Operator API enablement and token binding.
- Secret-backed Binance demo credentials.
- Secret-backed JDBC audit credentials.
- Alertmanager receiver substitutions.
- Indexed JDBC pause-governance audit persistence.
- Audit retention and backup policy.
- Indexed JDBC trading-state projection persistence.
- Trading-state projection retention, compaction, backup, and restore-drill
  policy.
- Journal archive destination, layout, and retention policy.
- Production runtime container image contract.

Cloud-specific contracts must implement this shape without changing bot code.
Google Cloud and AWS can use different runtimes, secret managers, registries,
databases, monitoring stacks, and deployment automation, but the app-facing
environment variables and operational guarantees must stay equivalent.

Cloud Run and ECS contracts explicitly disable file projection snapshots and
enable JDBC projection persistence because ephemeral container filesystems are
not a durable trading-state backend. Local first-start runtime files can still
use file snapshots unless runtime environment variables override them.

The recovery and restore procedure is documented in
`ops/runbooks/persistence-recovery.md`.

The root `Dockerfile` defines the production runtime image used by Cloud Run and
ECS deployment automation. It packages the prebuilt `bot-app` Spring Boot jar,
copies source-controlled config to `/app/config`, defaults to the `live`
profile, sets `BOT_CONFIG_DIR=/app/config`, includes the Chronicle Queue JVM
module-access flags required by the runtime, runs as a non-root `tradingbot`
user, and exposes the readiness health endpoint. The image carries OCI metadata
labels for source repository, git revision, ref/version, and build creation
time. GitHub Actions currently builds the image without pushing it and uploads
the Buildx metadata as an artifact; registry publication and deployment
workflows remain separate guarded CI/CD slices.

`.github/workflows/publish-google-cloud-image.yml` is the guarded Google Cloud
publication workflow. It is manual only, uses GitHub environment approval for
`demo` and `real`, authenticates to Google Cloud through OIDC Workload Identity,
publishes the same Dockerfile to Artifact Registry with commit-SHA tags, and
uploads publish metadata. It does not deploy Cloud Run yet.

`.github/workflows/deploy-google-cloud-cloud-run.yml` is the guarded Cloud Run
deployment workflow. It is manual only, uses the same `demo` and `real` GitHub
environment gates, verifies the requested commit passed the `Security` workflow,
verifies the commit-tagged Artifact Registry image exists, maps the
source-controlled Google Cloud deployment contract runtime variables and Secret
Manager bindings into Cloud Run, deploys a commit-labeled revision, and uploads
deployment metadata.

`.github/workflows/smoke-google-cloud-cloud-run.yml` is the guarded Cloud Run
post-deployment smoke workflow. It is manual only, uses the same `demo` and
`real` GitHub environment gates, verifies `Security` success for the requested
commit, checks that the latest ready Cloud Run revision is labeled with that
commit and runs the matching commit-tagged image, invokes the private readiness
endpoint using a Google identity token, and uploads smoke metadata. Rollback
automation is handled by a separate guarded workflow.

`.github/workflows/rollback-google-cloud-cloud-run.yml` is the guarded Cloud Run
rollback workflow. It is manual only, uses the same `demo` and `real` GitHub
environment gates, requires an explicit target revision and rollback commit SHA,
verifies `Security` success for that commit, proves that the target revision
belongs to the selected service and matches the expected commit label/image,
routes 100 percent traffic to the revision, verifies private readiness, and
uploads rollback metadata.

Current implementations:

- `ops/google-cloud/demo-usdm-futures-deployment.yml`
- `ops/google-cloud/real-usdm-futures-deployment.yml`
- `ops/aws/demo-usdm-futures-deployment.yml`
