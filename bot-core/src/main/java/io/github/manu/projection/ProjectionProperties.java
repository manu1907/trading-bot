package io.github.manu.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "trading.projection")
public record ProjectionProperties(
        SnapshotStore snapshotStore,
        JdbcStore jdbcStore
) {

    public ProjectionProperties {
        snapshotStore = snapshotStore == null ? SnapshotStore.disabled() : snapshotStore;
        jdbcStore = jdbcStore == null ? JdbcStore.disabled() : jdbcStore;
    }

    public record SnapshotStore(
            Boolean enabled,
            Path path
    ) {

        public SnapshotStore {
            enabled = Boolean.TRUE.equals(enabled);
            path = path == null ? Path.of("data/projection/trading-state-snapshot.json") : path;
        }

        static SnapshotStore disabled() {
            return new SnapshotStore(false, Path.of("data/projection/trading-state-snapshot.json"));
        }
    }

    public record JdbcStore(
            Boolean enabled,
            String url,
            String username,
            String password,
            String tablePrefix,
            Boolean initializeSchema
    ) {

        public JdbcStore {
            enabled = Boolean.TRUE.equals(enabled);
            url = blankToNull(url);
            username = blankToNull(username);
            password = password == null ? "" : password;
            tablePrefix = blankToDefault(tablePrefix, "trading_projection_");
            initializeSchema = Boolean.TRUE.equals(initializeSchema);
        }

        static JdbcStore disabled() {
            return new JdbcStore(false, null, null, "", "trading_projection_", false);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
