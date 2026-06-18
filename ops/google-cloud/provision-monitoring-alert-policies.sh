#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Provision Google Cloud Monitoring alert policies for trading-bot.

Usage:
  ops/google-cloud/provision-monitoring-alert-policies.sh demo [options]
  ops/google-cloud/provision-monitoring-alert-policies.sh real [options]

Arguments:
  environment                       demo or real

Options:
  --project <id>                    Google Cloud project. Defaults to current gcloud project.
  --region <region>                 Cloud Run region. Default: europe-west1.
  --service <name>                  Cloud Run service. Default: trading-bot-{environment}-main-usdm-futures.
  --cloud-sql-instance <name>       Cloud SQL instance. Default: trading-bot-postgres.
  --template-dir <path>             Policy template directory. Default: ops/google-cloud/monitoring/alert-policies.
  --render-dir <path>               Rendered policy output directory. Default: build/google-cloud/monitoring/{environment}.
  --notification-channels <names>   Optional comma-separated Cloud Monitoring notification channel resource names.
  --validate-only                   Render and validate policy JSON without creating Google Cloud policies.
  -h, --help                        Show this help.

The script is idempotent by policy displayName. It creates a policy only when no
existing policy with the same displayName exists in the target project. It never
prints notification channel secrets because it accepts only Cloud Monitoring
channel resource names.
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
  if [[ -e "$path" ]]; then
    printf '%s' "$path"
    return
  fi
  local parent="../${path}"
  if [[ -e "$parent" ]]; then
    printf '%s' "$parent"
    return
  fi
  echo "Unable to locate path: $path" >&2
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

render_policy_template() {
  local template="$1"
  local output="$2"
  local rendered
  rendered="$(cat "$template")"
  rendered="${rendered//\$\{TARGET_ENVIRONMENT\}/$TARGET_ENVIRONMENT}"
  rendered="${rendered//\$\{GCP_PROJECT_ID\}/$GCP_PROJECT_ID}"
  rendered="${rendered//\$\{GCP_REGION\}/$GCP_REGION}"
  rendered="${rendered//\$\{CLOUD_RUN_SERVICE\}/$CLOUD_RUN_SERVICE}"
  rendered="${rendered//\$\{CLOUD_SQL_INSTANCE\}/$CLOUD_SQL_INSTANCE}"
  if grep -q '\${[A-Z0-9_]*}' <<<"$rendered"; then
    echo "Rendered policy still contains unresolved placeholders from $template." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$output")"
  printf '%s\n' "$rendered" > "$output"
  python3 -m json.tool "$output" >/dev/null
}

policy_display_name() {
  local policy_file="$1"
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["displayName"])' "$policy_file"
}

policy_exists() {
  local display_name="$1"
  [[ -n "$(gcloud monitoring policies list \
    --project="$GCP_PROJECT_ID" \
    --filter="displayName=\"${display_name}\"" \
    --limit=1 \
    --format='value(name)' 2>/dev/null)" ]]
}

create_policy_if_missing() {
  local policy_file="$1"
  local display_name
  display_name="$(policy_display_name "$policy_file")"
  if policy_exists "$display_name"; then
    printf 'Monitoring policy already exists: %s\n' "$display_name"
    return
  fi
  local create_args=(
    "--project=$GCP_PROJECT_ID"
    "--policy-from-file=$policy_file"
    --quiet
  )
  if [[ -n "$GCP_MONITORING_NOTIFICATION_CHANNELS" ]]; then
    create_args+=("--notification-channels=$GCP_MONITORING_NOTIFICATION_CHANNELS")
  fi
  gcloud monitoring policies create "${create_args[@]}" >/dev/null
  printf 'Created monitoring policy: %s\n' "$display_name"
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

GCP_PROJECT_ID="${GCP_PROJECT_ID:-}"
GCP_REGION="${GCP_REGION:-europe-west1}"
CLOUD_RUN_SERVICE="trading-bot-${TARGET_ENVIRONMENT}-main-usdm-futures"
CLOUD_SQL_INSTANCE="${GCP_CLOUD_SQL_INSTANCE:-trading-bot-postgres}"
TEMPLATE_DIR="ops/google-cloud/monitoring/alert-policies"
RENDER_DIR="build/google-cloud/monitoring/${TARGET_ENVIRONMENT}"
GCP_MONITORING_NOTIFICATION_CHANNELS="${GCP_MONITORING_NOTIFICATION_CHANNELS:-}"
VALIDATE_ONLY=false

while (($# > 0)); do
  case "$1" in
    --project)
      GCP_PROJECT_ID="${2:-}"
      shift 2
      ;;
    --region)
      GCP_REGION="${2:-}"
      shift 2
      ;;
    --service)
      CLOUD_RUN_SERVICE="${2:-}"
      shift 2
      ;;
    --cloud-sql-instance)
      CLOUD_SQL_INSTANCE="${2:-}"
      shift 2
      ;;
    --template-dir)
      TEMPLATE_DIR="${2:-}"
      shift 2
      ;;
    --render-dir)
      RENDER_DIR="${2:-}"
      shift 2
      ;;
    --notification-channels)
      GCP_MONITORING_NOTIFICATION_CHANNELS="${2:-}"
      shift 2
      ;;
    --validate-only)
      VALIDATE_ONLY=true
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

require_command python3
TEMPLATE_DIR="$(resolve_path "$TEMPLATE_DIR")"
mkdir -p "$RENDER_DIR"

if [[ "$VALIDATE_ONLY" != "true" ]]; then
  require_command gcloud
  if [[ -z "$GCP_PROJECT_ID" ]]; then
    GCP_PROJECT_ID="$(current_gcloud_project)"
  fi
fi

if [[ -z "$GCP_PROJECT_ID" ]]; then
  GCP_PROJECT_ID="validation-project"
fi

shopt -s nullglob
policy_templates=("$TEMPLATE_DIR"/*.json)
if ((${#policy_templates[@]} == 0)); then
  echo "No monitoring policy templates found in $TEMPLATE_DIR" >&2
  exit 1
fi

for template in "${policy_templates[@]}"; do
  rendered_policy="${RENDER_DIR}/$(basename "$template")"
  render_policy_template "$template" "$rendered_policy"
  if [[ "$VALIDATE_ONLY" == "true" ]]; then
    printf 'Validated monitoring policy template: %s\n' "$rendered_policy"
  else
    create_policy_if_missing "$rendered_policy"
  fi
done
