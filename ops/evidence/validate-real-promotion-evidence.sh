#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/evidence/validate-real-promotion-evidence.sh [options]

Validates that demo burn-in, demo release, and real release evidence are complete
enough to request enabling real exchange execution. The validator is offline and
fail-closed: unresolved placeholders, reduced demo behavior, missing live-smoke
proof, unresolved incidents, or missing drills block promotion.

Options:
  --demo-burn-in-file PATH      Completed demo burn-in evidence YAML.
  --demo-release-file PATH      Completed demo live-release evidence YAML.
  --real-release-file PATH      Completed real live-release evidence YAML.
  --output-file PATH            Markdown validation report to write.
  --real-scope-btc-only         Allow btc_only_run=true only when real is intentionally BTC-only.
  -h, --help                    Show this help.

This script does not call cloud providers, GitHub, Binance, or Secret Manager.
It must receive sanitized evidence files that prove secret bindings by reference,
not by secret value.
USAGE
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

iso_utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

failures=()

add_failure() {
  failures+=("$1")
}

require_file() {
  local file=$1
  local description=$2
  if [[ ! -f "$file" ]]; then
    add_failure "${description} is missing: ${file}"
    return 1
  fi
  return 0
}

safe_secret_scan() {
  local file=$1
  local description=$2
  local slack_webhook_marker="hooks[.]slack[.]com"
  local slack_bot_token_marker="xoxb""-"
  local github_classic_token_marker="ghp""_"
  local github_fine_grained_token_marker="github_pat""_"
  local private_key_marker="-----""BEGIN"
  local jdbc_marker="jdbc:postgresql""://"
  local aws_access_key_marker="AKIA[0-9A-Z]{16}"
  local google_api_key_marker="AI""za[0-9A-Za-z_-]+"
  local pattern="${slack_webhook_marker}|${slack_bot_token_marker}|${github_classic_token_marker}|${github_fine_grained_token_marker}|${private_key_marker}|${jdbc_marker}|${aws_access_key_marker}|${google_api_key_marker}"

  if grep -E -q "$pattern" "$file"; then
    add_failure "${description} contains secret-like material"
  fi
}

require_no_open_blockers() {
  local file=$1
  local description=$2
  local blocker_pattern='TODO|true_or_false|false_default|TODO_FULL_40_CHAR_SHA|demo_or_real|keep_disabled_or_|none_or_|_required|pass_required|UP_required|healthy_required'
  if grep -E -q "$blocker_pattern" "$file"; then
    add_failure "${description} still contains unresolved template or promotion-blocking markers"
  fi
}

require_line() {
  local file=$1
  local pattern=$2
  local description=$3
  if ! grep -E -q "$pattern" "$file"; then
    add_failure "$description"
  fi
}

demo_burn_in_file=""
demo_release_file=""
real_release_file=""
output_file=""
real_scope_btc_only="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --demo-burn-in-file) demo_burn_in_file=${2:?}; shift 2 ;;
    --demo-release-file) demo_release_file=${2:?}; shift 2 ;;
    --real-release-file) real_release_file=${2:?}; shift 2 ;;
    --output-file) output_file=${2:?}; shift 2 ;;
    --real-scope-btc-only) real_scope_btc_only="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$demo_burn_in_file" ]] || die "--demo-burn-in-file is required"
[[ -n "$demo_release_file" ]] || die "--demo-release-file is required"
[[ -n "$real_release_file" ]] || die "--real-release-file is required"
[[ -n "$output_file" ]] || die "--output-file is required"

if require_file "$demo_burn_in_file" "demo burn-in evidence"; then
  safe_secret_scan "$demo_burn_in_file" "demo burn-in evidence"
  require_no_open_blockers "$demo_burn_in_file" "demo burn-in evidence"
  require_line "$demo_burn_in_file" '^[[:space:]]*evidence_type:[[:space:]]*demo_burn_in' "demo burn-in evidence_type must be demo_burn_in"
  require_line "$demo_burn_in_file" '^[[:space:]]*environment:[[:space:]]*demo$' "demo burn-in environment must be demo"
  require_line "$demo_burn_in_file" '^[[:space:]]*runtime_target:[[:space:]]*binance/demo/main/usdm_futures$' "demo burn-in runtime target must be binance/demo/main/usdm_futures"
  require_line "$demo_burn_in_file" '^[[:space:]]*same_codebase_as_real:[[:space:]]*true$' "demo burn-in must prove same codebase as real"
  require_line "$demo_burn_in_file" '^[[:space:]]*same_strategy_behavior_intended_for_real:[[:space:]]*true$' "demo burn-in must prove same strategy behavior intended for real"
  require_line "$demo_burn_in_file" '^[[:space:]]*same_remediation_behavior_intended_for_real:[[:space:]]*true$' "demo burn-in must prove same remediation behavior intended for real"
  require_line "$demo_burn_in_file" '^[[:space:]]*same_symbol_universe_policy_intended_for_real:[[:space:]]*true$' "demo burn-in must prove same symbol-universe policy intended for real"
  require_line "$demo_burn_in_file" '^[[:space:]]*reduced_demo_behavior:[[:space:]]*false$' "demo burn-in cannot be reduced versus intended real behavior"
  if [[ "$real_scope_btc_only" == "true" ]]; then
    require_line "$demo_burn_in_file" '^[[:space:]]*btc_only_run:[[:space:]]*(true|false)$' "demo burn-in btc_only_run must be explicit"
  else
    require_line "$demo_burn_in_file" '^[[:space:]]*btc_only_run:[[:space:]]*false$' "demo burn-in cannot be BTC-only unless real is intentionally BTC-only"
  fi
  require_line "$demo_burn_in_file" '^[[:space:]]*dynamic_exchange_metadata_refresh_verified:[[:space:]]*true$' "demo burn-in must prove dynamic exchange metadata refresh"
  require_line "$demo_burn_in_file" '^[[:space:]]*high_liquidity_candidate_universe_covered:[[:space:]]*true$' "demo burn-in must cover the intended high-liquidity universe"
  require_line "$demo_burn_in_file" '^[[:space:]]*unknown_order_results_count:[[:space:]]*0$' "demo burn-in unknown order results must be zero"
  require_line "$demo_burn_in_file" '^[[:space:]]*unresolved_critical_incidents:[[:space:]]*0$' "demo burn-in unresolved critical incidents must be zero"
  require_line "$demo_burn_in_file" '^[[:space:]]*unresolved_high_risk_exceptions:[[:space:]]*0$' "demo burn-in unresolved high-risk exceptions must be zero"
  require_line "$demo_burn_in_file" '^[[:space:]]*real_config_diff_reviewed:[[:space:]]*true$' "real config diff must be reviewed"
  require_line "$demo_burn_in_file" '^[[:space:]]*real_secret_isolation_verified:[[:space:]]*true$' "real secret isolation must be verified"
  require_line "$demo_burn_in_file" '^[[:space:]]*real_exchange_execution_remains_disabled_until_approval:[[:space:]]*true$' "real exchange execution must remain disabled until approval"
  require_line "$demo_burn_in_file" '^[[:space:]]*promote_to_real_execution:[[:space:]]*true$' "demo burn-in must explicitly approve promotion to real execution"
  for drill in \
    restart_during_open_order \
    network_outage_or_exchange_timeout \
    stale_user_data_stream \
    stale_market_data_stream \
    external_manual_order_or_position \
    bad_config_or_failed_deployment \
    emergency_stop \
    rollback; do
    require_line "$demo_burn_in_file" "^[[:space:]]*${drill}(_completed)?[[:space:]]*:[[:space:]]*true$" "demo burn-in drill must be completed: ${drill}"
  done
fi

for release_pair in "demo:$demo_release_file" "real:$real_release_file"; do
  environment=${release_pair%%:*}
  release_file=${release_pair#*:}
  if require_file "$release_file" "${environment} live-release evidence"; then
    safe_secret_scan "$release_file" "${environment} live-release evidence"
    require_no_open_blockers "$release_file" "${environment} live-release evidence"
    require_line "$release_file" '^[[:space:]]*evidence_type:[[:space:]]*live_release' "${environment} release evidence_type must be live_release"
    require_line "$release_file" "^[[:space:]]*environment:[[:space:]]*${environment}$" "${environment} release environment must be ${environment}"
    require_line "$release_file" "^[[:space:]]*runtime_target:[[:space:]]*binance/${environment}/main/usdm_futures$" "${environment} release runtime target must match ${environment}"
    require_line "$release_file" '^[[:space:]]*security_workflow_conclusion:[[:space:]]*success$' "${environment} release must prove Security workflow success"
    require_line "$release_file" '^[[:space:]]*same_codebase_as_demo_and_real:[[:space:]]*true$' "${environment} release must prove shared demo/real codebase"
    require_line "$release_file" '^[[:space:]]*intended_real_behavior_pretested_in_demo:[[:space:]]*true$' "${environment} release must prove intended real behavior was pretested in demo"
    require_line "$release_file" '^[[:space:]]*demo_behavior_reduced_vs_real:[[:space:]]*false$' "${environment} release cannot be based on reduced demo behavior"
    require_line "$release_file" '^[[:space:]]*secret_values_collected:[[:space:]]*false$' "${environment} release evidence must not collect secret values"
    require_line "$release_file" '^[[:space:]]*no_secret_values_in_evidence:[[:space:]]*true$' "${environment} release evidence must assert no secret values"
    require_line "$release_file" '^[[:space:]]*readiness_status:[[:space:]]*UP$' "${environment} release readiness smoke must be UP"
    require_line "$release_file" '^[[:space:]]*binance_server_time_smoke:[[:space:]]*pass$' "${environment} release Binance server-time smoke must pass"
    require_line "$release_file" '^[[:space:]]*binance_order_endpoint_smoke:[[:space:]]*pass$' "${environment} release Binance order smoke must pass"
    require_line "$release_file" '^[[:space:]]*binance_user_data_stream_smoke:[[:space:]]*pass$' "${environment} release Binance user-data stream smoke must pass"
    require_line "$release_file" '^[[:space:]]*binance_market_data_websocket_smoke:[[:space:]]*pass$' "${environment} release Binance websocket smoke must pass"
    require_line "$release_file" '^[[:space:]]*no_unintended_exchange_action_on_startup:[[:space:]]*true$' "${environment} release must prove no unintended exchange action on startup"
    require_line "$release_file" '^[[:space:]]*reconciliation_confidence:[[:space:]]*healthy$' "${environment} release reconciliation confidence must be healthy"
    require_line "$release_file" '^[[:space:]]*unknown_order_results_count:[[:space:]]*0$' "${environment} release unknown order results must be zero"
    require_line "$release_file" '^[[:space:]]*pending_modify_outcomes_count:[[:space:]]*0$' "${environment} release pending modify outcomes must be zero"
    require_line "$release_file" '^[[:space:]]*account_risk_caps_reviewed:[[:space:]]*true$' "${environment} release account risk caps must be reviewed"
    require_line "$release_file" '^[[:space:]]*symbol_risk_caps_reviewed:[[:space:]]*true$' "${environment} release symbol risk caps must be reviewed"
    require_line "$release_file" '^[[:space:]]*loss_drawdown_caps_reviewed:[[:space:]]*true$' "${environment} release loss/drawdown caps must be reviewed"
    require_line "$release_file" '^[[:space:]]*google_cloud_monitoring_policies_verified:[[:space:]]*true$' "${environment} release monitoring policies must be verified"
    require_line "$release_file" '^[[:space:]]*budget_alert_verified:[[:space:]]*true$' "${environment} release budget alert must be verified"
    require_line "$release_file" '^[[:space:]]*incident_response_runbook_available:[[:space:]]*true$' "${environment} release incident runbook must be available"
    require_line "$release_file" '^[[:space:]]*no_second_bot_instance_verified:[[:space:]]*true$' "${environment} release must prove no second bot instance"
  fi
done

if [[ -f "$real_release_file" ]]; then
  require_line "$real_release_file" '^[[:space:]]*real_environment_allowed:[[:space:]]*false$' "real release must prove real exchange execution is still disabled before approval"
  require_line "$real_release_file" '^[[:space:]]*promote_to_next_stage:[[:space:]]*true$' "real release must explicitly approve next-stage promotion"
  require_line "$real_release_file" '^[[:space:]]*next_stage:[[:space:]]*real_execution_enabled$' "real release next stage must be real_execution_enabled"
  require_line "$real_release_file" '^[[:space:]]*real_execution_policy_change_requested:[[:space:]]*true$' "real release must explicitly request real execution policy change"
fi

mkdir -p "$(dirname "$output_file")"
{
  echo "# Real Promotion Evidence Validation"
  echo
  echo "- Generated at UTC: $(iso_utc_now)"
  echo "- Demo burn-in evidence: ${demo_burn_in_file}"
  echo "- Demo live-release evidence: ${demo_release_file}"
  echo "- Real live-release evidence: ${real_release_file}"
  echo "- Real scope BTC-only override: ${real_scope_btc_only}"
  echo
  if [[ ${#failures[@]} -eq 0 ]]; then
    echo "Status: PASS"
    echo
    echo "All configured promotion gates passed. This validates the evidence package only; the real execution config change must still be made through the guarded deployment path."
  else
    echo "Status: FAIL"
    echo
    echo "Promotion is blocked for the following reasons:"
    for failure in "${failures[@]}"; do
      echo "- ${failure}"
    done
  fi
} > "$output_file"

if [[ ${#failures[@]} -ne 0 ]]; then
  cat "$output_file" >&2
  exit 1
fi

cat "$output_file"
