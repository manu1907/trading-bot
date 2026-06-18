#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/evidence/collect-live-release-evidence.sh demo|real [options]

Creates a sanitized live-release evidence bundle for the selected runtime
environment. The bundle records committed config hashes, deployment contract
identity, secret binding names, workflow/run identifiers, Cloud Run metadata,
smoke outcomes, and operator observations without reading or printing secret
values.

Options:
  --output-dir PATH                  Directory for the generated evidence bundle.
  --release-id VALUE                 Release or promotion identifier.
  --operator VALUE                   Operator name or automation identity.
  --decision VALUE                   keep_disabled|deploy|promote|rollback.
  --security-workflow-run-id VALUE   GitHub Security workflow run id.
  --security-workflow-url VALUE      GitHub Security workflow URL.
  --publish-workflow-run-id VALUE    Image publish workflow run id.
  --deploy-workflow-run-id VALUE     Cloud Run deploy workflow run id.
  --smoke-workflow-run-id VALUE      Cloud Run smoke workflow run id.
  --artifact-image VALUE             Artifact Registry image reference.
  --image-digest VALUE               Image digest.
  --cloud-run-revision VALUE         Cloud Run revision name.
  --cloud-run-region VALUE           Cloud Run region.
  --revision-commit-label VALUE      Commit SHA label on the deployed revision.
  --alertmanager-file PATH           Rendered Alertmanager config to checksum.
  --smoke-results-file PATH          Live smoke output to copy after secret scan.
  --trading-state-file PATH          Operator/API trading-state snapshot to copy after secret scan.
  --risk-policy-file PATH            Operator/API risk-policy snapshot to copy after secret scan.
  --notes VALUE                      Short operator note.
  -h, --help                         Show this help.

The script is intentionally offline-first. It does not call gcloud, GitHub, or
Binance directly; pass the resulting run ids, revision names, and observation
files from those tools.
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

extract_secret_bindings() {
  local contract=$1
  awk '
    /^secret_env:/ {section="secret_env"; next}
    /^alertmanager_secret_substitutions:/ {section="alertmanager_secret_substitutions"; next}
    /^[^[:space:]]/ {section=""}
    section != "" && /^[[:space:]]+[A-Z0-9_]+:/ {
      name=$1
      sub(":", "", name)
    }
    section != "" && /^[[:space:]]+secret:/ {
      print section "\t" name "\t" $2
    }
  ' "$contract"
}

target_environment=${1:-}
[[ -n "$target_environment" ]] || { usage; exit 2; }
shift || true

case "$target_environment" in
  demo|real) ;;
  -h|--help) usage; exit 0 ;;
  *) die "first argument must be demo or real" ;;
esac

release_id="release-$(date -u +%Y%m%dT%H%M%SZ)"
operator="${USER:-unknown}"
decision="keep_disabled"
security_workflow_run_id=""
security_workflow_url=""
publish_workflow_run_id=""
deploy_workflow_run_id=""
smoke_workflow_run_id=""
artifact_image=""
image_digest=""
cloud_run_revision=""
cloud_run_region=""
revision_commit_label=""
alertmanager_file=""
smoke_results_file=""
trading_state_file=""
risk_policy_file=""
notes=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) output_dir=${2:?}; shift 2 ;;
    --release-id) release_id=${2:?}; shift 2 ;;
    --operator) operator=${2:?}; shift 2 ;;
    --decision) decision=${2:?}; shift 2 ;;
    --security-workflow-run-id) security_workflow_run_id=${2:?}; shift 2 ;;
    --security-workflow-url) security_workflow_url=${2:?}; shift 2 ;;
    --publish-workflow-run-id) publish_workflow_run_id=${2:?}; shift 2 ;;
    --deploy-workflow-run-id) deploy_workflow_run_id=${2:?}; shift 2 ;;
    --smoke-workflow-run-id) smoke_workflow_run_id=${2:?}; shift 2 ;;
    --artifact-image) artifact_image=${2:?}; shift 2 ;;
    --image-digest) image_digest=${2:?}; shift 2 ;;
    --cloud-run-revision) cloud_run_revision=${2:?}; shift 2 ;;
    --cloud-run-region) cloud_run_region=${2:?}; shift 2 ;;
    --revision-commit-label) revision_commit_label=${2:?}; shift 2 ;;
    --alertmanager-file) alertmanager_file=${2:?}; shift 2 ;;
    --smoke-results-file) smoke_results_file=${2:?}; shift 2 ;;
    --trading-state-file) trading_state_file=${2:?}; shift 2 ;;
    --risk-policy-file) risk_policy_file=${2:?}; shift 2 ;;
    --notes) notes=${2:?}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$repo_root"

git_sha=$(git rev-parse HEAD)
git_branch=$(git rev-parse --abbrev-ref HEAD)
started_at=$(iso_utc_now)
completed_at=$started_at
contract_path="ops/google-cloud/${target_environment}-usdm-futures-deployment.yml"
template_path="ops/evidence/live-release-evidence-template.yml"
schema_path="ops/deployment/deployment-contract.yml"
catalog_path="config/catalog.json"
runtime_config_path="config/runtime/live/binance/${target_environment}/main/usdm_futures.json"

[[ -f "$contract_path" ]] || die "missing deployment contract: $contract_path"
[[ -f "$template_path" ]] || die "missing evidence template: $template_path"
[[ -f "$schema_path" ]] || die "missing deployment schema: $schema_path"
[[ -f "$catalog_path" ]] || die "missing catalog: $catalog_path"

if [[ -z "$output_dir" ]]; then
  output_dir="build/evidence/${target_environment}/${release_id}"
fi

bundle_dir=$output_dir
mkdir -p "$bundle_dir"

checksums_file="$bundle_dir/checksums.sha256"
: > "$checksums_file"
write_checksum_line "$catalog_path"
write_checksum_line "$runtime_config_path"
write_checksum_line "$contract_path"
write_checksum_line "$schema_path"
write_checksum_line "$template_path"

if [[ -n "$alertmanager_file" ]]; then
  assert_safe_file "$alertmanager_file"
  write_checksum_line "$alertmanager_file"
fi

smoke_artifact=$(copy_optional_observation "$smoke_results_file" "live-smoke-results.txt")
trading_state_artifact=$(copy_optional_observation "$trading_state_file" "trading-state-snapshot.txt")
risk_policy_artifact=$(copy_optional_observation "$risk_policy_file" "risk-policy-snapshot.txt")

secret_bindings_file="$bundle_dir/secret-bindings.tsv"
{
  printf 'section\tenvironment_variable\tsecret_name\n'
  extract_secret_bindings "$contract_path"
} > "$secret_bindings_file"

service_name=$(awk '/^[[:space:]]+service_name:/ {print $2; exit}' "$contract_path")
provider=$(awk '/^[[:space:]]+provider:/ {print $2; exit}' "$contract_path")
account=$(awk '/^[[:space:]]+account:/ {print $2; exit}' "$contract_path")
market=$(awk '/^[[:space:]]+market:/ {print $2; exit}' "$contract_path")
runtime_target="${provider}/${target_environment}/${account}/${market}"
contract_secret_count=$(($(wc -l < "$secret_bindings_file") - 1))

cat > "$bundle_dir/live-release-evidence.yml" <<EOF
schema_version: 1
evidence_type: live_release_collected

release:
  environment: ${target_environment}
  provider: ${provider}
  account: ${account}
  market: ${market}
  release_id: ${release_id}
  operator: ${operator}
  started_at_utc: ${started_at}
  completed_at_utc: ${completed_at}
  decision: ${decision}
  notes: ${notes:-none}

code_identity:
  git_commit_sha: ${git_sha}
  git_branch: ${git_branch}
  security_workflow_run_id: ${security_workflow_run_id:-TODO}
  security_workflow_url: ${security_workflow_url:-TODO}
  security_workflow_conclusion: success_required
  repository_url: https://github.com/manu1907/trading-bot

image_and_deployment:
  artifact_registry_image: ${artifact_image:-TODO}
  image_digest: ${image_digest:-TODO}
  publish_workflow_run_id: ${publish_workflow_run_id:-TODO}
  deploy_workflow_run_id: ${deploy_workflow_run_id:-TODO}
  cloud_run_service: ${service_name}
  cloud_run_revision: ${cloud_run_revision:-TODO}
  cloud_run_region: ${cloud_run_region:-TODO}
  revision_commit_label: ${revision_commit_label:-TODO_FULL_40_CHAR_SHA}
  traffic_percent: 100

configuration:
  runtime_profile: live
  runtime_target: ${runtime_target}
  deployment_contract_path: ${contract_path}
  deployment_contract_schema_path: ${schema_path}
  catalog_path: ${catalog_path}
  runtime_config_path: ${runtime_config_path}
  checksums_file: checksums.sha256
  same_codebase_as_demo_and_real: true
  intended_real_behavior_pretested_in_demo: true_or_false
  demo_behavior_reduced_vs_real: false_required_for_real_promotion

secret_bindings:
  secret_binding_file: secret-bindings.tsv
  secret_binding_count: ${contract_secret_count}
  secret_values_collected: false
  no_secret_values_in_evidence: true_required

smoke_and_live_validation:
  cloud_run_private_readiness_smoke_run_id: ${smoke_workflow_run_id:-TODO}
  readiness_status: UP_required
  binance_server_time_smoke: pass_required
  binance_order_endpoint_smoke: pass_required
  binance_user_data_stream_smoke: pass_required
  binance_market_data_websocket_smoke: pass_required
  no_unintended_exchange_action_on_startup: true_required
  copied_smoke_results_file: ${smoke_artifact:-none}

trading_state:
  copied_trading_state_file: ${trading_state_artifact:-none}
  reconciliation_confidence: healthy_required_before_execution
  unknown_order_results_count: 0_required_for_promotion
  pending_modify_outcomes_count: 0_required_for_promotion

risk_and_policy:
  copied_risk_policy_file: ${risk_policy_artifact:-none}
  real_environment_allowed: false_required_until_promotion
  account_risk_caps_reviewed: true_or_false
  symbol_risk_caps_reviewed: true_or_false
  loss_drawdown_caps_reviewed: true_or_false

observability:
  alertmanager_rendered_checksum: ${alertmanager_file:+recorded_in_checksums_file}
  google_cloud_monitoring_policies_verified: true_or_false
  budget_alert_verified: true_or_false
  incident_response_runbook_available: true_required

rollback_and_emergency:
  rollback_workflow_run_id_or_drill_id: TODO
  emergency_stop_drill_id: TODO
  controlled_drain_drill_id: TODO
  no_second_bot_instance_verified: true_required

promotion_decision:
  promote_to_next_stage: false_default
  next_stage: none_or_shadow_or_bounded_demo_live_or_extended_demo_burn_in_or_real_disabled_or_real_execution_enabled
  decision_reason: TODO
  real_execution_policy_change_requested: false_default
  evidence_bundle_location: ${bundle_dir}
EOF

cat > "$bundle_dir/README.md" <<EOF
# Live Release Evidence Bundle

- Environment: \`${target_environment}\`
- Runtime target: \`${runtime_target}\`
- Git commit: \`${git_sha}\`
- Deployment contract: \`${contract_path}\`
- Secret values collected: \`false\`

This bundle is generated by \`ops/evidence/collect-live-release-evidence.sh\`.
It is safe to archive as operational evidence only if any optional observation
files supplied to the script were already scrubbed and passed the built-in
secret-pattern scan.
EOF

if grep -R -E -q 'hooks\.slack\.com|xoxb-|ghp_|-----BEGIN|jdbc:postgresql://|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]+' "$bundle_dir"; then
  die "generated bundle contains a forbidden secret-like pattern"
fi

echo "Evidence bundle written to $bundle_dir"
