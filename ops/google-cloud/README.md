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

Deployment automation must render the Alertmanager profile from
`ops/alertmanager/pause-governance-alertmanager.yml` by substituting these
Secret Manager values outside source control.
