# AWS Operations

This directory contains AWS deployment contracts for trading-bot.

The contract implements the neutral schema in
`ops/deployment/deployment-contract.yml`. AWS-specific choices must not change
the app-facing runtime variables or trading behavior.

`demo-usdm-futures-deployment.yml` and `real-usdm-futures-deployment.yml` are
the AWS equivalents of the Google Cloud deployment contracts. They keep the same
app-facing runtime surface and map cloud-specific services as follows:

- Runtime: ECS Fargate.
- Runtime image: root `Dockerfile`, built from the `bot-app` boot jar and
  source-controlled config.
- Image registry: ECR, provided through `TRADING_BOT_IMAGE`.
- Secrets: AWS Secrets Manager, referenced by secret name and `AWSCURRENT`.
- Audit database: RDS PostgreSQL.
- Audit backups: RDS automated backups with at least 7 recovery days and a
  restore drill every 90 days.
- Projection database: RDS PostgreSQL through `TRADING_PROJECTION_JDBC_*`
  secrets, with file snapshots disabled for ECS.
- Projection retention: 180 days in demo and 365 days in real, compaction
  enabled while preserving the latest snapshot and applied event ids, RDS
  automated backups with at least 7 recovery days in demo and 35 recovery days
  in real, and restore drills every 90 days in demo and 30 days in real.
- Journal archive: S3 using `trading_event_archive_layout_v1`.

The real AWS contract uses isolated `trading-bot/real/...` secret names and
starts with remediation exchange execution disabled plus an empty real-operation
allowlist until demo promotion evidence and deployment approval are supplied.

The AWS contracts are deliberately parallel to the Google Cloud contracts.
Moving between clouds must not require changing trading, risk, remediation, or
exchange code. Only deployment automation and cloud service bindings should
differ.

GitHub Actions currently validates the image build without publishing. The image
build already carries OCI source/revision/version/created metadata and uploads
Buildx metadata as a CI artifact. AWS publish/deploy automation must later push
the same image contract to ECR and deploy it with the same app-facing runtime
variables and secret names.
