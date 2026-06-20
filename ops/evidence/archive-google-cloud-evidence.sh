#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ops/evidence/archive-google-cloud-evidence.sh --environment demo|real --evidence-type TYPE --evidence-id ID --bundle-dir PATH --bucket BUCKET [options]

Archives a sanitized evidence bundle to Google Cloud Storage. The script scans
all files for obvious secret-bearing patterns before upload, writes a local
archive manifest, and uploads the bundle under:

  gs://BUCKET/ENVIRONMENT/EVIDENCE_TYPE/EVIDENCE_ID/

Options:
  --environment VALUE      demo or real.
  --evidence-type VALUE    release, burn-in, smoke, rollback, incident, drill, or promotion.
  --evidence-id VALUE      Stable evidence id. Use workflow run id, release id, or burn-in id.
  --bundle-dir PATH        Directory containing the evidence bundle to archive.
  --bucket VALUE           Google Cloud Storage bucket name, without gs://.
  --project VALUE          Optional Google Cloud project id for gcloud storage commands.
  --dry-run                Validate and write manifest without uploading.
  -h, --help               Show this help.

The script does not read Secret Manager and must not be pointed at raw secret or
credential files. Evidence must prove secret bindings by name/version/checksum,
not by value.
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
    die "refusing to archive possible secret-bearing evidence bundle: $dir"
  fi
}

safe_path_segment() {
  local value=$1
  [[ "$value" =~ ^[a-zA-Z0-9._=-]+$ ]] || die "unsafe path segment: $value"
}

environment=""
evidence_type=""
evidence_id=""
bundle_dir=""
bucket=""
project=""
dry_run="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment=${2:?}; shift 2 ;;
    --evidence-type) evidence_type=${2:?}; shift 2 ;;
    --evidence-id) evidence_id=${2:?}; shift 2 ;;
    --bundle-dir) bundle_dir=${2:?}; shift 2 ;;
    --bucket) bucket=${2:?}; shift 2 ;;
    --project) project=${2:?}; shift 2 ;;
    --dry-run) dry_run="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$environment" in
  demo|real) ;;
  *) die "--environment must be demo or real" ;;
esac
case "$evidence_type" in
  release|burn-in|smoke|rollback|incident|drill|promotion) ;;
  *) die "--evidence-type must be release, burn-in, smoke, rollback, incident, drill, or promotion" ;;
esac
[[ -n "$evidence_id" ]] || die "--evidence-id is required"
[[ -d "$bundle_dir" ]] || die "--bundle-dir must be an existing directory"
[[ -n "$bucket" ]] || die "--bucket is required"
safe_path_segment "$evidence_id"
safe_path_segment "$bucket"

forbidden_secret_scan "$bundle_dir"

manifest="$bundle_dir/archive-manifest.tsv"
printf 'sha256	relative_path\n' > "$manifest"
while IFS= read -r file; do
  relative="${file#"$bundle_dir"/}"
  printf '%s\t%s\n' "$(sha256_file "$file")" "$relative" >> "$manifest"
done < <(find "$bundle_dir" -type f ! -name archive-manifest.tsv | sort)

prefix="${environment}/${evidence_type}/${evidence_id}"
destination="gs://${bucket}/${prefix}/"

if [[ "$dry_run" == "true" ]]; then
  echo "Validated evidence bundle for ${destination}"
  exit 0
fi

require_command gcloud
args=("storage" "cp" "--recursive" "$bundle_dir" "$destination")
if [[ -n "$project" ]]; then
  args+=("--project=$project")
fi
gcloud "${args[@]}"
echo "Archived evidence bundle to ${destination}"
