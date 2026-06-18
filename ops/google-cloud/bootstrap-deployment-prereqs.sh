#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Bootstrap Google Cloud prerequisites for trading-bot GitHub Actions deployment.

Required environment variables:
  BINANCE_DEMO_API_KEY
  BINANCE_DEMO_API_SECRET

Optional environment variables:
  GCP_PROJECT_ID                                      Default: current gcloud project
  GCP_REGION                                          Default: europe-west1
  GCP_ARTIFACT_REGISTRY_LOCATION                      Default: ${GCP_REGION}
  GCP_ARTIFACT_REGISTRY_REPOSITORY                    Default: trading-bot
  GITHUB_OWNER                                        Default: inferred from git remote, then manu1907
  GITHUB_REPO                                         Default: inferred from git remote, then trading-bot
  GITHUB_CONFIGURE_ENVIRONMENTS=true|false            Default: false
  GCP_CREATE_PROJECT=true|false                         Default: false
  GCP_BILLING_ACCOUNT                                  Required when creating a project or budget alerts
  GCP_WORKLOAD_IDENTITY_POOL_ID                        Default: github-actions
  GCP_WORKLOAD_IDENTITY_PROVIDER_ID                    Default: github-actions
  GCP_SERVICE_ACCOUNT_PREFIX                           Default: trading-bot
  TRADING_BOT_JOURNAL_ARCHIVE_BUCKET                   Default: ${GCP_PROJECT_ID}-trading-bot-journal-archive
  GCP_CLOUD_RUN_CPU                                    Default printed for GitHub vars: 1
  GCP_CLOUD_RUN_MEMORY                                 Default printed for GitHub vars: 1Gi
  GCP_CLOUD_RUN_MIN_INSTANCES                          Default printed for GitHub vars: 0
  GCP_CLOUD_RUN_MAX_INSTANCES                          Default printed for GitHub vars: 1
  GCP_CLOUD_RUN_TIMEOUT                                Default printed for GitHub vars: 300s
  GCP_CLOUD_SQL_INSTANCE                               Default: trading-bot-postgres
  GCP_CLOUD_SQL_DATABASE_VERSION                       Default: POSTGRES_16
  GCP_CLOUD_SQL_TIER                                   Default: db-custom-1-3840
  GCP_CLOUD_SQL_STORAGE_GB                             Default: 20
  GCP_CLOUD_SQL_AVAILABILITY_TYPE                      Default: ZONAL
  GCP_CLOUD_SQL_BACKUP_START_TIME                      Default: 03:00
  GCP_CLOUD_SQL_DELETION_PROTECTION=true|false         Default: true
  GCP_BUDGET_ALERTS_ENABLED=true|false                  Default: false
  GCP_BUDGET_DISPLAY_NAME                              Default: trading-bot-${GCP_PROJECT_ID}-monthly
  GCP_BUDGET_AMOUNT                                    Default: 250USD
  GCP_BUDGET_CALENDAR_PERIOD                           Default: month
  GCP_BUDGET_THRESHOLD_RULES                           Default: percent=0.50;percent=0.80;percent=1.00;percent=1.00,basis=forecasted-spend
  GCP_BUDGET_FILTER_PROJECTS                           Default: projects/${GCP_PROJECT_ID}
  GCP_BUDGET_PUBSUB_TOPIC                              Optional: projects/${GCP_PROJECT_ID}/topics/{topic}
  GCP_BUDGET_MONITORING_NOTIFICATION_CHANNELS          Optional comma list of projects/{project}/notificationChannels/{channel}
  GCP_BUDGET_DISABLE_DEFAULT_IAM_RECIPIENTS=true|false Default: false
  DEMO_CLOUD_SQL_DATABASE                              Default: trading_bot_demo
  DEMO_AUDIT_CLOUD_SQL_USERNAME                        Default: trading_bot_demo_audit
  DEMO_PROJECTION_CLOUD_SQL_USERNAME                   Default: trading_bot_demo_projection
  REAL_CLOUD_SQL_DATABASE                              Default: trading_bot_real
  REAL_AUDIT_CLOUD_SQL_USERNAME                        Default: trading_bot_real_audit
  REAL_PROJECTION_CLOUD_SQL_USERNAME                   Default: trading_bot_real_projection

Optional generated/secret value environment variables:
  DEMO_OPERATOR_TOKEN                                  Default: generated if no secret version exists
  DEMO_AUDIT_JDBC_URL
  DEMO_AUDIT_JDBC_USERNAME
  DEMO_AUDIT_JDBC_PASSWORD
  DEMO_PROJECTION_JDBC_URL
  DEMO_PROJECTION_JDBC_USERNAME
  DEMO_PROJECTION_JDBC_PASSWORD
  REAL_BINANCE_API_KEY
  REAL_BINANCE_API_SECRET
  REAL_OPERATOR_TOKEN                                  Default: generated if no secret version exists
  REAL_AUDIT_JDBC_URL
  REAL_AUDIT_JDBC_USERNAME
  REAL_AUDIT_JDBC_PASSWORD
  REAL_PROJECTION_JDBC_URL
  REAL_PROJECTION_JDBC_USERNAME
  REAL_PROJECTION_JDBC_PASSWORD
  DEMO_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  DEMO_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  DEMO_ALERT_SMTP_SMARTHOST
  DEMO_ALERT_SMTP_FROM
  DEMO_ALERT_SMTP_AUTH_USERNAME
  DEMO_ALERT_SMTP_AUTH_PASSWORD
  DEMO_ALERT_EMAIL_TO                                  Optional common default for demo operator/platform/fallback email
  DEMO_ALERT_OPERATOR_EMAIL_TO                         Default: ${DEMO_ALERT_EMAIL_TO}
  DEMO_ALERT_PLATFORM_EMAIL_TO                         Default: ${DEMO_ALERT_EMAIL_TO}
  DEMO_ALERT_FALLBACK_EMAIL_TO                         Default: ${DEMO_ALERT_EMAIL_TO}
  DEMO_ALERT_OPERATOR_SLACK_WEBHOOK
  DEMO_ALERT_OPERATOR_SLACK_CHANNEL
  DEMO_ALERT_PLATFORM_SLACK_WEBHOOK
  DEMO_ALERT_PLATFORM_SLACK_CHANNEL
  DEMO_ALERT_FALLBACK_SLACK_WEBHOOK
  DEMO_ALERT_FALLBACK_SLACK_CHANNEL
  REAL_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  REAL_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  REAL_ALERT_SMTP_SMARTHOST
  REAL_ALERT_SMTP_FROM
  REAL_ALERT_SMTP_AUTH_USERNAME
  REAL_ALERT_SMTP_AUTH_PASSWORD
  REAL_ALERT_EMAIL_TO                                  Optional common default for real operator/platform/fallback email
  REAL_ALERT_OPERATOR_EMAIL_TO                         Default: ${REAL_ALERT_EMAIL_TO}
  REAL_ALERT_PLATFORM_EMAIL_TO                         Default: ${REAL_ALERT_EMAIL_TO}
  REAL_ALERT_FALLBACK_EMAIL_TO                         Default: ${REAL_ALERT_EMAIL_TO}
  REAL_ALERT_OPERATOR_SLACK_WEBHOOK
  REAL_ALERT_OPERATOR_SLACK_CHANNEL
  REAL_ALERT_PLATFORM_SLACK_WEBHOOK
  REAL_ALERT_PLATFORM_SLACK_CHANNEL
  REAL_ALERT_FALLBACK_SLACK_WEBHOOK
  REAL_ALERT_FALLBACK_SLACK_CHANNEL

The script creates Google Cloud resources and IAM bindings idempotently. It does
not print secret values. If an alert secret value environment variable is
absent, the Secret Manager secret is created without adding a version;
deployment will still require a secret version before Cloud Run can bind
:latest. Operator tokens and Cloud SQL JDBC values are generated automatically
when absent and no enabled secret version exists.

Example:
  set -a
  source api.env
  set +a
  ./ops/google-cloud/bootstrap-deployment-prereqs.sh

Optional GitHub environment configuration:
  GITHUB_CONFIGURE_ENVIRONMENTS=true ./ops/google-cloud/bootstrap-deployment-prereqs.sh
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_env() {
  if [[ -z "${!1-}" ]]; then
    echo "Missing required environment variable: $1" >&2
    exit 1
  fi
}

log() {
  printf '\n==> %s\n' "$1"
}

infer_github_slug() {
  local remote
  remote="$(git remote get-url origin 2>/dev/null || true)"
  case "$remote" in
    https://github.com/*/*.git)
      remote="${remote#https://github.com/}"
      remote="${remote%.git}"
      ;;
    git@github.com:*/*.git)
      remote="${remote#git@github.com:}"
      remote="${remote%.git}"
      ;;
    *)
      remote=""
      ;;
  esac
  if [[ "$remote" == */* ]]; then
    GITHUB_OWNER="${GITHUB_OWNER:-${remote%%/*}}"
    GITHUB_REPO="${GITHUB_REPO:-${remote##*/}}"
  fi
}

infer_defaults() {
  local configured_project
  configured_project="$(gcloud config get-value project 2>/dev/null || true)"
  if [[ "$configured_project" == "(unset)" ]]; then
    configured_project=""
  fi
  GCP_PROJECT_ID="${GCP_PROJECT_ID:-$configured_project}"
  GCP_REGION="${GCP_REGION:-europe-west1}"
  GCP_ARTIFACT_REGISTRY_LOCATION="${GCP_ARTIFACT_REGISTRY_LOCATION:-$GCP_REGION}"
  GCP_ARTIFACT_REGISTRY_REPOSITORY="${GCP_ARTIFACT_REGISTRY_REPOSITORY:-trading-bot}"
  infer_github_slug
  GITHUB_OWNER="${GITHUB_OWNER:-manu1907}"
  GITHUB_REPO="${GITHUB_REPO:-trading-bot}"
  GITHUB_CONFIGURE_ENVIRONMENTS="${GITHUB_CONFIGURE_ENVIRONMENTS:-false}"
  GCP_WORKLOAD_IDENTITY_POOL_ID="${GCP_WORKLOAD_IDENTITY_POOL_ID:-github-actions}"
  GCP_WORKLOAD_IDENTITY_PROVIDER_ID="${GCP_WORKLOAD_IDENTITY_PROVIDER_ID:-github-actions}"
  GCP_SERVICE_ACCOUNT_PREFIX="${GCP_SERVICE_ACCOUNT_PREFIX:-trading-bot}"
  TRADING_BOT_JOURNAL_ARCHIVE_BUCKET="${TRADING_BOT_JOURNAL_ARCHIVE_BUCKET:-${GCP_PROJECT_ID}-trading-bot-journal-archive}"
  GCP_CLOUD_SQL_INSTANCE="${GCP_CLOUD_SQL_INSTANCE:-trading-bot-postgres}"
  GCP_CLOUD_SQL_DATABASE_VERSION="${GCP_CLOUD_SQL_DATABASE_VERSION:-POSTGRES_16}"
  GCP_CLOUD_SQL_TIER="${GCP_CLOUD_SQL_TIER:-db-custom-1-3840}"
  GCP_CLOUD_SQL_STORAGE_GB="${GCP_CLOUD_SQL_STORAGE_GB:-20}"
  GCP_CLOUD_SQL_AVAILABILITY_TYPE="${GCP_CLOUD_SQL_AVAILABILITY_TYPE:-ZONAL}"
  GCP_CLOUD_SQL_BACKUP_START_TIME="${GCP_CLOUD_SQL_BACKUP_START_TIME:-03:00}"
  GCP_CLOUD_SQL_DELETION_PROTECTION="${GCP_CLOUD_SQL_DELETION_PROTECTION:-true}"
  GCP_BUDGET_ALERTS_ENABLED="${GCP_BUDGET_ALERTS_ENABLED:-false}"
  GCP_BUDGET_DISPLAY_NAME="${GCP_BUDGET_DISPLAY_NAME:-trading-bot-${GCP_PROJECT_ID}-monthly}"
  GCP_BUDGET_AMOUNT="${GCP_BUDGET_AMOUNT:-250USD}"
  GCP_BUDGET_CALENDAR_PERIOD="${GCP_BUDGET_CALENDAR_PERIOD:-month}"
  GCP_BUDGET_THRESHOLD_RULES="${GCP_BUDGET_THRESHOLD_RULES:-percent=0.50;percent=0.80;percent=1.00;percent=1.00,basis=forecasted-spend}"
  GCP_BUDGET_FILTER_PROJECTS="${GCP_BUDGET_FILTER_PROJECTS:-projects/${GCP_PROJECT_ID}}"
  GCP_BUDGET_DISABLE_DEFAULT_IAM_RECIPIENTS="${GCP_BUDGET_DISABLE_DEFAULT_IAM_RECIPIENTS:-false}"
  DEMO_CLOUD_SQL_DATABASE="${DEMO_CLOUD_SQL_DATABASE:-trading_bot_demo}"
  DEMO_AUDIT_CLOUD_SQL_USERNAME="${DEMO_AUDIT_CLOUD_SQL_USERNAME:-trading_bot_demo_audit}"
  DEMO_PROJECTION_CLOUD_SQL_USERNAME="${DEMO_PROJECTION_CLOUD_SQL_USERNAME:-trading_bot_demo_projection}"
  REAL_CLOUD_SQL_DATABASE="${REAL_CLOUD_SQL_DATABASE:-trading_bot_real}"
  REAL_AUDIT_CLOUD_SQL_USERNAME="${REAL_AUDIT_CLOUD_SQL_USERNAME:-trading_bot_real_audit}"
  REAL_PROJECTION_CLOUD_SQL_USERNAME="${REAL_PROJECTION_CLOUD_SQL_USERNAME:-trading_bot_real_projection}"
  DEMO_ALERT_OPERATOR_EMAIL_TO="${DEMO_ALERT_OPERATOR_EMAIL_TO:-${DEMO_ALERT_EMAIL_TO:-}}"
  DEMO_ALERT_PLATFORM_EMAIL_TO="${DEMO_ALERT_PLATFORM_EMAIL_TO:-${DEMO_ALERT_EMAIL_TO:-}}"
  DEMO_ALERT_FALLBACK_EMAIL_TO="${DEMO_ALERT_FALLBACK_EMAIL_TO:-${DEMO_ALERT_EMAIL_TO:-}}"
  REAL_ALERT_OPERATOR_EMAIL_TO="${REAL_ALERT_OPERATOR_EMAIL_TO:-${REAL_ALERT_EMAIL_TO:-}}"
  REAL_ALERT_PLATFORM_EMAIL_TO="${REAL_ALERT_PLATFORM_EMAIL_TO:-${REAL_ALERT_EMAIL_TO:-}}"
  REAL_ALERT_FALLBACK_EMAIL_TO="${REAL_ALERT_FALLBACK_EMAIL_TO:-${REAL_ALERT_EMAIL_TO:-}}"
}

ensure_project() {
  if gcloud projects describe "$GCP_PROJECT_ID" >/dev/null 2>&1; then
    return
  fi
  if [[ "${GCP_CREATE_PROJECT:-false}" != "true" ]]; then
    echo "Project $GCP_PROJECT_ID does not exist. Set GCP_CREATE_PROJECT=true to create it." >&2
    exit 1
  fi
  if [[ -z "${GCP_BILLING_ACCOUNT:-}" ]]; then
    echo "GCP_BILLING_ACCOUNT is required when GCP_CREATE_PROJECT=true." >&2
    exit 1
  fi
  gcloud projects create "$GCP_PROJECT_ID" --name="${GCP_PROJECT_NAME:-$GCP_PROJECT_ID}"
  gcloud billing projects link "$GCP_PROJECT_ID" --billing-account="$GCP_BILLING_ACCOUNT"
}

ensure_api() {
  local service="$1"
  gcloud services enable "$service" --project="$GCP_PROJECT_ID" --quiet
}

ensure_artifact_registry_repository() {
  if gcloud artifacts repositories describe "$GCP_ARTIFACT_REGISTRY_REPOSITORY" \
      --project="$GCP_PROJECT_ID" \
      --location="$GCP_ARTIFACT_REGISTRY_LOCATION" >/dev/null 2>&1; then
    return
  fi
  gcloud artifacts repositories create "$GCP_ARTIFACT_REGISTRY_REPOSITORY" \
    --project="$GCP_PROJECT_ID" \
    --location="$GCP_ARTIFACT_REGISTRY_LOCATION" \
    --repository-format=docker \
    --description="trading-bot runtime images" \
    --quiet
}

ensure_bucket() {
  local bucket="$1"
  if gcloud storage buckets describe "gs://${bucket}" --project="$GCP_PROJECT_ID" >/dev/null 2>&1; then
    return
  fi
  gcloud storage buckets create "gs://${bucket}" \
    --project="$GCP_PROJECT_ID" \
    --location="$GCP_REGION" \
    --uniform-bucket-level-access \
    --public-access-prevention \
    --quiet
  gcloud storage buckets update "gs://${bucket}" --versioning --quiet
}

ensure_service_account() {
  local account_id="$1"
  local display_name="$2"
  local email="${account_id}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
  if gcloud iam service-accounts describe "$email" --project="$GCP_PROJECT_ID" >/dev/null 2>&1; then
    echo "$email"
    return
  fi
  gcloud iam service-accounts create "$account_id" \
    --project="$GCP_PROJECT_ID" \
    --display-name="$display_name" \
    --quiet >/dev/null
  echo "$email"
}

ensure_project_role() {
  local member="$1"
  local role="$2"
  gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
    --member="$member" \
    --role="$role" \
    --condition=None \
    --quiet >/dev/null
}

ensure_service_account_user() {
  local runtime_email="$1"
  local actor_email="$2"
  gcloud iam service-accounts add-iam-policy-binding "$runtime_email" \
    --project="$GCP_PROJECT_ID" \
    --member="serviceAccount:${actor_email}" \
    --role="roles/iam.serviceAccountUser" \
    --quiet >/dev/null
}

ensure_workload_identity_pool() {
  if gcloud iam workload-identity-pools describe "$GCP_WORKLOAD_IDENTITY_POOL_ID" \
      --project="$GCP_PROJECT_ID" \
      --location=global >/dev/null 2>&1; then
    return
  fi
  gcloud iam workload-identity-pools create "$GCP_WORKLOAD_IDENTITY_POOL_ID" \
    --project="$GCP_PROJECT_ID" \
    --location=global \
    --display-name="GitHub Actions" \
    --description="GitHub Actions OIDC identities for trading-bot" \
    --quiet
}

ensure_workload_identity_provider() {
  if gcloud iam workload-identity-pools providers describe "$GCP_WORKLOAD_IDENTITY_PROVIDER_ID" \
      --project="$GCP_PROJECT_ID" \
      --location=global \
      --workload-identity-pool="$GCP_WORKLOAD_IDENTITY_POOL_ID" >/dev/null 2>&1; then
    return
  fi
  gcloud iam workload-identity-pools providers create-oidc "$GCP_WORKLOAD_IDENTITY_PROVIDER_ID" \
    --project="$GCP_PROJECT_ID" \
    --location=global \
    --workload-identity-pool="$GCP_WORKLOAD_IDENTITY_POOL_ID" \
    --display-name="GitHub ${GITHUB_OWNER}/${GITHUB_REPO}" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
    --attribute-condition="assertion.repository == '${GITHUB_OWNER}/${GITHUB_REPO}'" \
    --quiet
}

allow_github_to_impersonate() {
  local service_account_email="$1"
  local project_number="$2"
  local principal="principalSet://iam.googleapis.com/projects/${project_number}/locations/global/workloadIdentityPools/${GCP_WORKLOAD_IDENTITY_POOL_ID}/attribute.repository/${GITHUB_OWNER}/${GITHUB_REPO}"
  gcloud iam service-accounts add-iam-policy-binding "$service_account_email" \
    --project="$GCP_PROJECT_ID" \
    --member="$principal" \
    --role="roles/iam.workloadIdentityUser" \
    --quiet >/dev/null
}

ensure_secret() {
  local name="$1"
  if gcloud secrets describe "$name" --project="$GCP_PROJECT_ID" >/dev/null 2>&1; then
    return
  fi
  gcloud secrets create "$name" \
    --project="$GCP_PROJECT_ID" \
    --replication-policy=automatic \
    --quiet >/dev/null
}

add_secret_version_from_env() {
  local secret_name="$1"
  local env_var="$2"
  local value="${!env_var-}"
  if [[ -z "$value" ]]; then
    MISSING_SECRET_VALUE_ENVS+=("$secret_name <= $env_var")
    return
  fi
  printf '%s' "$value" | gcloud secrets versions add "$secret_name" \
    --project="$GCP_PROJECT_ID" \
    --data-file=- \
    --quiet >/dev/null
  ADDED_SECRET_VALUE_ENVS+=("$secret_name <= $env_var")
}

secret_has_enabled_version() {
  local secret_name="$1"
  [[ -n "$(gcloud secrets versions list "$secret_name" \
    --project="$GCP_PROJECT_ID" \
    --filter='state:enabled' \
    --limit=1 \
    --format='value(name)' 2>/dev/null)" ]]
}

generate_secret_value() {
  openssl rand -base64 48 | tr -d '\n'
}

ensure_secret_with_generated_fallback() {
  local secret_name="$1"
  local env_var="$2"
  ensure_secret "$secret_name"
  if [[ -n "${!env_var-}" ]]; then
    add_secret_version_from_env "$secret_name" "$env_var"
    return
  fi
  if secret_has_enabled_version "$secret_name"; then
    REUSED_SECRET_VALUE_ENVS+=("$secret_name")
    return
  fi
  generate_secret_value | gcloud secrets versions add "$secret_name" \
    --project="$GCP_PROJECT_ID" \
    --data-file=- \
    --quiet >/dev/null
  GENERATED_SECRET_VALUE_ENVS+=("$secret_name")
}

ensure_secret_with_optional_version() {
  local secret_name="$1"
  local env_var="$2"
  ensure_secret "$secret_name"
  add_secret_version_from_env "$secret_name" "$env_var"
}

add_secret_version_from_value() {
  local secret_name="$1"
  local value="$2"
  local source_label="$3"
  printf '%s' "$value" | gcloud secrets versions add "$secret_name" \
    --project="$GCP_PROJECT_ID" \
    --data-file=- \
    --quiet >/dev/null
  ADDED_SECRET_VALUE_ENVS+=("$secret_name <= $source_label")
}

secret_latest_value() {
  local secret_name="$1"
  gcloud secrets versions access latest \
    --secret "$secret_name" \
    --project="$GCP_PROJECT_ID" 2>/dev/null || true
}

ensure_secret_with_literal_fallback() {
  local secret_name="$1"
  local env_var="$2"
  local fallback_value="$3"
  local fallback_label="$4"
  ensure_secret "$secret_name"
  if [[ -n "${!env_var-}" ]]; then
    add_secret_version_from_env "$secret_name" "$env_var"
    return
  fi
  if secret_has_enabled_version "$secret_name"; then
    REUSED_SECRET_VALUE_ENVS+=("$secret_name")
    return
  fi
  add_secret_version_from_value "$secret_name" "$fallback_value" "$fallback_label"
}

ensure_cloud_sql_instance() {
  if gcloud sql instances describe "$GCP_CLOUD_SQL_INSTANCE" \
      --project="$GCP_PROJECT_ID" >/dev/null 2>&1; then
    return
  fi
  local create_args=(
    "$GCP_CLOUD_SQL_INSTANCE"
    "--project=$GCP_PROJECT_ID"
    "--database-version=$GCP_CLOUD_SQL_DATABASE_VERSION"
    "--region=$GCP_REGION"
    "--tier=$GCP_CLOUD_SQL_TIER"
    "--storage-type=SSD"
    "--storage-size=${GCP_CLOUD_SQL_STORAGE_GB}GB"
    "--availability-type=$GCP_CLOUD_SQL_AVAILABILITY_TYPE"
    "--backup-start-time=$GCP_CLOUD_SQL_BACKUP_START_TIME"
    "--quiet"
  )
  if [[ "$GCP_CLOUD_SQL_DELETION_PROTECTION" == "true" ]]; then
    create_args+=("--deletion-protection")
  fi
  gcloud sql instances create "${create_args[@]}"
}

ensure_cloud_sql_database() {
  local database="$1"
  if gcloud sql databases describe "$database" \
      --instance="$GCP_CLOUD_SQL_INSTANCE" \
      --project="$GCP_PROJECT_ID" >/dev/null 2>&1; then
    return
  fi
  gcloud sql databases create "$database" \
    --instance="$GCP_CLOUD_SQL_INSTANCE" \
    --project="$GCP_PROJECT_ID" \
    --quiet >/dev/null
}

cloud_sql_user_exists() {
  local username="$1"
  gcloud sql users list \
    --instance="$GCP_CLOUD_SQL_INSTANCE" \
    --project="$GCP_PROJECT_ID" \
    --format='value(name)' 2>/dev/null | grep -Fx -- "$username" >/dev/null
}

ensure_cloud_sql_user_password() {
  local username="$1"
  local password_secret="$2"
  local password_env_var="$3"
  local sql_user_pass
  ensure_secret "$password_secret"
  if [[ -n "${!password_env_var-}" ]]; then
    sql_user_pass="${!password_env_var}"
    add_secret_version_from_env "$password_secret" "$password_env_var"
  elif secret_has_enabled_version "$password_secret"; then
    sql_user_pass="$(secret_latest_value "$password_secret")"
    REUSED_SECRET_VALUE_ENVS+=("$password_secret")
  else
    sql_user_pass="$(generate_secret_value)"
    add_secret_version_from_value "$password_secret" "$sql_user_pass" "generated Cloud SQL password"
  fi

  if cloud_sql_user_exists "$username"; then
    gcloud sql users set-password "$username" \
      --instance="$GCP_CLOUD_SQL_INSTANCE" \
      --project="$GCP_PROJECT_ID" \
      --password "$sql_user_pass" \
      --quiet >/dev/null
  else
    gcloud sql users create "$username" \
      --instance="$GCP_CLOUD_SQL_INSTANCE" \
      --project="$GCP_PROJECT_ID" \
      --password "$sql_user_pass" \
      --quiet >/dev/null
  fi
}

cloud_sql_postgres_jdbc_url() {
  local database="$1"
  local connection_name="${GCP_PROJECT_ID}:${GCP_REGION}:${GCP_CLOUD_SQL_INSTANCE}"
  local jdbc_scheme="jdbc:postgresql:"
  printf '%s///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable' \
    "$jdbc_scheme" \
    "$database" \
    "$connection_name"
}

ensure_cloud_sql_runtime() {
  ensure_cloud_sql_instance
  ensure_cloud_sql_database "$DEMO_CLOUD_SQL_DATABASE"
  ensure_cloud_sql_database "$REAL_CLOUD_SQL_DATABASE"

  ensure_cloud_sql_user_password "$DEMO_AUDIT_CLOUD_SQL_USERNAME" trading-bot-demo-audit-jdbc-password DEMO_AUDIT_JDBC_PASSWORD
  ensure_cloud_sql_user_password "$DEMO_PROJECTION_CLOUD_SQL_USERNAME" trading-bot-demo-projection-jdbc-password DEMO_PROJECTION_JDBC_PASSWORD
  ensure_cloud_sql_user_password "$REAL_AUDIT_CLOUD_SQL_USERNAME" trading-bot-real-audit-jdbc-password REAL_AUDIT_JDBC_PASSWORD
  ensure_cloud_sql_user_password "$REAL_PROJECTION_CLOUD_SQL_USERNAME" trading-bot-real-projection-jdbc-password REAL_PROJECTION_JDBC_PASSWORD

  ensure_secret_with_literal_fallback trading-bot-demo-audit-jdbc-url \
    DEMO_AUDIT_JDBC_URL \
    "$(cloud_sql_postgres_jdbc_url "$DEMO_CLOUD_SQL_DATABASE")" \
    "generated Cloud SQL JDBC URL"
  ensure_secret_with_literal_fallback trading-bot-demo-audit-jdbc-username \
    DEMO_AUDIT_JDBC_USERNAME \
    "$DEMO_AUDIT_CLOUD_SQL_USERNAME" \
    "generated Cloud SQL username"
  ensure_secret_with_literal_fallback trading-bot-demo-projection-jdbc-url \
    DEMO_PROJECTION_JDBC_URL \
    "$(cloud_sql_postgres_jdbc_url "$DEMO_CLOUD_SQL_DATABASE")" \
    "generated Cloud SQL JDBC URL"
  ensure_secret_with_literal_fallback trading-bot-demo-projection-jdbc-username \
    DEMO_PROJECTION_JDBC_USERNAME \
    "$DEMO_PROJECTION_CLOUD_SQL_USERNAME" \
    "generated Cloud SQL username"

  ensure_secret_with_literal_fallback trading-bot-real-audit-jdbc-url \
    REAL_AUDIT_JDBC_URL \
    "$(cloud_sql_postgres_jdbc_url "$REAL_CLOUD_SQL_DATABASE")" \
    "generated Cloud SQL JDBC URL"
  ensure_secret_with_literal_fallback trading-bot-real-audit-jdbc-username \
    REAL_AUDIT_JDBC_USERNAME \
    "$REAL_AUDIT_CLOUD_SQL_USERNAME" \
    "generated Cloud SQL username"
  ensure_secret_with_literal_fallback trading-bot-real-projection-jdbc-url \
    REAL_PROJECTION_JDBC_URL \
    "$(cloud_sql_postgres_jdbc_url "$REAL_CLOUD_SQL_DATABASE")" \
    "generated Cloud SQL JDBC URL"
  ensure_secret_with_literal_fallback trading-bot-real-projection-jdbc-username \
    REAL_PROJECTION_JDBC_USERNAME \
    "$REAL_PROJECTION_CLOUD_SQL_USERNAME" \
    "generated Cloud SQL username"
}

budget_exists() {
  gcloud billing budgets list \
    --billing-account="$GCP_BILLING_ACCOUNT" \
    --format='value(displayName)' 2>/dev/null | grep -Fx -- "$GCP_BUDGET_DISPLAY_NAME" >/dev/null
}

ensure_budget_alert() {
  if [[ "$GCP_BUDGET_ALERTS_ENABLED" != "true" ]]; then
    BUDGET_ALERTS_STATE="disabled"
    return
  fi
  require_env GCP_BILLING_ACCOUNT
  if budget_exists; then
    BUDGET_ALERTS_STATE="existing"
    return
  fi

  local create_args=(
    "--billing-account=$GCP_BILLING_ACCOUNT"
    "--display-name=$GCP_BUDGET_DISPLAY_NAME"
    "--budget-amount=$GCP_BUDGET_AMOUNT"
    "--calendar-period=$GCP_BUDGET_CALENDAR_PERIOD"
    "--filter-projects=$GCP_BUDGET_FILTER_PROJECTS"
  )
  local threshold_rule
  local old_ifs="$IFS"
  IFS=';'
  for threshold_rule in $GCP_BUDGET_THRESHOLD_RULES; do
    if [[ -n "$threshold_rule" ]]; then
      create_args+=("--threshold-rule=$threshold_rule")
    fi
  done
  IFS="$old_ifs"
  if [[ -n "${GCP_BUDGET_PUBSUB_TOPIC:-}" ]]; then
    create_args+=("--notifications-rule-pubsub-topic=$GCP_BUDGET_PUBSUB_TOPIC")
  fi
  if [[ -n "${GCP_BUDGET_MONITORING_NOTIFICATION_CHANNELS:-}" ]]; then
    create_args+=("--notifications-rule-monitoring-notification-channels=$GCP_BUDGET_MONITORING_NOTIFICATION_CHANNELS")
  fi
  if [[ "$GCP_BUDGET_DISABLE_DEFAULT_IAM_RECIPIENTS" == "true" ]]; then
    create_args+=("--disable-default-iam-recipients")
  fi
  gcloud billing budgets create "${create_args[@]}" --quiet >/dev/null
  BUDGET_ALERTS_STATE="created"
}

github_repo_slug() {
  printf '%s/%s' "$GITHUB_OWNER" "$GITHUB_REPO"
}

ensure_github_environment() {
  local environment="$1"
  gh api \
    --method PUT \
    "repos/$(github_repo_slug)/environments/${environment}" >/dev/null
}

set_github_environment_secret() {
  local environment="$1"
  local secret_name="$2"
  local secret_value="$3"
  printf '%s' "$secret_value" | gh secret set "$secret_name" \
    --repo "$(github_repo_slug)" \
    --env "$environment" >/dev/null
}

set_github_environment_variable() {
  local environment="$1"
  local variable_name="$2"
  local variable_value="$3"
  gh variable set "$variable_name" \
    --repo "$(github_repo_slug)" \
    --env "$environment" \
    --body "$variable_value" >/dev/null
}

configure_github_environment() {
  local environment="$1"
  local provider="$2"
  ensure_github_environment "$environment"

  set_github_environment_secret "$environment" GCP_WORKLOAD_IDENTITY_PROVIDER "$provider"
  set_github_environment_secret "$environment" GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT "$ARTIFACT_REGISTRY_SA"
  set_github_environment_secret "$environment" GCP_CLOUD_RUN_DEPLOY_SERVICE_ACCOUNT "$CLOUD_RUN_DEPLOY_SA"
  set_github_environment_secret "$environment" GCP_CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT "$CLOUD_RUN_RUNTIME_SA"
  set_github_environment_secret "$environment" GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT "$CLOUD_RUN_SMOKE_SA"
  set_github_environment_secret "$environment" GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT "$CLOUD_RUN_ROLLBACK_SA"

  set_github_environment_variable "$environment" GCP_PROJECT_ID "$GCP_PROJECT_ID"
  set_github_environment_variable "$environment" GCP_REGION "$GCP_REGION"
  set_github_environment_variable "$environment" GCP_ARTIFACT_REGISTRY_LOCATION "$GCP_ARTIFACT_REGISTRY_LOCATION"
  set_github_environment_variable "$environment" GCP_ARTIFACT_REGISTRY_REPOSITORY "$GCP_ARTIFACT_REGISTRY_REPOSITORY"
  set_github_environment_variable "$environment" GCP_CLOUD_RUN_CPU "${GCP_CLOUD_RUN_CPU:-1}"
  set_github_environment_variable "$environment" GCP_CLOUD_RUN_MEMORY "${GCP_CLOUD_RUN_MEMORY:-1Gi}"
  set_github_environment_variable "$environment" GCP_CLOUD_RUN_MIN_INSTANCES "${GCP_CLOUD_RUN_MIN_INSTANCES:-0}"
  set_github_environment_variable "$environment" GCP_CLOUD_RUN_MAX_INSTANCES "${GCP_CLOUD_RUN_MAX_INSTANCES:-1}"
  set_github_environment_variable "$environment" GCP_CLOUD_RUN_TIMEOUT "${GCP_CLOUD_RUN_TIMEOUT:-300s}"
  set_github_environment_variable "$environment" GCP_CLOUD_SQL_INSTANCE "$GCP_CLOUD_SQL_INSTANCE"
}

configure_github_environments() {
  local project_number="$1"
  local provider="projects/${project_number}/locations/global/workloadIdentityPools/${GCP_WORKLOAD_IDENTITY_POOL_ID}/providers/${GCP_WORKLOAD_IDENTITY_PROVIDER_ID}"
  require_command gh
  configure_github_environment demo "$provider"
  configure_github_environment real "$provider"
  GITHUB_ENVIRONMENTS_CONFIGURED=true
}

print_github_configuration() {
  local project_number="$1"
  local provider="projects/${project_number}/locations/global/workloadIdentityPools/${GCP_WORKLOAD_IDENTITY_POOL_ID}/providers/${GCP_WORKLOAD_IDENTITY_PROVIDER_ID}"

  cat <<CONFIG

Google Cloud bootstrap complete.

Set these GitHub environment secrets for both demo and real environments:
  GCP_WORKLOAD_IDENTITY_PROVIDER=${provider}
  GCP_ARTIFACT_REGISTRY_SERVICE_ACCOUNT=${ARTIFACT_REGISTRY_SA}
  GCP_CLOUD_RUN_DEPLOY_SERVICE_ACCOUNT=${CLOUD_RUN_DEPLOY_SA}
  GCP_CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT=${CLOUD_RUN_RUNTIME_SA}
  GCP_CLOUD_RUN_SMOKE_SERVICE_ACCOUNT=${CLOUD_RUN_SMOKE_SA}
  GCP_CLOUD_RUN_ROLLBACK_SERVICE_ACCOUNT=${CLOUD_RUN_ROLLBACK_SA}

Set these GitHub environment variables for both demo and real environments:
  GCP_PROJECT_ID=${GCP_PROJECT_ID}
  GCP_REGION=${GCP_REGION}
  GCP_ARTIFACT_REGISTRY_LOCATION=${GCP_ARTIFACT_REGISTRY_LOCATION}
  GCP_ARTIFACT_REGISTRY_REPOSITORY=${GCP_ARTIFACT_REGISTRY_REPOSITORY}
  GCP_CLOUD_RUN_CPU=${GCP_CLOUD_RUN_CPU:-1}
  GCP_CLOUD_RUN_MEMORY=${GCP_CLOUD_RUN_MEMORY:-1Gi}
  GCP_CLOUD_RUN_MIN_INSTANCES=${GCP_CLOUD_RUN_MIN_INSTANCES:-0}
  GCP_CLOUD_RUN_MAX_INSTANCES=${GCP_CLOUD_RUN_MAX_INSTANCES:-1}
  GCP_CLOUD_RUN_TIMEOUT=${GCP_CLOUD_RUN_TIMEOUT:-300s}
  GCP_CLOUD_SQL_INSTANCE=${GCP_CLOUD_SQL_INSTANCE}

Optional automation:
  Set GITHUB_CONFIGURE_ENVIRONMENTS=true and authenticate gh to create/update
  the demo and real GitHub environments, secrets, and variables automatically.

Journal archive bucket created or verified:
  gs://${TRADING_BOT_JOURNAL_ARCHIVE_BUCKET}

Cloud SQL/PostgreSQL created or verified:
  ${GCP_PROJECT_ID}:${GCP_REGION}:${GCP_CLOUD_SQL_INSTANCE}
  demo database: ${DEMO_CLOUD_SQL_DATABASE}
  demo users: ${DEMO_AUDIT_CLOUD_SQL_USERNAME}, ${DEMO_PROJECTION_CLOUD_SQL_USERNAME}
  real database: ${REAL_CLOUD_SQL_DATABASE}
  real users: ${REAL_AUDIT_CLOUD_SQL_USERNAME}, ${REAL_PROJECTION_CLOUD_SQL_USERNAME}

Google Cloud budget alerts:
  state: ${BUDGET_ALERTS_STATE}
  display name: ${GCP_BUDGET_DISPLAY_NAME}
  amount: ${GCP_BUDGET_AMOUNT}
  thresholds: ${GCP_BUDGET_THRESHOLD_RULES}
CONFIG

  if ((${#ADDED_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nSecret versions added from environment variables:\n'
    printf '  %s\n' "${ADDED_SECRET_VALUE_ENVS[@]}"
  fi

  if ((${#GENERATED_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nSecret versions generated automatically:\n'
    printf '  %s\n' "${GENERATED_SECRET_VALUE_ENVS[@]}"
  fi

  if ((${#REUSED_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nExisting enabled secret versions reused:\n'
    printf '  %s\n' "${REUSED_SECRET_VALUE_ENVS[@]}"
  fi

  if ((${#MISSING_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nSecret containers created/verified without versions because these env vars were not set:\n'
    printf '  %s\n' "${MISSING_SECRET_VALUE_ENVS[@]}"
  fi

  if [[ "${GITHUB_ENVIRONMENTS_CONFIGURED:-false}" == "true" ]]; then
    printf '\nGitHub environments configured:\n'
    printf '  demo\n'
    printf '  real\n'
  fi
}

main() {
  require_command gcloud
  require_command git
  require_command openssl
  infer_defaults
  require_env GCP_PROJECT_ID
  require_env BINANCE_DEMO_API_KEY
  require_env BINANCE_DEMO_API_SECRET
  ADDED_SECRET_VALUE_ENVS=()
  GENERATED_SECRET_VALUE_ENVS=()
  REUSED_SECRET_VALUE_ENVS=()
  MISSING_SECRET_VALUE_ENVS=()
  GITHUB_ENVIRONMENTS_CONFIGURED=false
  BUDGET_ALERTS_STATE=disabled

  log "Checking project"
  ensure_project
  gcloud config set project "$GCP_PROJECT_ID" >/dev/null
  local project_number
  project_number="$(gcloud projects describe "$GCP_PROJECT_ID" --format='value(projectNumber)')"

  log "Enabling required APIs"
  ensure_api iam.googleapis.com
  ensure_api iamcredentials.googleapis.com
  ensure_api cloudresourcemanager.googleapis.com
  ensure_api serviceusage.googleapis.com
  ensure_api artifactregistry.googleapis.com
  ensure_api run.googleapis.com
  ensure_api secretmanager.googleapis.com
  ensure_api storage.googleapis.com
  ensure_api sqladmin.googleapis.com
  if [[ "$GCP_BUDGET_ALERTS_ENABLED" == "true" ]]; then
    ensure_api billingbudgets.googleapis.com
  fi

  log "Creating Artifact Registry repository"
  ensure_artifact_registry_repository

  log "Creating journal archive bucket"
  ensure_bucket "$TRADING_BOT_JOURNAL_ARCHIVE_BUCKET"

  log "Creating service accounts"
  ARTIFACT_REGISTRY_SA="$(ensure_service_account "${GCP_SERVICE_ACCOUNT_PREFIX}-artifact-publisher" "trading-bot Artifact Registry publisher")"
  CLOUD_RUN_DEPLOY_SA="$(ensure_service_account "${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-deployer" "trading-bot Cloud Run deployer")"
  CLOUD_RUN_RUNTIME_SA="$(ensure_service_account "${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-runtime" "trading-bot Cloud Run runtime")"
  CLOUD_RUN_SMOKE_SA="$(ensure_service_account "${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-smoke" "trading-bot Cloud Run smoke")"
  CLOUD_RUN_ROLLBACK_SA="$(ensure_service_account "${GCP_SERVICE_ACCOUNT_PREFIX}-cloud-run-rollback" "trading-bot Cloud Run rollback")"

  log "Granting IAM roles"
  ensure_project_role "serviceAccount:${ARTIFACT_REGISTRY_SA}" roles/artifactregistry.writer
  ensure_project_role "serviceAccount:${CLOUD_RUN_DEPLOY_SA}" roles/run.admin
  ensure_project_role "serviceAccount:${CLOUD_RUN_DEPLOY_SA}" roles/artifactregistry.reader
  ensure_project_role "serviceAccount:${CLOUD_RUN_RUNTIME_SA}" roles/secretmanager.secretAccessor
  ensure_project_role "serviceAccount:${CLOUD_RUN_RUNTIME_SA}" roles/cloudsql.client
  ensure_project_role "serviceAccount:${CLOUD_RUN_RUNTIME_SA}" roles/storage.objectAdmin
  ensure_project_role "serviceAccount:${CLOUD_RUN_SMOKE_SA}" roles/run.viewer
  ensure_project_role "serviceAccount:${CLOUD_RUN_SMOKE_SA}" roles/run.invoker
  ensure_project_role "serviceAccount:${CLOUD_RUN_ROLLBACK_SA}" roles/run.admin
  ensure_project_role "serviceAccount:${CLOUD_RUN_ROLLBACK_SA}" roles/run.invoker
  ensure_service_account_user "$CLOUD_RUN_RUNTIME_SA" "$CLOUD_RUN_DEPLOY_SA"

  log "Configuring Workload Identity Federation"
  ensure_workload_identity_pool
  ensure_workload_identity_provider
  allow_github_to_impersonate "$ARTIFACT_REGISTRY_SA" "$project_number"
  allow_github_to_impersonate "$CLOUD_RUN_DEPLOY_SA" "$project_number"
  allow_github_to_impersonate "$CLOUD_RUN_SMOKE_SA" "$project_number"
  allow_github_to_impersonate "$CLOUD_RUN_ROLLBACK_SA" "$project_number"

  log "Creating Secret Manager secrets and adding supplied versions"
  ensure_secret_with_optional_version trading-bot-demo-binance-api-key BINANCE_DEMO_API_KEY
  ensure_secret_with_optional_version trading-bot-demo-binance-api-secret BINANCE_DEMO_API_SECRET
  ensure_secret_with_generated_fallback trading-bot-demo-operator-token DEMO_OPERATOR_TOKEN
  ensure_secret_with_optional_version trading-bot-real-binance-api-key REAL_BINANCE_API_KEY
  ensure_secret_with_optional_version trading-bot-real-binance-api-secret REAL_BINANCE_API_SECRET
  ensure_secret_with_generated_fallback trading-bot-real-operator-token REAL_OPERATOR_TOKEN

  log "Creating Cloud SQL PostgreSQL runtime and JDBC secrets"
  ensure_cloud_sql_runtime

  log "Creating alert Secret Manager secrets and adding supplied versions"
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-pagerduty-routing-key DEMO_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-pagerduty-routing-key DEMO_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-demo-alert-smtp-smarthost DEMO_ALERT_SMTP_SMARTHOST
  ensure_secret_with_optional_version trading-bot-demo-alert-smtp-from DEMO_ALERT_SMTP_FROM
  ensure_secret_with_optional_version trading-bot-demo-alert-smtp-auth-username DEMO_ALERT_SMTP_AUTH_USERNAME
  ensure_secret_with_optional_version trading-bot-demo-alert-smtp-auth-password DEMO_ALERT_SMTP_AUTH_PASSWORD
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-email-to DEMO_ALERT_OPERATOR_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-email-to DEMO_ALERT_PLATFORM_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-demo-alert-fallback-email-to DEMO_ALERT_FALLBACK_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-slack-webhook DEMO_ALERT_OPERATOR_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-slack-channel DEMO_ALERT_OPERATOR_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-slack-webhook DEMO_ALERT_PLATFORM_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-slack-channel DEMO_ALERT_PLATFORM_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-demo-alert-fallback-slack-webhook DEMO_ALERT_FALLBACK_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-fallback-slack-channel DEMO_ALERT_FALLBACK_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-operator-pagerduty-routing-key REAL_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-real-alert-platform-pagerduty-routing-key REAL_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-real-alert-smtp-smarthost REAL_ALERT_SMTP_SMARTHOST
  ensure_secret_with_optional_version trading-bot-real-alert-smtp-from REAL_ALERT_SMTP_FROM
  ensure_secret_with_optional_version trading-bot-real-alert-smtp-auth-username REAL_ALERT_SMTP_AUTH_USERNAME
  ensure_secret_with_optional_version trading-bot-real-alert-smtp-auth-password REAL_ALERT_SMTP_AUTH_PASSWORD
  ensure_secret_with_optional_version trading-bot-real-alert-operator-email-to REAL_ALERT_OPERATOR_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-real-alert-platform-email-to REAL_ALERT_PLATFORM_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-real-alert-fallback-email-to REAL_ALERT_FALLBACK_EMAIL_TO
  ensure_secret_with_optional_version trading-bot-real-alert-operator-slack-webhook REAL_ALERT_OPERATOR_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-operator-slack-channel REAL_ALERT_OPERATOR_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-platform-slack-webhook REAL_ALERT_PLATFORM_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-platform-slack-channel REAL_ALERT_PLATFORM_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-fallback-slack-webhook REAL_ALERT_FALLBACK_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-fallback-slack-channel REAL_ALERT_FALLBACK_SLACK_CHANNEL

  log "Creating optional Google Cloud budget alerts"
  ensure_budget_alert

  if [[ "$GITHUB_CONFIGURE_ENVIRONMENTS" == "true" ]]; then
    log "Configuring GitHub deployment environments"
    configure_github_environments "$project_number"
  fi

  print_github_configuration "$project_number"
}

main "$@"
