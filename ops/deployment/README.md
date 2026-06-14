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

Current implementations:

- `ops/google-cloud/demo-usdm-futures-deployment.yml`
- `ops/google-cloud/real-usdm-futures-deployment.yml`
- `ops/aws/demo-usdm-futures-deployment.yml`
