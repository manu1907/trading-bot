#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Render trading-bot Alertmanager config from Google Secret Manager.

Usage:
  ops/alertmanager/render-google-cloud-alertmanager.sh demo --project <gcp-project> --output build/alertmanager/demo-alertmanager.yml
  ops/alertmanager/render-google-cloud-alertmanager.sh real --project <gcp-project> --output build/alertmanager/real-alertmanager.yml

Arguments:
  environment            demo or real

Options:
  --project <id>         Google Cloud project. Defaults to current gcloud project.
  --template <path>      Alertmanager template path.
                         Default: ops/alertmanager/pause-governance-alertmanager.yml
  --output <path>        Rendered output path. Required unless --validate-placeholders-only is used.
  --validate-placeholders-only
                         Do not access Google Cloud. Validate that the template contains exactly the required placeholders.
  -h, --help             Show this help.

The script never prints secret values. It fails if a required Secret Manager
secret has no enabled latest version or if unresolved ${...} placeholders remain
after rendering.
USAGE
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

resolve_path() {
  local path="$1"
  if [[ -f "$path" ]]; then
    printf '%s' "$path"
    return
  fi
  local parent="../${path}"
  if [[ -f "$parent" ]]; then
    printf '%s' "$parent"
    return
  fi
  echo "Unable to locate file: $path" >&2
  exit 1
}

current_gcloud_project() {
  local configured_project
  configured_project="$(gcloud config get-value project 2>/dev/null || true)"
  if [[ "$configured_project" == "(unset)" || -z "$configured_project" ]]; then
    echo "Unable to infer GCP project. Pass --project <id>." >&2
    exit 1
  fi
  printf '%s' "$configured_project"
}

secret_latest_value() {
  local secret_name="$1"
  local value
  if ! value="$(gcloud secrets versions access latest \
      --secret "$secret_name" \
      --project "$GCP_PROJECT_ID" 2>/dev/null)"; then
    echo "Missing enabled secret version: $secret_name" >&2
    exit 1
  fi
  printf '%s' "$value"
}

required_placeholders() {
  cat <<'PLACEHOLDERS'
ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY
ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY
ALERTMANAGER_TRADING_BOT_SMTP_SMARTHOST
ALERTMANAGER_TRADING_BOT_SMTP_FROM
ALERTMANAGER_TRADING_BOT_SMTP_AUTH_USERNAME
ALERTMANAGER_TRADING_BOT_SMTP_AUTH_PASSWORD
ALERTMANAGER_TRADING_BOT_OPERATOR_EMAIL_TO
ALERTMANAGER_TRADING_BOT_PLATFORM_EMAIL_TO
ALERTMANAGER_TRADING_BOT_FALLBACK_EMAIL_TO
ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK
ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL
ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK
ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL
ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK
ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL
PLACEHOLDERS
}

secret_name_for_placeholder() {
  local placeholder="$1"
  case "$placeholder" in
    ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY)
      printf 'trading-bot-%s-alert-operator-pagerduty-routing-key' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY)
      printf 'trading-bot-%s-alert-platform-pagerduty-routing-key' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_SMTP_SMARTHOST)
      printf 'trading-bot-%s-alert-smtp-smarthost' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_SMTP_FROM)
      printf 'trading-bot-%s-alert-smtp-from' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_SMTP_AUTH_USERNAME)
      printf 'trading-bot-%s-alert-smtp-auth-username' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_SMTP_AUTH_PASSWORD)
      printf 'trading-bot-%s-alert-smtp-auth-password' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_OPERATOR_EMAIL_TO)
      printf 'trading-bot-%s-alert-operator-email-to' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_PLATFORM_EMAIL_TO)
      printf 'trading-bot-%s-alert-platform-email-to' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_FALLBACK_EMAIL_TO)
      printf 'trading-bot-%s-alert-fallback-email-to' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK)
      printf 'trading-bot-%s-alert-operator-slack-webhook' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL)
      printf 'trading-bot-%s-alert-operator-slack-channel' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK)
      printf 'trading-bot-%s-alert-platform-slack-webhook' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL)
      printf 'trading-bot-%s-alert-platform-slack-channel' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK)
      printf 'trading-bot-%s-alert-fallback-slack-webhook' "$TARGET_ENVIRONMENT"
      ;;
    ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL)
      printf 'trading-bot-%s-alert-fallback-slack-channel' "$TARGET_ENVIRONMENT"
      ;;
    *)
      echo "Unsupported placeholder: $placeholder" >&2
      exit 1
      ;;
  esac
}

validate_placeholders() {
  local template="$1"
  local expected actual
  expected="$(required_placeholders | sort)"
  actual="$(grep -o '\${ALERTMANAGER_TRADING_BOT_[A-Z0-9_]*}' "$template" \
    | sed 's/[${}]//g' \
    | sort -u)"
  if [[ "$actual" != "$expected" ]]; then
    echo "Alertmanager template placeholders do not match required substitutions." >&2
    echo "Expected:" >&2
    printf '%s\n' "$expected" >&2
    echo "Actual:" >&2
    printf '%s\n' "$actual" >&2
    exit 1
  fi
}

render_template() {
  local template="$1"
  local output="$2"
  local rendered
  rendered="$(cat "$template")"
  while IFS= read -r placeholder; do
    local secret_name secret_value
    secret_name="$(secret_name_for_placeholder "$placeholder")"
    secret_value="$(secret_latest_value "$secret_name")"
    rendered="${rendered//\$\{$placeholder\}/$secret_value}"
  done < <(required_placeholders)

  if grep -q '\${ALERTMANAGER_' <<<"$rendered"; then
    echo "Rendered Alertmanager config still contains unresolved placeholders." >&2
    exit 1
  fi

  mkdir -p "$(dirname "$output")"
  printf '%s\n' "$rendered" > "$output"
  chmod 0600 "$output"
}

TARGET_ENVIRONMENT="${1:-}"
if [[ -z "$TARGET_ENVIRONMENT" || "$TARGET_ENVIRONMENT" == "-h" || "$TARGET_ENVIRONMENT" == "--help" ]]; then
  usage
  exit 0
fi
shift

case "$TARGET_ENVIRONMENT" in
  demo | real) ;;
  *)
    echo "environment must be demo or real" >&2
    exit 1
    ;;
esac

TEMPLATE="ops/alertmanager/pause-governance-alertmanager.yml"
OUTPUT=""
GCP_PROJECT_ID="${GCP_PROJECT_ID:-}"
VALIDATE_PLACEHOLDERS_ONLY=false

while (($# > 0)); do
  case "$1" in
    --project)
      GCP_PROJECT_ID="${2:-}"
      shift 2
      ;;
    --template)
      TEMPLATE="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    --validate-placeholders-only)
      VALIDATE_PLACEHOLDERS_ONLY=true
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      exit 1
      ;;
  esac
done

TEMPLATE="$(resolve_path "$TEMPLATE")"
validate_placeholders "$TEMPLATE"

if [[ "$VALIDATE_PLACEHOLDERS_ONLY" == "true" ]]; then
  exit 0
fi

if [[ -z "$OUTPUT" ]]; then
  echo "--output is required unless --validate-placeholders-only is used." >&2
  exit 1
fi

require_command gcloud
if [[ -z "$GCP_PROJECT_ID" ]]; then
  GCP_PROJECT_ID="$(current_gcloud_project)"
fi

render_template "$TEMPLATE" "$OUTPUT"
printf 'Rendered Alertmanager config for %s to %s\n' "$TARGET_ENVIRONMENT" "$OUTPUT"
