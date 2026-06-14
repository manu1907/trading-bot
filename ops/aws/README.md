# AWS Operations

This directory contains AWS deployment contracts for trading-bot.

The contract implements the neutral schema in
`ops/deployment/deployment-contract.yml`. AWS-specific choices must not change
the app-facing runtime variables or trading behavior.

`demo-usdm-futures-deployment.yml` is the AWS equivalent of the Google Cloud
demo contract. It keeps the same app-facing runtime surface and maps
cloud-specific services as follows:

- Runtime: ECS Fargate.
- Image registry: ECR, provided through `TRADING_BOT_IMAGE`.
- Secrets: AWS Secrets Manager, referenced by secret name and `AWSCURRENT`.
- Audit database: RDS PostgreSQL.
- Audit backups: RDS automated backups with at least 7 recovery days and a
  restore drill every 90 days.
- Projection database: RDS PostgreSQL through `TRADING_PROJECTION_JDBC_*`
  secrets, with file snapshots disabled for ECS.
- Projection retention: 180 days, compaction enabled while preserving the latest
  snapshot and applied event ids, RDS automated backups with at least 7 recovery
  days, and a restore drill every 90 days.
- Journal archive: S3 using `trading_event_archive_layout_v1`.

The AWS contract is deliberately parallel to the Google Cloud contract. Moving
between clouds must not require changing trading, risk, remediation, or exchange
code. Only deployment automation and cloud service bindings should differ.
