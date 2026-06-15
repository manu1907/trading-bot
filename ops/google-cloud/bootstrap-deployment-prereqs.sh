#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Bootstrap Google Cloud prerequisites for trading-bot GitHub Actions deployment.

Required environment variables:
  GCP_PROJECT_ID
  GCP_REGION
  GCP_ARTIFACT_REGISTRY_LOCATION
  GCP_ARTIFACT_REGISTRY_REPOSITORY
  GITHUB_OWNER
  GITHUB_REPO

Optional environment variables:
  GCP_CREATE_PROJECT=true|false                         Default: false
  GCP_BILLING_ACCOUNT                                  Required only when creating a project
  GCP_WORKLOAD_IDENTITY_POOL_ID                        Default: github-actions
  GCP_WORKLOAD_IDENTITY_PROVIDER_ID                    Default: github-actions
  GCP_SERVICE_ACCOUNT_PREFIX                           Default: trading-bot
  TRADING_BOT_JOURNAL_ARCHIVE_BUCKET                   Default: ${GCP_PROJECT_ID}-trading-bot-journal-archive
  GCP_CLOUD_RUN_CPU                                    Default printed for GitHub vars: 1
  GCP_CLOUD_RUN_MEMORY                                 Default printed for GitHub vars: 1Gi
  GCP_CLOUD_RUN_MIN_INSTANCES                          Default printed for GitHub vars: 0
  GCP_CLOUD_RUN_MAX_INSTANCES                          Default printed for GitHub vars: 1
  GCP_CLOUD_RUN_TIMEOUT                                Default printed for GitHub vars: 300s

Optional secret value environment variables:
  DEMO_BINANCE_API_KEY
  DEMO_BINANCE_API_SECRET
  DEMO_OPERATOR_TOKEN
  DEMO_AUDIT_JDBC_URL
  DEMO_AUDIT_JDBC_USERNAME
  DEMO_AUDIT_JDBC_PASSWORD
  DEMO_PROJECTION_JDBC_URL
  DEMO_PROJECTION_JDBC_USERNAME
  DEMO_PROJECTION_JDBC_PASSWORD
  REAL_BINANCE_API_KEY
  REAL_BINANCE_API_SECRET
  REAL_OPERATOR_TOKEN
  REAL_AUDIT_JDBC_URL
  REAL_AUDIT_JDBC_USERNAME
  REAL_AUDIT_JDBC_PASSWORD
  REAL_PROJECTION_JDBC_URL
  REAL_PROJECTION_JDBC_USERNAME
  REAL_PROJECTION_JDBC_PASSWORD
  DEMO_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  DEMO_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  DEMO_ALERT_OPERATOR_SLACK_WEBHOOK
  DEMO_ALERT_OPERATOR_SLACK_CHANNEL
  DEMO_ALERT_PLATFORM_SLACK_WEBHOOK
  DEMO_ALERT_PLATFORM_SLACK_CHANNEL
  DEMO_ALERT_FALLBACK_SLACK_WEBHOOK
  DEMO_ALERT_FALLBACK_SLACK_CHANNEL
  REAL_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  REAL_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  REAL_ALERT_OPERATOR_SLACK_WEBHOOK
  REAL_ALERT_OPERATOR_SLACK_CHANNEL
  REAL_ALERT_PLATFORM_SLACK_WEBHOOK
  REAL_ALERT_PLATFORM_SLACK_CHANNEL
  REAL_ALERT_FALLBACK_SLACK_WEBHOOK
  REAL_ALERT_FALLBACK_SLACK_CHANNEL

The script creates Google Cloud resources and IAM bindings idempotently. It does
not print secret values. If a secret value environment variable is absent, the
Secret Manager secret is created without adding a version; deployment will still
require a secret version before Cloud Run can bind :latest.

Example:
  export GCP_PROJECT_ID=my-trading-bot-demo
  export GCP_REGION=europe-west1
  export GCP_ARTIFACT_REGISTRY_LOCATION=europe-west1
  export GCP_ARTIFACT_REGISTRY_REPOSITORY=trading-bot
  export GITHUB_OWNER=manu1907
  export GITHUB_REPO=trading-bot
  ./ops/google-cloud/bootstrap-deployment-prereqs.sh
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

run_quiet() {
  "$@" >/dev/null
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

ensure_secret_with_optional_version() {
  local secret_name="$1"
  local env_var="$2"
  ensure_secret "$secret_name"
  add_secret_version_from_env "$secret_name" "$env_var"
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

Journal archive bucket created or verified:
  gs://${TRADING_BOT_JOURNAL_ARCHIVE_BUCKET}

Cloud SQL/PostgreSQL is not created by this bootstrap script yet. Create the
managed PostgreSQL backend and add versions for these JDBC secrets before using
Cloud Run deployment:
  trading-bot-demo-audit-jdbc-url
  trading-bot-demo-audit-jdbc-username
  trading-bot-demo-audit-jdbc-password
  trading-bot-demo-projection-jdbc-url
  trading-bot-demo-projection-jdbc-username
  trading-bot-demo-projection-jdbc-password
  trading-bot-real-audit-jdbc-url
  trading-bot-real-audit-jdbc-username
  trading-bot-real-audit-jdbc-password
  trading-bot-real-projection-jdbc-url
  trading-bot-real-projection-jdbc-username
  trading-bot-real-projection-jdbc-password
CONFIG

  if ((${#ADDED_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nSecret versions added from environment variables:\n'
    printf '  %s\n' "${ADDED_SECRET_VALUE_ENVS[@]}"
  fi

  if ((${#MISSING_SECRET_VALUE_ENVS[@]} > 0)); then
    printf '\nSecret containers created/verified without versions because these env vars were not set:\n'
    printf '  %s\n' "${MISSING_SECRET_VALUE_ENVS[@]}"
  fi
}

main() {
  require_command gcloud
  require_env GCP_PROJECT_ID
  require_env GCP_REGION
  require_env GCP_ARTIFACT_REGISTRY_LOCATION
  require_env GCP_ARTIFACT_REGISTRY_REPOSITORY
  require_env GITHUB_OWNER
  require_env GITHUB_REPO

  GCP_WORKLOAD_IDENTITY_POOL_ID="${GCP_WORKLOAD_IDENTITY_POOL_ID:-github-actions}"
  GCP_WORKLOAD_IDENTITY_PROVIDER_ID="${GCP_WORKLOAD_IDENTITY_PROVIDER_ID:-github-actions}"
  GCP_SERVICE_ACCOUNT_PREFIX="${GCP_SERVICE_ACCOUNT_PREFIX:-trading-bot}"
  TRADING_BOT_JOURNAL_ARCHIVE_BUCKET="${TRADING_BOT_JOURNAL_ARCHIVE_BUCKET:-${GCP_PROJECT_ID}-trading-bot-journal-archive}"
  ADDED_SECRET_VALUE_ENVS=()
  MISSING_SECRET_VALUE_ENVS=()

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
  ensure_secret_with_optional_version trading-bot-demo-binance-api-key DEMO_BINANCE_API_KEY
  ensure_secret_with_optional_version trading-bot-demo-binance-api-secret DEMO_BINANCE_API_SECRET
  ensure_secret_with_optional_version trading-bot-demo-operator-token DEMO_OPERATOR_TOKEN
  ensure_secret_with_optional_version trading-bot-demo-audit-jdbc-url DEMO_AUDIT_JDBC_URL
  ensure_secret_with_optional_version trading-bot-demo-audit-jdbc-username DEMO_AUDIT_JDBC_USERNAME
  ensure_secret_with_optional_version trading-bot-demo-audit-jdbc-password DEMO_AUDIT_JDBC_PASSWORD
  ensure_secret_with_optional_version trading-bot-demo-projection-jdbc-url DEMO_PROJECTION_JDBC_URL
  ensure_secret_with_optional_version trading-bot-demo-projection-jdbc-username DEMO_PROJECTION_JDBC_USERNAME
  ensure_secret_with_optional_version trading-bot-demo-projection-jdbc-password DEMO_PROJECTION_JDBC_PASSWORD
  ensure_secret_with_optional_version trading-bot-real-binance-api-key REAL_BINANCE_API_KEY
  ensure_secret_with_optional_version trading-bot-real-binance-api-secret REAL_BINANCE_API_SECRET
  ensure_secret_with_optional_version trading-bot-real-operator-token REAL_OPERATOR_TOKEN
  ensure_secret_with_optional_version trading-bot-real-audit-jdbc-url REAL_AUDIT_JDBC_URL
  ensure_secret_with_optional_version trading-bot-real-audit-jdbc-username REAL_AUDIT_JDBC_USERNAME
  ensure_secret_with_optional_version trading-bot-real-audit-jdbc-password REAL_AUDIT_JDBC_PASSWORD
  ensure_secret_with_optional_version trading-bot-real-projection-jdbc-url REAL_PROJECTION_JDBC_URL
  ensure_secret_with_optional_version trading-bot-real-projection-jdbc-username REAL_PROJECTION_JDBC_USERNAME
  ensure_secret_with_optional_version trading-bot-real-projection-jdbc-password REAL_PROJECTION_JDBC_PASSWORD
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-pagerduty-routing-key DEMO_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-pagerduty-routing-key DEMO_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-slack-webhook DEMO_ALERT_OPERATOR_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-operator-slack-channel DEMO_ALERT_OPERATOR_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-slack-webhook DEMO_ALERT_PLATFORM_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-platform-slack-channel DEMO_ALERT_PLATFORM_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-demo-alert-fallback-slack-webhook DEMO_ALERT_FALLBACK_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-demo-alert-fallback-slack-channel DEMO_ALERT_FALLBACK_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-operator-pagerduty-routing-key REAL_ALERT_OPERATOR_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-real-alert-platform-pagerduty-routing-key REAL_ALERT_PLATFORM_PAGERDUTY_ROUTING_KEY
  ensure_secret_with_optional_version trading-bot-real-alert-operator-slack-webhook REAL_ALERT_OPERATOR_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-operator-slack-channel REAL_ALERT_OPERATOR_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-platform-slack-webhook REAL_ALERT_PLATFORM_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-platform-slack-channel REAL_ALERT_PLATFORM_SLACK_CHANNEL
  ensure_secret_with_optional_version trading-bot-real-alert-fallback-slack-webhook REAL_ALERT_FALLBACK_SLACK_WEBHOOK
  ensure_secret_with_optional_version trading-bot-real-alert-fallback-slack-channel REAL_ALERT_FALLBACK_SLACK_CHANNEL

  print_github_configuration "$project_number"
}

main "$@"
