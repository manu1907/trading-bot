package io.github.manu.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "trading.audit")
public record AuditProperties(
        PauseGovernance pauseGovernance
) {

    public AuditProperties {
        pauseGovernance = pauseGovernance == null ? PauseGovernance.defaults() : pauseGovernance;
    }

    public record PauseGovernance(
            FileStore fileStore,
            JdbcStore jdbcStore
    ) {

        public PauseGovernance {
            fileStore = fileStore == null ? FileStore.disabled() : fileStore;
            jdbcStore = jdbcStore == null ? JdbcStore.disabled() : jdbcStore;
        }

        static PauseGovernance defaults() {
            return new PauseGovernance(null, null);
        }
    }

    public record FileStore(
            Boolean enabled,
            Path path
    ) {

        public FileStore {
            enabled = Boolean.TRUE.equals(enabled);
            path = path == null ? Path.of("data/audit/pause-governance-audit.jsonl") : path;
        }

        static FileStore disabled() {
            return new FileStore(false, Path.of("data/audit/pause-governance-audit.jsonl"));
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
            tablePrefix = blankToDefault(tablePrefix, "trading_audit_pause_governance_");
            initializeSchema = Boolean.TRUE.equals(initializeSchema);
        }

        static JdbcStore disabled() {
            return new JdbcStore(false, null, null, "", "trading_audit_pause_governance_", false);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
