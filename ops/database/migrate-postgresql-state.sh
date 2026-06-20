#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/database/migrate-postgresql-state.sh --environment demo|real [options]

Applies deployment-owned PostgreSQL schema for durable bot state. The hot bot
runtime must keep schema initialization disabled; this script is for guarded
ops migration before deployment or promotion.

Options:
  --environment VALUE        demo or real.
  --schema projection|audit|all
                            Schema set to apply. Default: all.
  --jdbc-url VALUE           JDBC URL from deployment secret.
  --username VALUE           Database username from deployment secret.
  --password-env NAME        Environment variable containing the database password.
  --psql-url VALUE           Explicit psql connection URL. Overrides JDBC parsing.
  --cloud-sql-proxy PATH     Optional Cloud SQL Auth Proxy binary for Cloud SQL JDBC URLs.
  --local-host VALUE         Local host used with Cloud SQL Auth Proxy. Default: 127.0.0.1.
  --local-port VALUE         Local port used with Cloud SQL Auth Proxy. Default: 5432.
  --psql-bin PATH            psql binary. Default: psql.
  --output-dir PATH          Directory for migration evidence. Default: build/database-migration.
  --plan-only                Validate inputs and write evidence without connecting to PostgreSQL.
  -h, --help                 Show this help.
USAGE
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    die "missing required command: $1"
  fi
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

safe_scan_sql() {
  local file=$1
  if grep -E -qi '\b(drop|truncate)\b|delete[[:space:]]+from|alter[[:space:]]+table[[:space:]]+[^;]+[[:space:]]+drop[[:space:]]+' "$file"; then
    die "refusing potentially destructive migration SQL: $file"
  fi
  grep -E -q 'create table if not exists|create index if not exists|alter table .+ add column if not exists' "$file" \
    || die "migration SQL does not contain expected idempotent DDL: $file"
}

jdbc_database() {
  local jdbc_url=$1
  local without_prefix=${jdbc_url#jdbc:postgresql:}
  without_prefix=${without_prefix%%\?*}
  without_prefix=${without_prefix%/}
  printf '%s\n' "${without_prefix##*/}"
}

jdbc_cloud_sql_instance() {
  local jdbc_url=$1
  case "$jdbc_url" in
    *cloudSqlInstance=*) ;;
    *) return 1 ;;
  esac
  local value=${jdbc_url#*cloudSqlInstance=}
  value=${value%%&*}
  printf '%s\n' "$value"
}

start_cloud_sql_proxy() {
  local proxy=$1
  local instance=$2
  local host=$3
  local port=$4
  [[ -x "$proxy" ]] || die "Cloud SQL Auth Proxy is not executable: $proxy"
  "$proxy" "$instance" --address "$host" --port "$port" >/tmp/trading-bot-cloud-sql-proxy.log 2>&1 &
  proxy_pid=$!
  trap 'kill "$proxy_pid" >/dev/null 2>&1 || true' EXIT
  sleep 2
}

schema="all"
environment=""
jdbc_url=""
username=""
password_env=""
psql_url=""
cloud_sql_proxy=""
local_host="127.0.0.1"
local_port="5432"
psql_bin="psql"
output_dir="build/database-migration"
plan_only="false"
proxy_pid=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment=${2:?}; shift 2 ;;
    --schema) schema=${2:?}; shift 2 ;;
    --jdbc-url) jdbc_url=${2:?}; shift 2 ;;
    --username) username=${2:?}; shift 2 ;;
    --password-env) password_env=${2:?}; shift 2 ;;
    --psql-url) psql_url=${2:?}; shift 2 ;;
    --cloud-sql-proxy) cloud_sql_proxy=${2:?}; shift 2 ;;
    --local-host) local_host=${2:?}; shift 2 ;;
    --local-port) local_port=${2:?}; shift 2 ;;
    --psql-bin) psql_bin=${2:?}; shift 2 ;;
    --output-dir) output_dir=${2:?}; shift 2 ;;
    --plan-only) plan_only="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$environment" in
  demo|real) ;;
  *) die "--environment must be demo or real" ;;
esac
case "$schema" in
  projection|audit|all) ;;
  *) die "--schema must be projection, audit, or all" ;;
esac

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$repo_root"

projection_schema="bot-core/src/main/resources/db/projection/postgresql-schema.sql"
audit_schema="bot-core/src/main/resources/db/audit/pause-governance-postgresql-schema.sql"
[[ -f "$projection_schema" ]] || die "missing projection schema: $projection_schema"
[[ -f "$audit_schema" ]] || die "missing audit schema: $audit_schema"

selected_schemas=()
if [[ "$schema" == "projection" || "$schema" == "all" ]]; then
  selected_schemas+=("$projection_schema")
fi
if [[ "$schema" == "audit" || "$schema" == "all" ]]; then
  selected_schemas+=("$audit_schema")
fi
for file in "${selected_schemas[@]}"; do
  safe_scan_sql "$file"
done

mkdir -p "$output_dir"
report="$output_dir/postgresql-state-migration-${environment}-${schema}.md"
{
  echo "# PostgreSQL State Migration"
  echo
  echo "- Environment: ${environment}"
  echo "- Schema: ${schema}"
  echo "- Plan only: ${plan_only}"
  echo "- Projection schema: ${projection_schema} $(sha256_file "$projection_schema")"
  echo "- Audit schema: ${audit_schema} $(sha256_file "$audit_schema")"
} > "$report"

if [[ "$plan_only" == "true" ]]; then
  echo "- Status: PLAN_VALIDATED" >> "$report"
  cat "$report"
  exit 0
fi

[[ -n "$password_env" ]] || die "--password-env is required unless --plan-only is used"
[[ -n "${!password_env-}" ]] || die "password environment variable is unset: $password_env"
export PGPASSWORD="${!password_env}"

if [[ -z "$psql_url" ]]; then
  [[ -n "$jdbc_url" ]] || die "--jdbc-url or --psql-url is required"
  [[ -n "$username" ]] || die "--username is required when deriving psql connection from JDBC URL"
  database=$(jdbc_database "$jdbc_url")
  [[ -n "$database" ]] || die "could not parse database from JDBC URL"
  if cloud_instance=$(jdbc_cloud_sql_instance "$jdbc_url"); then
    [[ -n "$cloud_sql_proxy" ]] || die "--cloud-sql-proxy is required for Cloud SQL JDBC URLs"
    start_cloud_sql_proxy "$cloud_sql_proxy" "$cloud_instance" "$local_host" "$local_port"
    psql_url="postgresql://${username}@${local_host}:${local_port}/${database}"
  else
    psql_url="${jdbc_url#jdbc:}"
  fi
fi

require_command "$psql_bin"
for file in "${selected_schemas[@]}"; do
  "$psql_bin" "$psql_url" --set ON_ERROR_STOP=1 --file "$file"
done

echo "- Status: APPLIED" >> "$report"
echo "PostgreSQL migration applied for ${environment}/${schema}"
cat "$report"
