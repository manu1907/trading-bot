# Alertmanager Operations

This directory contains Alertmanager routing profiles for trading-bot alerts.

## Pause Governance Routing

`pause-governance-alertmanager.yml` routes the Prometheus alerts in
`ops/prometheus/pause-governance-alerts.yml` by the rule labels `service`,
`routing_hint`, and `severity`.

Routes:

- `routing_hint=platform`, `severity=critical`: platform PagerDuty plus platform Slack.
- `routing_hint=operator`, `severity=critical`: operator PagerDuty plus operator Slack.
- `routing_hint=operator`, `severity=warning`: operator Slack.
- `routing_hint=operator`, `severity=info`: operator Slack.
- unmatched trading-bot alerts: fallback Slack.

Required deployment secrets or environment substitutions:

- `ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY`
- `ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL`
- `ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK`
- `ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL`

Do not commit real webhook URLs or PagerDuty routing keys. Inject them through the
selected deployment secret system.

The Google Cloud demo deployment contract in
`ops/google-cloud/demo-usdm-futures-deployment.yml` maps these substitutions to
Google Secret Manager secret names for the Binance USD-M futures demo target.
The AWS demo deployment contract in
`ops/aws/demo-usdm-futures-deployment.yml` maps the same substitutions to AWS
Secrets Manager names.
