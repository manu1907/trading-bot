package io.github.manu.audit;

import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class JdbcPauseGovernanceAuditStore implements PauseGovernanceAuditStore {

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final String url;
    private final String username;
    private final String password;
    private final String tablePrefix;
    private final ObjectMapper jsonMapper;

    public JdbcPauseGovernanceAuditStore(
            String url,
            String username,
            String password,
            String tablePrefix,
            ObjectMapper jsonMapper
    ) {
        this.url = requireText(url, "url");
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.tablePrefix = requireTablePrefix(tablePrefix);
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public void initializeSchema() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String statementSql : schemaStatements()) {
                statement.executeUpdate(statementSql);
            }
        } catch (SQLException exception) {
            throw new PauseGovernanceAuditStoreException(
                    "Failed to initialize JDBC pause governance audit schema",
                    exception
            );
        }
    }

    @Override
    public void record(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event) {
        Objects.requireNonNull(event, "event");
        String sql = "insert into "
                + table("events")
                + " (event_type, provider, environment, account, market, symbol, pause_scope, pause_target,"
                + " remediation_id, event_id, source_pause_remediation_id, command_id, client_order_id,"
                + " decision_id, risk_decision, outcome, actor, reason, expires_at, invalid_reason,"
                + " occurred_at, payload)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, event.eventType());
            statement.setString(index++, event.provider());
            statement.setString(index++, event.environment());
            statement.setString(index++, event.account());
            statement.setString(index++, event.market());
            statement.setString(index++, event.symbol());
            statement.setString(index++, event.pauseScope());
            statement.setString(index++, event.pauseTarget());
            statement.setString(index++, event.remediationId());
            statement.setString(index++, event.eventId());
            statement.setString(index++, event.sourcePauseRemediationId());
            statement.setString(index++, event.commandId());
            statement.setString(index++, event.clientOrderId());
            statement.setString(index++, event.decisionId());
            statement.setString(index++, event.riskDecision());
            statement.setString(index++, event.outcome());
            statement.setString(index++, event.actor());
            statement.setString(index++, event.reason());
            statement.setString(index++, event.expiresAt());
            statement.setString(index++, event.invalidReason());
            statement.setString(index++, event.occurredAt() == null ? null : event.occurredAt().toString());
            statement.setString(index, jsonMapper.writeValueAsString(event));
            statement.executeUpdate();
        } catch (SQLException | RuntimeException exception) {
            throw new PauseGovernanceAuditStoreException(
                    "Failed to insert JDBC pause governance audit event",
                    exception
            );
        }
    }

    @Override
    public List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> recent(
            String provider,
            String environment,
            String account,
            String market,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String sql = "select payload from "
                + table("events")
                + " where provider = ? and environment = ? and account = ? and market = ?"
                + " order by occurred_at desc nulls last, sequence_number desc limit ?";
        List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> events = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider);
            statement.setString(2, environment);
            statement.setString(3, account);
            statement.setString(4, market);
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(jsonMapper.readValue(
                            rows.getString("payload"),
                            PauseGovernanceAuditTrail.PauseGovernanceAuditEvent.class
                    ));
                }
            }
            return List.copyOf(events);
        } catch (SQLException | RuntimeException exception) {
            throw new PauseGovernanceAuditStoreException(
                    "Failed to query JDBC pause governance audit events",
                    exception
            );
        }
    }

    @Override
    public String storeName() {
        return "jdbc";
    }

    private Connection connect() throws SQLException {
        if (username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    private List<String> schemaStatements() {
        return List.of(
                "create table if not exists " + table("events") + " ("
                        + "sequence_number bigint generated by default as identity primary key,"
                        + "event_type varchar(128), provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, symbol varchar(128), pause_scope varchar(64),"
                        + "pause_target varchar(256), remediation_id varchar(512), event_id varchar(512),"
                        + "source_pause_remediation_id varchar(512), command_id varchar(512),"
                        + "client_order_id varchar(512), decision_id varchar(512), risk_decision varchar(64),"
                        + "outcome varchar(128), actor varchar(256), reason varchar(2048), expires_at varchar(64),"
                        + "invalid_reason varchar(2048), occurred_at varchar(64), payload text not null)",
                "create index if not exists " + table("scope_idx") + " on " + table("events")
                        + " (provider, environment, account, market, occurred_at, sequence_number)",
                "create index if not exists " + table("event_id_idx") + " on " + table("events")
                        + " (event_id)",
                "create index if not exists " + table("remediation_id_idx") + " on " + table("events")
                        + " (remediation_id)",
                "create index if not exists " + table("pause_target_idx") + " on " + table("events")
                        + " (provider, environment, account, market, pause_scope, pause_target)"
        );
    }

    private String table(String suffix) {
        return tablePrefix + suffix;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String requireTablePrefix(String value) {
        String candidate = requireText(value, "tablePrefix");
        if (!TABLE_PREFIX.matcher(candidate).matches()) {
            throw new IllegalArgumentException("tablePrefix must contain only letters, digits, and underscores");
        }
        return candidate;
    }
}
