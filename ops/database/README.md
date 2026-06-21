# Database Operations

Deployment-owned database migration lives here. The bot runtime must keep JDBC
schema initialization disabled in Cloud Run; schema changes are applied through
ops automation before deployment or promotion.

## PostgreSQL State Migration

`migrate-postgresql-state.sh` applies the checked-in PostgreSQL schemas for
persistent bot state:

- Projection state: `bot-core/src/main/resources/db/projection/postgresql-schema.sql`
- Pause-governance audit state: `bot-core/src/main/resources/db/audit/pause-governance-postgresql-schema.sql`

Local validation without connecting to a database:

```bash
ops/database/migrate-postgresql-state.sh \
  --environment demo \
  --schema all \
  --plan-only
```

Cloud SQL execution is normally handled by
`.github/workflows/migrate-google-cloud-postgresql-state.yml`. The workflow is
manual, environment-gated, verifies the requested commit passed `Security`,
authenticates through `GCP_CLOUD_SQL_MIGRATION_SERVICE_ACCOUNT`, reads the
JDBC URL/username/password bindings from Google Secret Manager, connects through
Cloud SQL Auth Proxy, applies the projection and/or audit schema, and uploads
migration evidence.

For real, the workflow also requires
`confirm_real_migration=RUN_REAL_DATABASE_MIGRATION`. The migration workflow does
not enable exchange execution and does not deploy the bot; it only prepares the
state database schema required by the selected deployment contract.

## Safety Rules

- Use the same checked-in SQL files for demo and real.
- Demo and real differ by environment gate, database secrets, and approval only.
- Do not run schema creation from the hot trading runtime in Cloud Run.
- Keep migration SQL idempotent: `create table if not exists`, `create index if
  not exists`, and `alter table ... add column if not exists`.
- Destructive DDL/DML such as drop, truncate, or delete is rejected by the
  migration script.

## Journal Segment Archive

`archive-journal-segments.sh` archives raw trading-event journal segment
directories for restore/replay evidence. It preserves journal files byte-for-byte,
writes `journal-archive-manifest.tsv`, scans for obvious secret-bearing content,
and uploads under:

```text
gs://$GCP_JOURNAL_ARCHIVE_BUCKET/<environment>/<provider>/<account>/<market>/journal-segments/v1/<archive-id>/
```

Local validation without calling Google Cloud:

```bash
ops/database/archive-journal-segments.sh \
  --environment demo \
  --provider binance \
  --account main \
  --market usdm_futures \
  --archive-id restore-drill-001 \
  --journal-dir data/journal/trading-events \
  --bucket example-journal-bucket \
  --validate-only
```

`.github/workflows/archive-google-cloud-journal.yml` wraps the script as a
manual, environment-gated workflow. It downloads a produced journal artifact
from a source workflow run, authenticates through
`GCP_JOURNAL_ARCHIVE_SERVICE_ACCOUNT`, writes the manifest, uploads to
`GCP_JOURNAL_ARCHIVE_BUCKET`, and uploads the manifest as workflow evidence.
For real, the workflow requires `confirm_real_archive=ARCHIVE_REAL_JOURNAL`.
