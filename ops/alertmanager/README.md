# Alertmanager Operations

This directory contains Alertmanager routing profiles for trading-bot alerts.

## Pause Governance Routing

`pause-governance-alertmanager.yml` routes the Prometheus alerts in
`ops/prometheus/pause-governance-alerts.yml` and
`ops/prometheus/remediation-executor-alerts.yml` by the rule labels `service`,
`routing_hint`, and `severity`.

Routes:

- `routing_hint=platform`, `severity=critical`: platform PagerDuty plus platform Slack and platform email.
- `routing_hint=operator`, `severity=critical`: operator PagerDuty plus operator Slack and operator email.
- `routing_hint=operator`, `severity=warning`: operator Slack and operator email.
- `routing_hint=operator`, `severity=info`: operator Slack and operator email.
- unmatched trading-bot alerts: fallback Slack and fallback email.

Required deployment secrets or environment substitutions:

- `ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY`
- `ALERTMANAGER_TRADING_BOT_SMTP_SMARTHOST`
- `ALERTMANAGER_TRADING_BOT_SMTP_FROM`
- `ALERTMANAGER_TRADING_BOT_SMTP_AUTH_USERNAME`
- `ALERTMANAGER_TRADING_BOT_SMTP_AUTH_PASSWORD`
- `ALERTMANAGER_TRADING_BOT_OPERATOR_EMAIL_TO`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_EMAIL_TO`
- `ALERTMANAGER_TRADING_BOT_FALLBACK_EMAIL_TO`
- `ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL`
- `ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL`

Do not commit real webhook URLs, PagerDuty routing keys, SMTP credentials, or
personal email addresses. Inject them through the selected deployment secret
system.

## Google Cloud Rendering

`render-google-cloud-alertmanager.sh` renders the source-controlled placeholder
template from Google Secret Manager for either `demo` or `real`. It fails closed
if the template placeholders do not exactly match the supported substitution set,
if any required Secret Manager value is missing, or if any `${ALERTMANAGER_*}`
placeholder remains after rendering. Rendered files are written with `0600`
permissions and secret values are not printed.

Examples:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh demo \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/demo-alertmanager.yml

ops/alertmanager/render-google-cloud-alertmanager.sh real \
  --project "$GCP_PROJECT_ID" \
  --output build/alertmanager/real-alertmanager.yml
```

Template-only validation, without Google Cloud access:

```bash
ops/alertmanager/render-google-cloud-alertmanager.sh demo --validate-placeholders-only
```

The Google Cloud deployment contracts in
`ops/google-cloud/demo-usdm-futures-deployment.yml` and
`ops/google-cloud/real-usdm-futures-deployment.yml` map these substitutions to
Google Secret Manager secret names for the Binance USD-M futures targets. The
AWS deployment contracts in `ops/aws/demo-usdm-futures-deployment.yml` and
`ops/aws/real-usdm-futures-deployment.yml` map the same substitutions to AWS
Secrets Manager names.
