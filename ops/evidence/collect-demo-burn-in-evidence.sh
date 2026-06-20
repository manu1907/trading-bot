#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/evidence/collect-demo-burn-in-evidence.sh [options]

Creates a sanitized demo burn-in evidence bundle for binance/demo/main/usdm_futures.
The bundle records committed config hashes, demo deployment contract identity,
optional live observation files, optional release-evidence manifests, and
promotion-blocking defaults without reading or printing secret values.

Options:
  --output-dir PATH                  Directory for the generated evidence bundle.
  --burn-in-id VALUE                 Burn-in identifier.
  --operator VALUE                   Operator name or automation identity.
  --started-at VALUE                 Burn-in start timestamp, ISO-8601 UTC preferred.
  --ended-at VALUE                   Burn-in end timestamp, ISO-8601 UTC preferred.
  --duration-hours VALUE             Burn-in duration in hours.
  --release-evidence-dir PATH        Existing release-evidence directory to checksum.
  --stage-evidence-file PATH         Runtime-stage evidence summary to copy after secret scan.
  --market-universe-file PATH        Market-universe coverage evidence to copy after secret scan.
  --continuous-metrics-file PATH     Continuous-operation metrics to copy after secret scan.
  --trading-metrics-file PATH        Trading metrics to copy after secret scan.
  --drills-file PATH                 Required-drill evidence to copy after secret scan.
  --observability-file PATH          Alert/dashboard/monitoring evidence to copy after secret scan.
  --incidents-file PATH              Incident and exception evidence to copy after secret scan.
  --notes VALUE                      Short operator note.
  -h, --help                         Show this help.

The script is intentionally offline-first. It does not call gcloud, GitHub,
Binance, Cloud Run, monitoring systems, or Secret Manager. Pass sanitized live
observation files produced by those systems.
USAGE
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

iso_utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

assert_safe_file() {
  local path=$1
  [[ -f "$path" ]] || die "file does not exist: $path"
  if grep -E -q 'hooks\.slack\.com|xoxb-|ghp_|-----BEGIN|jdbc:postgresql://|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]+' "$path"; then
    die "refusing to copy possible secret-bearing file: $path"
  fi
}

relative_path() {
  local path=$1
  case "$path" in
    "$repo_root"/*) printf '%s\n' "${path#"$repo_root"/}" ;;
    *) printf '%s\n' "$path" ;;
  esac
}

write_checksum_line() {
  local path=$1
  if [[ -f "$path" ]]; then
    printf '%s  %s\n' "$(sha256_file "$path")" "$(relative_path "$path")" >> "$checksums_file"
  else
    printf 'missing  %s\n' "$(relative_path "$path")" >> "$checksums_file"
  fi
}

copy_optional_observation() {
  local source=$1
  local target_name=$2
  [[ -n "$source" ]] || return 0
  assert_safe_file "$source"
  cp "$source" "$bundle_dir/$target_name"
  printf '%s\n' "$target_name"
}

manifest_release_evidence_dir() {
  local source_dir=$1
  [[ -n "$source_dir" ]] || return 0
  [[ -d "$source_dir" ]] || die "release evidence directory does not exist: $source_dir"

  local manifest=$bundle_dir/release-evidence-manifest.tsv
  printf 'sha256\trelative_path\n' > "$manifest"
  while IFS= read -r file; do
    assert_safe_file "$file"
    printf '%s\t%s\n' "$(sha256_file "$file")" "$(relative_path "$file")" >> "$manifest"
  done < <(find "$source_dir" -maxdepth 2 -type f | sort)
  printf '%s\n' "release-evidence-manifest.tsv"
}

extract_contract_value() {
  local field=$1
  local file=$2
  awk -v target="$field" '$1 == target ":" {print $2; exit}' "$file"
}

burn_in_id="burn-in-$(date -u +%Y%m%dT%H%M%SZ)"
operator="${USER:-unknown}"
started_at="TODO"
ended_at="$(iso_utc_now)"
duration_hours="TODO"
release_evidence_dir=""
stage_evidence_file=""
market_universe_file=""
continuous_metrics_file=""
trading_metrics_file=""
drills_file=""
observability_file=""
incidents_file=""
notes=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) output_dir=${2:?}; shift 2 ;;
    --burn-in-id) burn_in_id=${2:?}; shift 2 ;;
    --operator) operator=${2:?}; shift 2 ;;
    --started-at) started_at=${2:?}; shift 2 ;;
    --ended-at) ended_at=${2:?}; shift 2 ;;
    --duration-hours) duration_hours=${2:?}; shift 2 ;;
    --release-evidence-dir) release_evidence_dir=${2:?}; shift 2 ;;
    --stage-evidence-file) stage_evidence_file=${2:?}; shift 2 ;;
    --market-universe-file) market_universe_file=${2:?}; shift 2 ;;
    --continuous-metrics-file) continuous_metrics_file=${2:?}; shift 2 ;;
    --trading-metrics-file) trading_metrics_file=${2:?}; shift 2 ;;
    --drills-file) drills_file=${2:?}; shift 2 ;;
    --observability-file) observability_file=${2:?}; shift 2 ;;
    --incidents-file) incidents_file=${2:?}; shift 2 ;;
    --notes) notes=${2:?}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$repo_root"

git_sha=$(git rev-parse HEAD)
git_branch=$(git rev-parse --abbrev-ref HEAD)
collected_at=$(iso_utc_now)
contract_path="ops/google-cloud/demo-usdm-futures-deployment.yml"
template_path="ops/evidence/demo-burn-in-evidence-template.yml"
schema_path="ops/deployment/deployment-contract.yml"
catalog_path="config/catalog.json"
runtime_config_path="config/runtime/live/binance/demo/main/usdm_futures.json"
real_contract_path="ops/google-cloud/real-usdm-futures-deployment.yml"

[[ -f "$contract_path" ]] || die "missing demo deployment contract: $contract_path"
[[ -f "$real_contract_path" ]] || die "missing real deployment contract: $real_contract_path"
[[ -f "$template_path" ]] || die "missing burn-in template: $template_path"
[[ -f "$schema_path" ]] || die "missing deployment schema: $schema_path"
[[ -f "$catalog_path" ]] || die "missing catalog: $catalog_path"
[[ -f "$runtime_config_path" ]] || die "missing demo runtime config: $runtime_config_path"

if [[ -z "$output_dir" ]]; then
  output_dir="build/evidence/demo-burn-in/${burn_in_id}"
fi

bundle_dir=$output_dir
mkdir -p "$bundle_dir"

checksums_file="$bundle_dir/checksums.sha256"
: > "$checksums_file"
write_checksum_line "$catalog_path"
write_checksum_line "$runtime_config_path"
write_checksum_line "$contract_path"
write_checksum_line "$real_contract_path"
write_checksum_line "$schema_path"
write_checksum_line "$template_path"

stage_artifact=$(copy_optional_observation "$stage_evidence_file" "runtime-stage-evidence.txt")
market_artifact=$(copy_optional_observation "$market_universe_file" "market-universe-evidence.txt")
continuous_artifact=$(copy_optional_observation "$continuous_metrics_file" "continuous-operation-metrics.txt")
trading_artifact=$(copy_optional_observation "$trading_metrics_file" "trading-metrics.txt")
drills_artifact=$(copy_optional_observation "$drills_file" "required-drills-evidence.txt")
observability_artifact=$(copy_optional_observation "$observability_file" "observability-evidence.txt")
incidents_artifact=$(copy_optional_observation "$incidents_file" "incidents-and-exceptions.txt")
release_manifest_artifact=$(manifest_release_evidence_dir "$release_evidence_dir")

provider=$(extract_contract_value "provider" "$contract_path")
environment=$(extract_contract_value "environment" "$contract_path")
account=$(extract_contract_value "account" "$contract_path")
market=$(extract_contract_value "market" "$contract_path")
service_name=$(extract_contract_value "service_name" "$contract_path")
runtime_target="${provider}/${environment}/${account}/${market}"

cat > "$bundle_dir/demo-burn-in-evidence.yml" <<EOF_BUNDLE
schema_version: 1
evidence_type: demo_burn_in_collected

burn_in:
  provider: ${provider}
  environment: ${environment}
  account: ${account}
  market: ${market}
  runtime_target: ${runtime_target}
  cloud_run_service: ${service_name}
  burn_in_id: ${burn_in_id}
  operator: ${operator}
  collected_at_utc: ${collected_at}
  started_at_utc: ${started_at}
  ended_at_utc: ${ended_at}
  duration_hours: ${duration_hours}
  notes: ${notes:-none}

code_identity:
  git_commit_sha: ${git_sha}
  git_branch: ${git_branch}
  same_codebase_as_real: true
  repository_url: https://github.com/manu1907/trading-bot

configuration:
  runtime_profile: live
  catalog_path: ${catalog_path}
  demo_runtime_config_path: ${runtime_config_path}
  demo_deployment_contract_path: ${contract_path}
  real_deployment_contract_path: ${real_contract_path}
  deployment_contract_schema_path: ${schema_path}
  checksums_file: checksums.sha256
  release_evidence_manifest: ${release_manifest_artifact:-none}

behavior_equivalence:
  same_codebase_as_real: true_required
  same_strategy_behavior_intended_for_real: true_required
  same_remediation_behavior_intended_for_real: true_required
  same_symbol_universe_policy_intended_for_real: true_required
  only_allowed_differences: credentials_endpoints_provider_availability_approvals_and_calibrated_risk_values
  reduced_demo_behavior: false_required_for_real_promotion

runtime_stages:
  copied_stage_evidence_file: ${stage_artifact:-none}
  strategy_disabled_observation_completed: true_or_false
  strategy_shadow_mode_completed: true_or_false
  bounded_demo_live_trading_completed: true_or_false
  extended_demo_burn_in_completed: true_or_false

market_universe:
  copied_market_universe_file: ${market_artifact:-none}
  dynamic_exchange_metadata_refresh_verified: true_or_false
  high_liquidity_candidate_universe_covered: true_or_false
  btc_only_run: false_required_for_real_promotion_unless_real_is_btc_only
  same_universe_policy_as_intended_real: true_required

continuous_operation_metrics:
  copied_continuous_metrics_file: ${continuous_artifact:-none}
  uptime_percent: TODO
  restart_count: TODO
  readiness_failures_count: TODO
  market_data_staleness_incidents: TODO
  user_data_staleness_incidents: TODO
  reconciliation_degraded_incidents: TODO
  unknown_order_results_count: 0_required_for_promotion
  duplicate_command_prevention_events: TODO

trading_metrics:
  copied_trading_metrics_file: ${trading_artifact:-none}
  orders_submitted_total: TODO
  orders_rejected_by_risk_total: TODO
  fills_total: TODO
  realized_pnl: TODO
  unrealized_pnl: TODO
  max_drawdown: TODO
  max_margin_utilization: TODO

required_drills:
  copied_drills_file: ${drills_artifact:-none}
  restart_during_open_order_completed: true_or_false
  network_outage_or_exchange_timeout_completed: true_or_false
  stale_user_data_stream_completed: true_or_false
  stale_market_data_stream_completed: true_or_false
  external_manual_order_or_position_completed: true_or_false
  bad_config_or_failed_deployment_completed: true_or_false
  emergency_stop_completed: true_or_false
  rollback_completed: true_or_false

observability_and_alerting:
  copied_observability_file: ${observability_artifact:-none}
  alertmanager_routing_verified: true_or_false
  email_slack_pagerduty_receivers_verified: true_or_false
  google_cloud_monitoring_policies_verified: true_or_false
  budget_alert_verified: true_or_false
  dashboards_available: true_or_false

incidents_and_exceptions:
  copied_incidents_file: ${incidents_artifact:-none}
  unresolved_critical_incidents: 0_required
  unresolved_high_risk_exceptions: 0_required

promotion_readiness:
  real_config_diff_reviewed: true_or_false
  real_secret_isolation_verified: true_or_false
  real_exchange_execution_remains_disabled_until_approval: true_required
  promote_to_real_execution: false_default
  decision_reason: TODO
  evidence_bundle_location: ${bundle_dir}
  secret_values_collected: false
  no_secret_values_in_evidence: true_required
EOF_BUNDLE

cat > "$bundle_dir/README.md" <<EOF_README
# Demo Burn-In Evidence Bundle

- Runtime target: \`${runtime_target}\`
- Git commit: \`${git_sha}\`
- Demo deployment contract: \`${contract_path}\`
- Real deployment contract checked for parity context: \`${real_contract_path}\`
- Secret values collected: \`false\`

This bundle is generated by \`ops/evidence/collect-demo-burn-in-evidence.sh\`.
It is promotion-blocking by default: fields that remain \`TODO\`,
\`true_or_false\`, or \`false_default\` must be completed by live operation or
review automation before real exchange execution can be enabled.
EOF_README

if grep -R -E -q 'hooks\.slack\.com|xoxb-|ghp_|-----BEGIN|jdbc:postgresql://|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]+' "$bundle_dir"; then
  die "generated bundle contains a forbidden secret-like pattern"
fi

echo "Demo burn-in evidence bundle written to $bundle_dir"
