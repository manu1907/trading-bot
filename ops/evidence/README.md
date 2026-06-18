# Operational Evidence Templates

This directory contains source-controlled evidence templates for live demo and
real operations. Evidence files produced from these templates should be stored in
the deployment evidence location or incident/release archive, not committed with
secret values.

The templates enforce the same product rule used throughout the repo: demo and
real use the same codebase and the same intended behavior. Demo is the
preproduction environment for real. A reduced demo run is not valid real-promotion
evidence unless real is intentionally restricted to exactly the same reduced
behavior.

## Templates

- `live-release-evidence-template.yml`: one release, deployment, smoke,
  rollback, config, secret-binding, state, observability, and decision evidence
  bundle for a demo or real target.
- `demo-burn-in-evidence-template.yml`: multi-window demo burn-in evidence used
  before real execution policy can be enabled.

## Handling Rules

- Do not write Binance API keys, Google Cloud credentials, SMTP passwords,
  Slack webhooks, PagerDuty routing keys, JDBC passwords, or operator tokens into
  evidence files.
- Secret evidence must be proof by name, version, checksum, timestamp, or
  workflow artifact without revealing secret values.
- Attach GitHub workflow run ids, Cloud Run revision names, Artifact Registry
  image references, rendered config checksums, monitoring policy ids, live smoke
  results, and incident/drill ids.
- Keep real execution disabled when required evidence is missing, stale,
  contradictory, or based on a demo behavior that is not the intended real
  behavior.
