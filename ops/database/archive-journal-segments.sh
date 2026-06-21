#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/database/archive-journal-segments.sh --environment demo|real --provider PROVIDER --account ACCOUNT --market MARKET --archive-id ID --journal-dir PATH --bucket BUCKET [options]

Archives raw trading-event journal segments for restore/replay evidence. This is
deployment-owned backup automation, not hot-path trading logic. The script scans
journal files for obvious secret-bearing patterns, writes a SHA-256 manifest,
and uploads the raw journal directory under a deterministic restore path.

Destination:
  gs://BUCKET/ENVIRONMENT/PROVIDER/ACCOUNT/MARKET/journal-segments/v1/ARCHIVE_ID/

Options:
  --environment VALUE      demo or real.
  --provider VALUE         Provider id, for example binance.
  --account VALUE          Account id, for example main.
  --market VALUE           Market id, for example usdm_futures.
  --archive-id VALUE       Stable archive id, for example workflow run id or timestamp.
  --journal-dir PATH       Directory containing raw journal segment files.
  --bucket VALUE           Google Cloud Storage bucket name, without gs://.
  --project VALUE          Optional Google Cloud project id for gcloud storage commands.
  --validate-only          Validate and write manifest without uploading.
  -h, --help               Show this help.
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

safe_path_segment() {
  local value=$1
  [[ "$value" =~ ^[a-zA-Z0-9._=-]+$ ]] || die "unsafe path segment: $value"
}

forbidden_secret_scan() {
  local dir=$1
  local slack_webhook_marker="hooks[.]slack[.]com"
  local slack_bot_token_marker="xoxb""-"
  local github_classic_token_marker="ghp""_"
  local github_fine_grained_token_marker="github_pat""_"
  local private_key_marker="-----""BEGIN"
  local jdbc_marker="jdbc:postgresql""://"
  local aws_access_key_marker="AKIA[0-9A-Z]{16}"
  local google_api_key_marker="AI""za[0-9A-Za-z_-]+"
  local pattern="${slack_webhook_marker}|${slack_bot_token_marker}|${github_classic_token_marker}|${github_fine_grained_token_marker}|${private_key_marker}|${jdbc_marker}|${aws_access_key_marker}|${google_api_key_marker}"

  if grep -R -I -E -q "$pattern" "$dir"; then
    die "refusing to archive possible secret-bearing journal directory: $dir"
  fi
}

environment=""
provider=""
account=""
market=""
archive_id=""
journal_dir=""
bucket=""
project=""
validate_only="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment=${2:?}; shift 2 ;;
    --provider) provider=${2:?}; shift 2 ;;
    --account) account=${2:?}; shift 2 ;;
    --market) market=${2:?}; shift 2 ;;
    --archive-id) archive_id=${2:?}; shift 2 ;;
    --journal-dir) journal_dir=${2:?}; shift 2 ;;
    --bucket) bucket=${2:?}; shift 2 ;;
    --project) project=${2:?}; shift 2 ;;
    --validate-only) validate_only="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$environment" in
  demo|real) ;;
  *) die "--environment must be demo or real" ;;
esac
[[ -n "$provider" ]] || die "--provider is required"
[[ -n "$account" ]] || die "--account is required"
[[ -n "$market" ]] || die "--market is required"
[[ -n "$archive_id" ]] || die "--archive-id is required"
[[ -d "$journal_dir" ]] || die "--journal-dir must be an existing directory"
[[ -n "$bucket" ]] || die "--bucket is required"

safe_path_segment "$provider"
safe_path_segment "$account"
safe_path_segment "$market"
safe_path_segment "$archive_id"
safe_path_segment "$bucket"

if ! find "$journal_dir" -type f | grep -q .; then
  die "journal directory contains no files: $journal_dir"
fi

forbidden_secret_scan "$journal_dir"

manifest="$journal_dir/journal-archive-manifest.tsv"
printf 'sha256\trelative_path\n' > "$manifest"
while IFS= read -r file; do
  relative="${file#"$journal_dir"/}"
  printf '%s\t%s\n' "$(sha256_file "$file")" "$relative" >> "$manifest"
done < <(find "$journal_dir" -type f ! -name journal-archive-manifest.tsv | sort)

prefix="${environment}/${provider}/${account}/${market}/journal-segments/v1/${archive_id}"
destination="gs://${bucket}/${prefix}/"

if [[ "$validate_only" == "true" ]]; then
  echo "Validated journal archive for ${destination}"
  exit 0
fi

require_command gcloud
args=("storage" "cp" "--recursive" "$journal_dir" "$destination")
if [[ -n "$project" ]]; then
  args+=("--project=$project")
fi
gcloud "${args[@]}"
echo "Archived journal segments to ${destination}"
