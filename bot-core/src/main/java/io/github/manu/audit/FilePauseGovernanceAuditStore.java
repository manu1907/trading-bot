package io.github.manu.audit;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FilePauseGovernanceAuditStore implements PauseGovernanceAuditStore {

    private final Path auditPath;
    private final ObjectMapper jsonMapper;

    public FilePauseGovernanceAuditStore(Path auditPath, ObjectMapper jsonMapper) {
        this.auditPath = Objects.requireNonNull(auditPath, "auditPath");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public synchronized void record(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            Path parent = auditPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    auditPath,
                    jsonMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException | RuntimeException exception) {
            throw new PauseGovernanceAuditStoreException("Failed to append pause governance audit event", exception);
        }
    }

    @Override
    public synchronized List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> recent(
            String provider,
            String environment,
            String account,
            String market,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!Files.isRegularFile(auditPath)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(auditPath, StandardCharsets.UTF_8);
            List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> matches = new ArrayList<>();
            for (int index = lines.size() - 1; index >= 0 && matches.size() < limit; index--) {
                String line = lines.get(index);
                if (line == null || line.isBlank()) {
                    continue;
                }
                PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event =
                        jsonMapper.readValue(line, PauseGovernanceAuditTrail.PauseGovernanceAuditEvent.class);
                if (Objects.equals(event.provider(), provider)
                        && Objects.equals(event.environment(), environment)
                        && Objects.equals(event.account(), account)
                        && Objects.equals(event.market(), market)) {
                    matches.add(event);
                }
            }
            return List.copyOf(matches);
        } catch (IOException | RuntimeException exception) {
            throw new PauseGovernanceAuditStoreException("Failed to read pause governance audit events", exception);
        }
    }

    @Override
    public String storeName() {
        return "file";
    }
}
