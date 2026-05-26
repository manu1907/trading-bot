package io.github.manu.projection;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public final class FileTradingStateProjectionStore implements TradingStateProjectionStore {

    private final Path snapshotPath;
    private final ObjectMapper jsonMapper;

    public FileTradingStateProjectionStore(Path snapshotPath, ObjectMapper jsonMapper) {
        this.snapshotPath = Objects.requireNonNull(snapshotPath, "snapshotPath");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public Optional<TradingStateSnapshot> load() {
        if (!Files.isRegularFile(snapshotPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(snapshotPath.toFile(), TradingStateSnapshot.class));
        } catch (RuntimeException ex) {
            throw new ProjectionStoreException("Failed to load trading-state projection snapshot", ex);
        }
    }

    @Override
    public void save(TradingStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path parent = snapshotPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryPath = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), snapshot);
            moveAtomicallyWhenPossible(temporaryPath);
        } catch (IOException | RuntimeException ex) {
            throw new ProjectionStoreException("Failed to save trading-state projection snapshot", ex);
        }
    }

    private void moveAtomicallyWhenPossible(Path temporaryPath) throws IOException {
        try {
            Files.move(
                    temporaryPath,
                    snapshotPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporaryPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
