#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Validate source-controlled readiness for the live-profile Google Cloud deployment path.

This is an offline repository preflight. It does not call Google Cloud, GitHub,
Binance, or Secret Manager, and it does not read secret values.

Options:
  --environment demo|real        Target environment. Default: demo
  --provider binance             Target provider. Default: binance
  --account main                 Target account. Default: main
  --market usdm_futures          Target market. Default: usdm_futures
  --output-file PATH             Markdown report path. Default: build/reports/google-cloud/live-deployment-readiness-<target>.md
  -h, --help                     Show this help.

Example:
  ops/google-cloud/validate-live-deployment-readiness.sh --environment demo
USAGE
}

ENVIRONMENT="demo"
PROVIDER="binance"
ACCOUNT="main"
MARKET="usdm_futures"
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      ENVIRONMENT="${2:-}"
      shift 2
      ;;
    --provider)
      PROVIDER="${2:-}"
      shift 2
      ;;
    --account)
      ACCOUNT="${2:-}"
      shift 2
      ;;
    --market)
      MARKET="${2:-}"
      shift 2
      ;;
    --output-file)
      OUTPUT_FILE="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$ENVIRONMENT" in
  demo|real) ;;
  *)
    echo "--environment must be demo or real" >&2
    exit 2
    ;;
esac

if [[ "$PROVIDER" != "binance" || "$ACCOUNT" != "main" || "$MARKET" != "usdm_futures" ]]; then
  echo "Only binance/main/usdm_futures is currently source-controlled for Google Cloud readiness" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

MARKET_SLUG="${MARKET//_/-}"
TARGET_ID="$PROVIDER/$ENVIRONMENT/$ACCOUNT/$MARKET"
CONTRACT="ops/google-cloud/${ENVIRONMENT}-${MARKET_SLUG}-deployment.yml"
DEMO_CONTRACT="ops/google-cloud/demo-${MARKET_SLUG}-deployment.yml"
REAL_CONTRACT="ops/google-cloud/real-${MARKET_SLUG}-deployment.yml"
DEMO_RUNTIME="config/runtime/live/binance/demo/main/usdm_futures.json"
CATALOG="config/catalog.json"

if [[ -z "$OUTPUT_FILE" ]]; then
  OUTPUT_FILE="build/reports/google-cloud/live-deployment-readiness-${ENVIRONMENT}-${PROVIDER}-${ACCOUNT}-${MARKET}.md"
fi

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0
RESULT_ROWS=()

escape_md() {
  printf '%s' "$1" | sed 's/|/\\|/g'
}

add_result() {
  local status="$1"
  local check="$2"
  local detail="$3"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
  esac
  RESULT_ROWS+=("| $status | $(escape_md "$check") | $(escape_md "$detail") |")
}

require_file() {
  local file="$1"
  local check="$2"
  if [[ -f "$file" ]]; then
    add_result PASS "$check" "$file exists"
  else
    add_result FAIL "$check" "$file is missing"
  fi
}

require_executable() {
  local file="$1"
  local check="$2"
  if [[ -x "$file" ]]; then
    add_result PASS "$check" "$file is executable"
  else
    add_result FAIL "$check" "$file is missing or not executable"
  fi
}

require_contains() {
  local file="$1"
  local needle="$2"
  local check="$3"
  if [[ -f "$file" ]] && grep -Fq "$needle" "$file"; then
    add_result PASS "$check" "$file contains '$needle'"
  else
    add_result FAIL "$check" "$file must contain '$needle'"
  fi
}

require_not_contains_regex() {
  local file="$1"
  local regex="$2"
  local check="$3"
  if [[ ! -f "$file" ]]; then
    add_result FAIL "$check" "$file is missing"
  elif grep -Eq "$regex" "$file"; then
    add_result FAIL "$check" "$file contains forbidden inline secret-like material"
  else
    add_result PASS "$check" "$file contains no forbidden inline secret-like material"
  fi
}

require_secret_binding_prefix() {
  local file="$1"
  local secret_name="$2"
  local expected_prefix="$3"
  local check="$4"
  if [[ -f "$file" ]] && grep -Fq "$secret_name:" "$file" && grep -Fq "secret: $expected_prefix" "$file"; then
    add_result PASS "$check" "$secret_name is bound by Secret Manager name with prefix $expected_prefix"
  else
    add_result FAIL "$check" "$secret_name must be bound by a Secret Manager name with prefix $expected_prefix"
  fi
}

require_workflow() {
  local workflow="$1"
  require_file ".github/workflows/$workflow" "workflow: $workflow"
}

validate_repo_scaffold() {
  require_file Dockerfile "runtime image contract"
  require_file "$CATALOG" "catalog defaults"
  require_file config/application-demo.json "demo endpoint overlay"
  require_file "$DEMO_RUNTIME" "first-start demo runtime override"
  require_file ops/deployment/deployment-contract.yml "neutral deployment contract schema"
  require_file "$DEMO_CONTRACT" "Google Cloud demo deployment contract"
  require_file "$REAL_CONTRACT" "Google Cloud real deployment contract"
  require_file ops/aws/demo-usdm-futures-deployment.yml "AWS-compatible demo deployment contract"
  require_file ops/aws/real-usdm-futures-deployment.yml "AWS-compatible real deployment contract"
  require_executable ops/google-cloud/bootstrap-deployment-prereqs.sh "Google Cloud bootstrap script"
  require_executable ops/google-cloud/provision-monitoring-alert-policies.sh "Google Cloud monitoring provision script"
  require_executable ops/database/migrate-postgresql-state.sh "PostgreSQL state migration script"
  require_executable ops/database/archive-journal-segments.sh "journal archive script"
  require_executable ops/evidence/collect-live-release-evidence.sh "live release evidence collector"
  require_executable ops/evidence/collect-demo-burn-in-evidence.sh "demo burn-in evidence collector"
  require_executable ops/evidence/archive-google-cloud-evidence.sh "evidence archive script"
  require_executable ops/evidence/validate-real-promotion-evidence.sh "real promotion evidence validator"
  require_executable ops/alertmanager/render-google-cloud-alertmanager.sh "Alertmanager renderer"
}

validate_workflows() {
  require_workflow security.yml
  require_workflow publish-google-cloud-image.yml
  require_workflow migrate-google-cloud-postgresql-state.yml
  require_workflow deploy-google-cloud-cloud-run.yml
  require_workflow smoke-google-cloud-cloud-run.yml
  require_workflow smoke-binance-live.yml
  require_workflow rollback-google-cloud-cloud-run.yml
  require_workflow archive-google-cloud-evidence.yml
  require_workflow archive-google-cloud-journal.yml
  require_workflow validate-real-promotion-evidence.yml
}

validate_contract() {
  require_file "$CONTRACT" "selected deployment contract"
  require_contains "$CONTRACT" "environment: $ENVIRONMENT" "selected environment"
  require_contains "$CONTRACT" "provider: $PROVIDER" "selected provider"
  require_contains "$CONTRACT" "account: $ACCOUNT" "selected account"
  require_contains "$CONTRACT" "market: $MARKET" "selected market"
  require_contains "$CONTRACT" "runtime: cloud_run" "Cloud Run runtime"
  require_contains "$CONTRACT" "SPRING_PROFILES_ACTIVE: live" "live Spring profile"
  require_contains "$CONTRACT" "BOT_CONFIG_DIR: /app/config" "external config directory"
  require_contains "$CONTRACT" "BOT_PROVIDER: $PROVIDER" "runtime provider variable"
  require_contains "$CONTRACT" "BOT_ENVIRONMENT: $ENVIRONMENT" "runtime environment variable"
  require_contains "$CONTRACT" "BOT_ACCOUNT: $ACCOUNT" "runtime account variable"
  require_contains "$CONTRACT" "BOT_MARKET: $MARKET" "runtime market variable"
  require_contains "$CONTRACT" "TRADING_INTERVENTION_OPERATOR_API_ENABLED: \"true\"" "operator API enabled by deployment contract"
  require_contains "$CONTRACT" "TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_STORE_ENABLED: \"true\"" "JDBC audit store enabled"
  require_contains "$CONTRACT" "TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_STORE_INITIALIZE_SCHEMA: \"false\"" "audit schema owned by migration"
  require_contains "$CONTRACT" "TRADING_PROJECTION_JDBC_STORE_ENABLED: \"true\"" "JDBC projection store enabled"
  require_contains "$CONTRACT" "TRADING_PROJECTION_JDBC_STORE_INITIALIZE_SCHEMA: \"false\"" "projection schema owned by migration"
  require_contains "$CONTRACT" "selected: jdbc" "JDBC state backend selected"
  require_contains "$CONTRACT" "layout: trading_event_archive_layout_v1" "journal archive layout"
  require_contains "$CONTRACT" "bucket: \${TRADING_BOT_JOURNAL_ARCHIVE_BUCKET}" "journal archive bucket variable"
  require_not_contains_regex "$CONTRACT" '(hooks\.slack\.com|xox[baprs]-|pd_[A-Za-z0-9]|-----BEGIN|jdbc:postgresql://|AKIA[0-9A-Z]{16})' "deployment contract contains no inline secrets"
}

validate_secret_bindings() {
  local prefix="trading-bot-${ENVIRONMENT}-"
  require_secret_binding_prefix "$CONTRACT" TRADING_INTERVENTION_OPERATOR_API_OPERATOR_TOKEN "$prefix" "operator token secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_URL "$prefix" "audit JDBC URL secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_USERNAME "$prefix" "audit JDBC username secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_AUDIT_PAUSE_GOVERNANCE_JDBC_PASSWORD "$prefix" "audit JDBC password secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_PROJECTION_JDBC_URL "$prefix" "projection JDBC URL secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_PROJECTION_JDBC_USERNAME "$prefix" "projection JDBC username secret binding"
  require_secret_binding_prefix "$CONTRACT" TRADING_PROJECTION_JDBC_PASSWORD "$prefix" "projection JDBC password secret binding"

  if [[ "$ENVIRONMENT" == "demo" ]]; then
    require_secret_binding_prefix "$CONTRACT" BINANCE_DEMO_API_KEY "$prefix" "demo Binance API key binding"
    require_secret_binding_prefix "$CONTRACT" BINANCE_DEMO_API_SECRET "$prefix" "demo Binance API secret binding"
    if grep -Fq "BINANCE_REAL_API_" "$CONTRACT"; then
      add_result FAIL "demo contract secret isolation" "demo contract must not bind real Binance credentials"
    else
      add_result PASS "demo contract secret isolation" "demo contract does not bind real Binance credentials"
    fi
  else
    require_secret_binding_prefix "$CONTRACT" BINANCE_REAL_API_KEY "$prefix" "real Binance API key binding"
    require_secret_binding_prefix "$CONTRACT" BINANCE_REAL_API_SECRET "$prefix" "real Binance API secret binding"
    if grep -Fq "BINANCE_DEMO_API_" "$CONTRACT"; then
      add_result FAIL "real contract secret isolation" "real contract must not bind demo Binance credentials"
    else
      add_result PASS "real contract secret isolation" "real contract does not bind demo Binance credentials"
    fi
  fi
}

validate_demo_real_same_codebase_guardrails() {
  require_contains "$DEMO_CONTRACT" "SPRING_PROFILES_ACTIVE: live" "demo uses live profile"
  require_contains "$REAL_CONTRACT" "SPRING_PROFILES_ACTIVE: live" "real uses live profile"
  require_contains "$DEMO_CONTRACT" "BOT_PROVIDER: binance" "demo provider matches real code path"
  require_contains "$REAL_CONTRACT" "BOT_PROVIDER: binance" "real provider matches demo code path"
  require_contains "$DEMO_CONTRACT" "BOT_ACCOUNT: main" "demo account target"
  require_contains "$REAL_CONTRACT" "BOT_ACCOUNT: main" "real account target"
  require_contains "$DEMO_CONTRACT" "BOT_MARKET: usdm_futures" "demo market target"
  require_contains "$REAL_CONTRACT" "BOT_MARKET: usdm_futures" "real market target"
  require_contains "$REAL_CONTRACT" "require_demo_promotion_evidence: true" "real requires demo promotion evidence"
  require_contains "$REAL_CONTRACT" "require_real_secret_isolation: true" "real requires secret isolation"
  require_contains "$REAL_CONTRACT" "real_trading_initial_state: exchange_execution_disabled" "real starts with exchange execution disabled"
  require_contains "$REAL_CONTRACT" "allowed_real_operations: []" "real operation allowlist starts empty"
  require_contains "$REAL_CONTRACT" "TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ENABLED: \"false\"" "real executor disabled at startup"
  require_contains "$REAL_CONTRACT" "TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_EXCHANGE_EXECUTION_ENABLED: \"false\"" "real exchange execution disabled at startup"
  require_contains "$REAL_CONTRACT" "TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_REPORT_ONLY: \"true\"" "real report-only startup guard"
  require_contains "$REAL_CONTRACT" "TRADING_INTERVENTION_REMEDIATION_EXECUTOR_POLICY_ALLOW_REAL_ENVIRONMENT: \"false\"" "real-environment execution blocked at startup"
}

validate_runtime_and_catalog() {
  require_contains "$CATALOG" '"instrument_universe"' "catalog owns instrument universe defaults"
  require_contains "$CATALOG" '"BTCUSDT"' "catalog includes BTCUSDT baseline"
  require_contains "$CATALOG" '"ETHUSDT"' "catalog includes ETHUSDT baseline"
  require_contains "$CATALOG" '"SOLUSDT"' "catalog includes SOLUSDT baseline"
  require_contains "$CATALOG" '"BNBUSDT"' "catalog includes BNBUSDT baseline"
  require_contains "$CATALOG" '"derived_stream_templates"' "catalog defines stream-template capability"
  require_contains "$DEMO_RUNTIME" '"environment": "demo"' "demo runtime selects demo target"
  require_contains "$DEMO_RUNTIME" '"provider": "binance"' "demo runtime selects Binance provider"
  require_contains "$DEMO_RUNTIME" '"market": "usdm_futures"' "demo runtime selects USD-M futures"
  require_contains "$CATALOG" '"required_status": "TRADING"' "catalog requires tradable metadata"
  require_contains "$DEMO_RUNTIME" '"allowed_quote_assets": [' "demo runtime constrains quote assets"
  require_contains "$DEMO_RUNTIME" '"USDT"' "demo runtime admits USDT-quoted instruments"
  require_contains "$DEMO_RUNTIME" '"allowed_contract_types": [' "demo runtime constrains contract types"
  require_contains "$DEMO_RUNTIME" '"PERPETUAL"' "demo runtime admits perpetual contracts"
  require_contains "$DEMO_RUNTIME" '"require_market_data": true' "demo runtime requires projected market data"
  require_contains "$DEMO_RUNTIME" '"require_top_of_book": true' "demo runtime requires top-of-book data"
  require_contains "$DEMO_RUNTIME" '"min_daily_quote_volume": "100000000"' "demo runtime requires high projected quote volume"
  require_contains "$DEMO_RUNTIME" '"derived_required_status": "TRADING"' "demo market-data streams derive from tradable metadata"
  require_contains "$DEMO_RUNTIME" '"derived_max_symbols": 250' "demo market-data stream derivation is capped"
  require_contains "$DEMO_RUNTIME" '"{symbol_lower}@bookTicker"' "demo runtime uses stream templates instead of fixed stream literals"

  if grep -Eq '"[a-z0-9]+@(bookTicker|aggTrade|kline_1d)"' "$DEMO_RUNTIME"; then
    add_result FAIL "runtime stream literals" "demo runtime must use stream templates, not concrete symbol stream literals"
  else
    add_result PASS "runtime stream literals" "demo runtime has no concrete symbol stream literals"
  fi
}

validate_docs_and_runbooks() {
  require_file docs/current-state-and-scenarios.md "current-state documentation"
  require_file docs/demo-usdm-futures-user-manual.md "user manual"
  require_file docs/architecture.md "architecture documentation"
  require_file ops/google-cloud/README.md "Google Cloud operations README"
  require_file ops/runbooks/google-cloud-operations.md "Google Cloud operations runbook"
  require_file ops/runbooks/incident-response.md "incident response runbook"
  require_file ops/runbooks/persistence-recovery.md "persistence recovery runbook"
  require_file ops/runbooks/remediation-executor.md "remediation executor runbook"
  require_file plan.txt "handoff plan"
  require_contains plan.txt "Estimated remaining delivery" "plan has remaining-delivery estimate"
  require_contains plan.txt "Google Cloud" "plan covers Google Cloud deployment"
  require_contains plan.txt "demo and real" "plan preserves demo/real same-codebase rule"
}

validate_repo_scaffold
validate_workflows
validate_contract
validate_secret_bindings
validate_demo_real_same_codebase_guardrails
validate_runtime_and_catalog
validate_docs_and_runbooks

mkdir -p "$(dirname "$OUTPUT_FILE")"
GENERATED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
if [[ "$FAIL_COUNT" -eq 0 ]]; then
  OVERALL="PASS"
else
  OVERALL="FAIL"
fi

{
  echo "# Live Deployment Readiness Report"
  echo
  echo "- Generated at: $GENERATED_AT"
  echo "- Target: $TARGET_ID"
  echo "- Platform: google_cloud/cloud_run"
  echo "- Overall: $OVERALL"
  echo "- Passed checks: $PASS_COUNT"
  echo "- Warnings: $WARN_COUNT"
  echo "- Failed checks: $FAIL_COUNT"
  echo
  echo "This report is an offline repository preflight. It validates committed deployment contracts, workflows, scripts, docs, and runtime/catalog guardrails. It does not prove that Google Cloud resources, Secret Manager versions, Binance connectivity, or GitHub environment values exist."
  echo
  echo "## Checks"
  echo
  echo "| Status | Check | Detail |"
  echo "| --- | --- | --- |"
  printf '%s\n' "${RESULT_ROWS[@]}"
} > "$OUTPUT_FILE"

printf 'Live deployment readiness: %s (%s pass, %s warn, %s fail)\n' "$OVERALL" "$PASS_COUNT" "$WARN_COUNT" "$FAIL_COUNT"
printf 'Report: %s\n' "$OUTPUT_FILE"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
