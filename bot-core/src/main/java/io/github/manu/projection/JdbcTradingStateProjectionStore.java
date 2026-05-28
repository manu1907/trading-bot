package io.github.manu.projection;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JdbcTradingStateProjectionStore implements TradingStateProjectionStore {

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final String url;
    private final String username;
    private final String password;
    private final String tablePrefix;

    public JdbcTradingStateProjectionStore(String url, String username, String password, String tablePrefix) {
        this.url = requireText(url, "url");
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.tablePrefix = requireTablePrefix(tablePrefix);
    }

    public void initializeSchema() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String statementSql : schemaStatements()) {
                statement.executeUpdate(statementSql);
            }
        } catch (SQLException ex) {
            throw new ProjectionStoreException("Failed to initialize JDBC trading-state projection schema", ex);
        }
    }

    @Override
    public Optional<TradingStateSnapshot> load() {
        try (Connection connection = connect()) {
            TradingStateSnapshot snapshot = new TradingStateSnapshot(
                    loadBalances(connection),
                    loadPositions(connection),
                    loadOrders(connection),
                    loadRisks(connection),
                    loadManualReviewDecisions(connection),
                    loadAppliedEventIds(connection)
            );
            if (snapshot.balances().isEmpty()
                    && snapshot.positions().isEmpty()
                    && snapshot.orders().isEmpty()
                    && snapshot.risks().isEmpty()
                    && snapshot.manualReviewDecisions().isEmpty()
                    && snapshot.appliedEventIds().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (SQLException ex) {
            throw new ProjectionStoreException("Failed to load JDBC trading-state projection snapshot", ex);
        }
    }

    @Override
    public void save(TradingStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try (Connection connection = connect()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                clear(connection);
                saveBalances(connection, snapshot.balances());
                savePositions(connection, snapshot.positions());
                saveOrders(connection, snapshot.orders());
                saveRisks(connection, snapshot.risks());
                saveManualReviewDecisions(connection, snapshot.manualReviewDecisions());
                saveAppliedEventIds(connection, snapshot.appliedEventIds());
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw new ProjectionStoreException("Failed to save JDBC trading-state projection snapshot", ex);
        }
    }

    private Connection connect() throws SQLException {
        if (username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    private void clear(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from " + table("applied_event_ids"));
            statement.executeUpdate("delete from " + table("manual_review_decisions"));
            statement.executeUpdate("delete from " + table("risks"));
            statement.executeUpdate("delete from " + table("orders"));
            statement.executeUpdate("delete from " + table("positions"));
            statement.executeUpdate("delete from " + table("balances"));
        }
    }

    private List<TradingStateProjection.BalanceState> loadBalances(Connection connection) throws SQLException {
        String sql = "select provider, environment, account, market, asset, wallet_balance, cross_wallet_balance,"
                + " available_balance, balance_delta, update_reason, updated_at, event_id from "
                + table("balances")
                + " order by state_key";
        List<TradingStateProjection.BalanceState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                states.add(new TradingStateProjection.BalanceState(
                        rows.getString("provider"),
                        rows.getString("environment"),
                        rows.getString("account"),
                        rows.getString("market"),
                        rows.getString("asset"),
                        rows.getString("wallet_balance"),
                        rows.getString("cross_wallet_balance"),
                        rows.getString("available_balance"),
                        rows.getString("balance_delta"),
                        rows.getString("update_reason"),
                        instant(rows.getString("updated_at")),
                        rows.getString("event_id")
                ));
            }
        }
        return List.copyOf(states);
    }

    private List<TradingStateProjection.PositionState> loadPositions(Connection connection) throws SQLException {
        String sql = "select provider, environment, account, market, symbol, position_side, position_amount,"
                + " entry_price, mark_price, unrealized_pnl, update_source, external_intervention,"
                + " intervention_reason, updated_at, event_id from "
                + table("positions")
                + " order by state_key";
        List<TradingStateProjection.PositionState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                states.add(new TradingStateProjection.PositionState(
                        rows.getString("provider"),
                        rows.getString("environment"),
                        rows.getString("account"),
                        rows.getString("market"),
                        rows.getString("symbol"),
                        rows.getString("position_side"),
                        rows.getString("position_amount"),
                        rows.getString("entry_price"),
                        rows.getString("mark_price"),
                        rows.getString("unrealized_pnl"),
                        rows.getString("update_source"),
                        rows.getBoolean("external_intervention"),
                        rows.getString("intervention_reason"),
                        instant(rows.getString("updated_at")),
                        rows.getString("event_id")
                ));
            }
        }
        return List.copyOf(states);
    }

    private List<TradingStateProjection.OrderState> loadOrders(Connection connection) throws SQLException {
        String sql = "select provider, environment, account, market, symbol, command_id, client_order_id, exchange_order_id,"
                + " status, exchange_status, price, original_quantity, executed_quantity, average_price,"
                + " cumulative_quote, update_source, execution_type, managed_by_bot, external_intervention,"
                + " intervention_reason, updated_at, event_id from "
                + table("orders")
                + " order by state_key";
        List<TradingStateProjection.OrderState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                states.add(new TradingStateProjection.OrderState(
                        rows.getString("provider"),
                        rows.getString("environment"),
                        rows.getString("account"),
                        rows.getString("market"),
                        rows.getString("symbol"),
                        rows.getString("command_id"),
                        rows.getString("client_order_id"),
                        rows.getString("exchange_order_id"),
                        rows.getString("status"),
                        rows.getString("exchange_status"),
                        rows.getString("price"),
                        rows.getString("original_quantity"),
                        rows.getString("executed_quantity"),
                        rows.getString("average_price"),
                        rows.getString("cumulative_quote"),
                        rows.getString("update_source"),
                        rows.getString("execution_type"),
                        rows.getBoolean("managed_by_bot"),
                        rows.getBoolean("external_intervention"),
                        rows.getString("intervention_reason"),
                        instant(rows.getString("updated_at")),
                        rows.getString("event_id")
                ));
            }
        }
        return List.copyOf(states);
    }

    private List<TradingStateProjection.RiskState> loadRisks(Connection connection) throws SQLException {
        String sql = "select provider, environment, account, market, risk_scope, symbol, underlying, risk_level,"
                + " delta, gamma, theta, vega, margin_balance, maintenance_margin, updated_at, event_id from "
                + table("risks")
                + " order by state_key";
        List<TradingStateProjection.RiskState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                states.add(new TradingStateProjection.RiskState(
                        rows.getString("provider"),
                        rows.getString("environment"),
                        rows.getString("account"),
                        rows.getString("market"),
                        rows.getString("risk_scope"),
                        rows.getString("symbol"),
                        rows.getString("underlying"),
                        rows.getString("risk_level"),
                        rows.getString("delta"),
                        rows.getString("gamma"),
                        rows.getString("theta"),
                        rows.getString("vega"),
                        rows.getString("margin_balance"),
                        rows.getString("maintenance_margin"),
                        instant(rows.getString("updated_at")),
                        rows.getString("event_id")
                ));
            }
        }
        return List.copyOf(states);
    }

    private List<TradingStateProjection.ManualReviewDecisionState> loadManualReviewDecisions(
            Connection connection
    ) throws SQLException {
        String sql = "select provider, environment, account, market, symbol, command_id, signal_id, strategy_id,"
                + " decision_id, reasons, attributes, updated_at, event_id from "
                + table("manual_review_decisions")
                + " order by state_key";
        List<TradingStateProjection.ManualReviewDecisionState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                states.add(new TradingStateProjection.ManualReviewDecisionState(
                        rows.getString("provider"),
                        rows.getString("environment"),
                        rows.getString("account"),
                        rows.getString("market"),
                        rows.getString("symbol"),
                        rows.getString("command_id"),
                        rows.getString("signal_id"),
                        rows.getString("strategy_id"),
                        rows.getString("decision_id"),
                        split(rows.getString("reasons")),
                        splitMap(rows.getString("attributes")),
                        instant(rows.getString("updated_at")),
                        rows.getString("event_id")
                ));
            }
        }
        return List.copyOf(states);
    }

    private List<String> loadAppliedEventIds(Connection connection) throws SQLException {
        String sql = "select event_id from " + table("applied_event_ids") + " order by sequence_number";
        List<String> eventIds = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                eventIds.add(rows.getString("event_id"));
            }
        }
        return List.copyOf(eventIds);
    }

    private void saveBalances(Connection connection, List<TradingStateProjection.BalanceState> states) throws SQLException {
        String sql = "insert into "
                + table("balances")
                + " (state_key, provider, environment, account, market, asset, wallet_balance, cross_wallet_balance,"
                + " available_balance, balance_delta, update_reason, updated_at, event_id)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TradingStateProjection.BalanceState state : states) {
                int index = 1;
                statement.setString(index++, key(state.provider(), state.environment(), state.account(), state.market(), state.asset()));
                statement.setString(index++, state.provider());
                statement.setString(index++, state.environment());
                statement.setString(index++, state.account());
                statement.setString(index++, state.market());
                statement.setString(index++, state.asset());
                statement.setString(index++, state.walletBalance());
                statement.setString(index++, state.crossWalletBalance());
                statement.setString(index++, state.availableBalance());
                statement.setString(index++, state.balanceDelta());
                statement.setString(index++, state.updateReason());
                statement.setString(index++, string(state.updatedAt()));
                statement.setString(index, state.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void savePositions(Connection connection, List<TradingStateProjection.PositionState> states) throws SQLException {
        String sql = "insert into "
                + table("positions")
                + " (state_key, provider, environment, account, market, symbol, position_side, position_amount,"
                + " entry_price, mark_price, unrealized_pnl, update_source, external_intervention,"
                + " intervention_reason, updated_at, event_id)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TradingStateProjection.PositionState state : states) {
                int index = 1;
                statement.setString(index++, key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.symbol(),
                        state.positionSide()
                ));
                statement.setString(index++, state.provider());
                statement.setString(index++, state.environment());
                statement.setString(index++, state.account());
                statement.setString(index++, state.market());
                statement.setString(index++, state.symbol());
                statement.setString(index++, state.positionSide());
                statement.setString(index++, state.positionAmount());
                statement.setString(index++, state.entryPrice());
                statement.setString(index++, state.markPrice());
                statement.setString(index++, state.unrealizedPnl());
                statement.setString(index++, state.updateSource());
                statement.setBoolean(index++, state.externalIntervention());
                statement.setString(index++, state.interventionReason());
                statement.setString(index++, string(state.updatedAt()));
                statement.setString(index, state.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveOrders(Connection connection, List<TradingStateProjection.OrderState> states) throws SQLException {
        String sql = "insert into "
                + table("orders")
                + " (state_key, provider, environment, account, market, symbol, command_id, client_order_id,"
                + " exchange_order_id, status, exchange_status, price, original_quantity, executed_quantity,"
                + " average_price, cumulative_quote, update_source, execution_type, managed_by_bot,"
                + " external_intervention, intervention_reason, updated_at, event_id)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TradingStateProjection.OrderState state : states) {
                int index = 1;
                statement.setString(index++, key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.symbol(),
                        state.clientOrderId()
                ));
                statement.setString(index++, state.provider());
                statement.setString(index++, state.environment());
                statement.setString(index++, state.account());
                statement.setString(index++, state.market());
                statement.setString(index++, state.symbol());
                statement.setString(index++, state.commandId());
                statement.setString(index++, state.clientOrderId());
                statement.setString(index++, state.exchangeOrderId());
                statement.setString(index++, state.status());
                statement.setString(index++, state.exchangeStatus());
                statement.setString(index++, state.price());
                statement.setString(index++, state.originalQuantity());
                statement.setString(index++, state.executedQuantity());
                statement.setString(index++, state.averagePrice());
                statement.setString(index++, state.cumulativeQuote());
                statement.setString(index++, state.updateSource());
                statement.setString(index++, state.executionType());
                statement.setBoolean(index++, state.managedByBot());
                statement.setBoolean(index++, state.externalIntervention());
                statement.setString(index++, state.interventionReason());
                statement.setString(index++, string(state.updatedAt()));
                statement.setString(index, state.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveRisks(Connection connection, List<TradingStateProjection.RiskState> states) throws SQLException {
        String sql = "insert into "
                + table("risks")
                + " (state_key, provider, environment, account, market, risk_scope, symbol, underlying,"
                + " risk_level, delta, gamma, theta, vega, margin_balance, maintenance_margin, updated_at, event_id)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TradingStateProjection.RiskState state : states) {
                int index = 1;
                statement.setString(index++, key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.riskScope(),
                        riskEntityId(state)
                ));
                statement.setString(index++, state.provider());
                statement.setString(index++, state.environment());
                statement.setString(index++, state.account());
                statement.setString(index++, state.market());
                statement.setString(index++, state.riskScope());
                statement.setString(index++, state.symbol());
                statement.setString(index++, state.underlying());
                statement.setString(index++, state.riskLevel());
                statement.setString(index++, state.delta());
                statement.setString(index++, state.gamma());
                statement.setString(index++, state.theta());
                statement.setString(index++, state.vega());
                statement.setString(index++, state.marginBalance());
                statement.setString(index++, state.maintenanceMargin());
                statement.setString(index++, string(state.updatedAt()));
                statement.setString(index, state.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveManualReviewDecisions(
            Connection connection,
            List<TradingStateProjection.ManualReviewDecisionState> states
    ) throws SQLException {
        String sql = "insert into "
                + table("manual_review_decisions")
                + " (state_key, provider, environment, account, market, symbol, command_id, signal_id,"
                + " strategy_id, decision_id, reasons, attributes, updated_at, event_id)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TradingStateProjection.ManualReviewDecisionState state : states) {
                int index = 1;
                statement.setString(index++, key(
                        state.provider(),
                        state.environment(),
                        state.account(),
                        state.market(),
                        state.commandId()
                ));
                statement.setString(index++, state.provider());
                statement.setString(index++, state.environment());
                statement.setString(index++, state.account());
                statement.setString(index++, state.market());
                statement.setString(index++, state.symbol());
                statement.setString(index++, state.commandId());
                statement.setString(index++, state.signalId());
                statement.setString(index++, state.strategyId());
                statement.setString(index++, state.decisionId());
                statement.setString(index++, join(state.reasons()));
                statement.setString(index++, joinMap(state.attributes()));
                statement.setString(index++, string(state.updatedAt()));
                statement.setString(index, state.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveAppliedEventIds(Connection connection, List<String> eventIds) throws SQLException {
        String sql = "insert into " + table("applied_event_ids") + " (sequence_number, event_id) values (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < eventIds.size(); index++) {
                statement.setInt(1, index);
                statement.setString(2, eventIds.get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<String> schemaStatements() {
        return List.of(
                "create table if not exists " + table("balances") + " ("
                        + "state_key varchar(512) primary key, provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, asset varchar(128) not null,"
                        + "wallet_balance varchar(128), cross_wallet_balance varchar(128),"
                        + "available_balance varchar(128), balance_delta varchar(128), update_reason varchar(128),"
                        + "updated_at varchar(64) not null, event_id varchar(512))",
                "create table if not exists " + table("positions") + " ("
                        + "state_key varchar(512) primary key, provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, symbol varchar(128) not null,"
                        + "position_side varchar(64) not null, position_amount varchar(128), entry_price varchar(128),"
                        + "mark_price varchar(128), unrealized_pnl varchar(128), update_source varchar(64),"
                        + "external_intervention boolean not null default false, intervention_reason varchar(256),"
                        + "updated_at varchar(64) not null,"
                        + "event_id varchar(512))",
                "create table if not exists " + table("orders") + " ("
                        + "state_key varchar(512) primary key, provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, symbol varchar(128) not null,"
                        + "command_id varchar(512), client_order_id varchar(256) not null,"
                        + "exchange_order_id varchar(256), status varchar(64), exchange_status varchar(64),"
                        + "price varchar(128),"
                        + "original_quantity varchar(128), executed_quantity varchar(128),"
                        + "average_price varchar(128), cumulative_quote varchar(128), update_source varchar(64),"
                        + "execution_type varchar(64), managed_by_bot boolean not null default false,"
                        + "external_intervention boolean not null default false, intervention_reason varchar(256),"
                        + "updated_at varchar(64) not null, event_id varchar(512))",
                "create table if not exists " + table("risks") + " ("
                        + "state_key varchar(512) primary key, provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, risk_scope varchar(128) not null,"
                        + "symbol varchar(128), underlying varchar(128), risk_level varchar(128), delta varchar(128),"
                        + "gamma varchar(128), theta varchar(128), vega varchar(128), margin_balance varchar(128),"
                        + "maintenance_margin varchar(128), updated_at varchar(64) not null, event_id varchar(512))",
                "create table if not exists " + table("manual_review_decisions") + " ("
                        + "state_key varchar(512) primary key, provider varchar(64) not null,"
                        + "environment varchar(64) not null, account varchar(128) not null,"
                        + "market varchar(128) not null, symbol varchar(128), command_id varchar(512) not null,"
                        + "signal_id varchar(512), strategy_id varchar(256), decision_id varchar(512),"
                        + "reasons varchar(2048), attributes varchar(4096), updated_at varchar(64) not null,"
                        + "event_id varchar(512))",
                "create table if not exists " + table("applied_event_ids") + " ("
                        + "sequence_number integer primary key, event_id varchar(512) not null unique)"
        );
    }

    private String table(String suffix) {
        return tablePrefix + suffix;
    }

    private String key(String... parts) {
        return String.join("|", parts);
    }

    private String riskEntityId(TradingStateProjection.RiskState state) {
        if (state.symbol() != null) {
            return state.symbol();
        }
        if (state.underlying() != null) {
            return state.underlying();
        }
        return state.account();
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(this::encode)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String encoded : value.split(",")) {
            values.add(decode(encoded));
        }
        return List.copyOf(values);
    }

    private String joinMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private Map<String, String> splitMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                values.put(decode(parts[0]), decode(parts[1]));
            }
        }
        return Map.copyOf(values);
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String requireTablePrefix(String value) {
        String checked = requireText(value, "tablePrefix");
        if (!TABLE_PREFIX.matcher(checked).matches()) {
            throw new IllegalArgumentException("tablePrefix must contain only letters, numbers, and underscores");
        }
        return checked;
    }
}
