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

Cloud-specific contracts must implement this shape without changing bot code.
Google Cloud and AWS can use different runtimes, secret managers, registries,
databases, monitoring stacks, and deployment automation, but the app-facing
environment variables and operational guarantees must stay equivalent.

Current implementations:

- `ops/google-cloud/demo-usdm-futures-deployment.yml`
- `ops/aws/demo-usdm-futures-deployment.yml`
