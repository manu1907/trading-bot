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
            FileStore fileStore
    ) {

        public PauseGovernance {
            fileStore = fileStore == null ? FileStore.disabled() : fileStore;
        }

        static PauseGovernance defaults() {
            return new PauseGovernance(null);
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
}
