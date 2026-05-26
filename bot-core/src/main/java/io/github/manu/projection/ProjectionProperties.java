package io.github.manu.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "trading.projection")
public record ProjectionProperties(
        SnapshotStore snapshotStore
) {

    public ProjectionProperties {
        snapshotStore = snapshotStore == null ? SnapshotStore.disabled() : snapshotStore;
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
}
