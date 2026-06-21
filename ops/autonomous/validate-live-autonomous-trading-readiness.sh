#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Validate whether the live bot is ready for autonomous trading on the selected target.

This validator is intentionally stricter than deployment readiness. It does not call Google Cloud, GitHub, Binance, or Secret Manager. It validates that the
committed runtime configuration and implementation evidence are sufficient to
claim full autonomous live trading readiness.

Options:
  --environment demo|real        Target environment. Default: demo
  --output-file PATH             Markdown report path. Default: build/reports/autonomous/live-autonomous-trading-readiness-<environment>.md
  -h, --help                     Show this help.
USAGE
}

ENVIRONMENT="demo"
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      ENVIRONMENT="${2:-}"
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

if [[ -z "$OUTPUT_FILE" ]]; then
  OUTPUT_FILE="build/reports/autonomous/live-autonomous-trading-readiness-${ENVIRONMENT}.md"
fi

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0
ROWS=()

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
  ROWS+=("| $status | $(escape_md "$check") | $(escape_md "$detail") |")
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

require_contains() {
  local file="$1"
  local needle="$2"
  local check="$3"
  local pass_detail="$4"
  local fail_detail="$5"
  if [[ -f "$file" ]] && grep -Fq "$needle" "$file"; then
    add_result PASS "$check" "$pass_detail"
  else
    add_result FAIL "$check" "$fail_detail"
  fi
}

require_regex() {
  local file="$1"
  local regex="$2"
  local check="$3"
  local pass_detail="$4"
  local fail_detail="$5"
  if [[ -f "$file" ]] && perl -0ne "exit($regex ? 0 : 1)" "$file"; then
    add_result PASS "$check" "$pass_detail"
  else
    add_result FAIL "$check" "$fail_detail"
  fi
}

require_not_contains() {
  local file="$1"
  local needle="$2"
  local check="$3"
  local pass_detail="$4"
  local fail_detail="$5"
  if [[ -f "$file" ]] && grep -Fq "$needle" "$file"; then
    add_result FAIL "$check" "$fail_detail"
  else
    add_result PASS "$check" "$pass_detail"
  fi
}

DEMO_RUNTIME="config/runtime/live/binance/demo/main/usdm_futures.json"
REAL_CONTRACT="ops/google-cloud/real-usdm-futures-deployment.yml"
PLAN="plan.txt"
POSITION_MANAGER="bot-core/src/main/java/io/github/manu/position/PositionManager.java"
PROJECTION_CONFIGURATION="bot-core/src/main/java/io/github/manu/projection/ProjectionConfiguration.java"
TRADING_PROJECTION="bot-core/src/main/java/io/github/manu/projection/TradingStateProjection.java"
LFA_RUNNER="bot-strategy-lfa/src/main/java/io/github/manu/strategy/lfa/LfaSignalRunner.java"
STRATEGY_PLANNER="bot-core/src/main/java/io/github/manu/execution/StrategySignalPlanner.java"

require_file "$DEMO_RUNTIME" "demo runtime config"
require_file "$REAL_CONTRACT" "real deployment contract"
require_file "$POSITION_MANAGER" "position lifecycle implementation surface"
require_file "$PROJECTION_CONFIGURATION" "projection handler configuration"
require_file "$TRADING_PROJECTION" "trading state projection implementation"
require_file "$LFA_RUNNER" "LFA signal runner implementation"
require_file "$STRATEGY_PLANNER" "strategy-to-order planner implementation"

require_contains "$DEMO_RUNTIME" '"signal_runner"' \
  "demo LFA signal runner is configured" \
  "demo runtime contains LFA signal runner settings" \
  "demo runtime must configure the LFA signal runner before demo or real autonomous trading"
require_regex "$DEMO_RUNTIME" '/"signal_runner"\s*:\s*\{\s*"enabled"\s*:\s*true/s' \
  "demo LFA signal runner explicitly enabled" \
  "demo runtime explicitly enables the LFA signal runner" \
  "demo runtime must explicitly set trading.strategy.lfa.signal_runner.enabled=true before demo or real autonomous trading"
require_contains "$DEMO_RUNTIME" '"lifecycle_state": "ACTIVE"' \
  "demo LFA lifecycle active for autonomous trading" \
  "demo runtime starts autonomous strategy lifecycle as ACTIVE" \
  "demo runtime currently does not start LFA lifecycle as ACTIVE"
require_not_contains "$DEMO_RUNTIME" '"lifecycle_state": "PAUSED"' \
  "demo LFA lifecycle is not paused" \
  "demo runtime does not start LFA lifecycle as PAUSED" \
  "demo runtime currently starts LFA lifecycle as PAUSED"
require_contains "$DEMO_RUNTIME" '"max_account_open_order_notional"' \
  "demo account open-order notional cap configured" \
  "demo runtime configures account open-order notional cap" \
  "demo runtime must configure account open-order notional cap before demo or real autonomous trading"
require_contains "$DEMO_RUNTIME" '"max_account_position_notional"' \
  "demo account position notional cap configured" \
  "demo runtime configures account position notional cap" \
  "demo runtime must configure account position notional cap before demo or real autonomous trading"
require_contains "$DEMO_RUNTIME" '"max_account_unrealized_loss"' \
  "demo account unrealized-loss cap configured" \
  "demo runtime configures account unrealized-loss cap" \
  "demo runtime must configure account unrealized-loss cap before demo or real autonomous trading"
require_contains "$DEMO_RUNTIME" '"max_account_daily_realized_loss"' \
  "demo account daily realized-loss cap configured" \
  "demo runtime configures account daily realized-loss cap" \
  "demo runtime must configure account daily realized-loss cap before demo or real autonomous trading"
require_contains "$DEMO_RUNTIME" '"governed_strategy_id": "lfa"' \
  "demo position lifecycle is governed by LFA lifecycle" \
  "demo runtime binds position lifecycle actions to projected LFA lifecycle state" \
  "demo runtime must bind position lifecycle actions to projected LFA lifecycle state before autonomous trading"

require_contains "$STRATEGY_PLANNER" "EXIT_LONG" \
  "strategy exit signal planning exists" \
  "strategy planner recognizes exit/reduction signals" \
  "strategy planner must support exit/reduction signals"
require_contains "$POSITION_MANAGER" "runOnce" \
  "autonomous position lifecycle runner exists" \
  "position manager has a runnable autonomous lifecycle loop" \
  "position manager is not yet an autonomous lifecycle runner"
require_contains "$POSITION_MANAGER" "StrategySignalEvent" \
  "position lifecycle emits strategy exit/reduce signals" \
  "position manager can emit strategy lifecycle signals" \
  "position manager must emit exit/reduce/close signals through the strategy pipeline"
require_contains "$POSITION_MANAGER" "strategyLifecycle" \
  "position lifecycle reads projected strategy lifecycle" \
  "position manager gates actions on projected strategy lifecycle state" \
  "position manager must gate actions on projected strategy lifecycle state"
require_contains "$PROJECTION_CONFIGURATION" "TradingEventType.STRATEGY_SIGNAL" \
  "strategy signal projection handler is registered" \
  "projection registers strategy-signal events for restart evidence" \
  "projection must register strategy-signal events before autonomous trading"
require_contains "$TRADING_PROJECTION" "applyEventIdOnly" \
  "strategy signal event ids are projected" \
  "projection records strategy-signal event ids for duplicate suppression" \
  "projection must record strategy-signal event ids before autonomous trading"
require_contains "$POSITION_MANAGER" "projected_duplicate_signal" \
  "position lifecycle blocks projected duplicate signals" \
  "position manager blocks lifecycle signals already present in projected applied-event ids" \
  "position manager must block already projected lifecycle signals after restart"
require_contains "$PLAN" "full autonomous demo trading" \
  "plan tracks full autonomous demo trading" \
  "plan explicitly tracks full autonomous demo trading" \
  "plan must explicitly track full autonomous demo trading as the delivery target"

if [[ "$ENVIRONMENT" == "real" ]]; then
  require_contains "$REAL_CONTRACT" "require_demo_promotion_evidence: true" \
    "real requires demo promotion evidence" \
    "real contract requires demo promotion evidence" \
    "real must require demo promotion evidence"
  require_contains "$REAL_CONTRACT" "real_trading_initial_state: exchange_execution_disabled" \
    "real starts execution-disabled" \
    "real contract starts with exchange execution disabled" \
    "real must start with exchange execution disabled until promotion succeeds"
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"
if [[ "$FAIL_COUNT" -eq 0 ]]; then
  OVERALL="PASS"
else
  OVERALL="FAIL"
fi
GENERATED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

{
  echo "# Live Autonomous Trading Readiness Report"
  echo
  echo "- Generated at: $GENERATED_AT"
  echo "- Environment: $ENVIRONMENT"
  echo "- Target: binance/$ENVIRONMENT/main/usdm_futures"
  echo "- Overall: $OVERALL"
  echo "- Passed checks: $PASS_COUNT"
  echo "- Warnings: $WARN_COUNT"
  echo "- Failed checks: $FAIL_COUNT"
  echo
  echo "This is not a profitability certificate. It only checks whether the repository is configured and implemented enough to claim autonomous live trading readiness for the selected target."
  echo
  echo "## Checks"
  echo
  echo "| Status | Check | Detail |"
  echo "| --- | --- | --- |"
  printf '%s\n' "${ROWS[@]}"
} > "$OUTPUT_FILE"

printf 'Live autonomous trading readiness: %s (%s pass, %s warn, %s fail)\n' "$OVERALL" "$PASS_COUNT" "$WARN_COUNT" "$FAIL_COUNT"
printf 'Report: %s\n' "$OUTPUT_FILE"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
