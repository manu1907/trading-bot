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

## Live Release Collector

`collect-live-release-evidence.sh` creates a concrete sanitized evidence bundle
from the current checkout and operator-supplied live observations:

```bash
ops/evidence/collect-live-release-evidence.sh demo \
  --release-id demo-2026-06-18-001 \
  --operator github-actions \
  --decision deploy \
  --security-workflow-run-id 27783595357 \
  --smoke-workflow-run-id 123456789 \
  --cloud-run-region europe-west1 \
  --cloud-run-revision trading-bot-demo-main-usdm-futures-00001 \
  --revision-commit-label "$(git rev-parse HEAD)" \
  --output-dir build/evidence/demo/demo-2026-06-18-001
```

Use `real` instead of `demo` only for the real runtime contract. The collector
uses the same script and evidence schema for both environments; environment
differences come from the selected deployment contract, credentials, endpoints,
approval gates, provider availability, and calibrated risk/runtime values.

The collector is offline-first. It does not call Google Cloud, GitHub, Binance,
or Secret Manager. Pass workflow run ids, Cloud Run revision metadata, rendered
Alertmanager files, smoke output, trading-state snapshots, and risk-policy
snapshots from those systems as inputs. Optional observation files are copied
only after a built-in secret-pattern scan.

Generated bundles must still be filled with live outcomes. `TODO` and
`true_or_false` fields are intentional blockers until the operator or automation
attaches the corresponding deployment, smoke, trading-state, rollback,
emergency-stop, alerting, and promotion evidence.

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
