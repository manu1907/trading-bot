package io.github.manu.config;

import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ConfigFileLayout {

    static final String CATALOG_FILE = "catalog.json";
    static final String ACTIVE_FILE = "active.json";
    static final String BACKTEST_FILE = "application-backtest.json";

    private final Path configDir;
    private final ObjectMapper jsonMapper;

    public ConfigFileLayout(Path configDir) {
        this.configDir = configDir;
        this.jsonMapper = JsonMapperFactory.create();
    }

    public Path catalogFile() {
        return configDir.resolve(CATALOG_FILE);
    }

    public Path activeFile() {
        return configDir.resolve(ACTIVE_FILE);
    }

    public Path backtestFile() {
        return configDir.resolve(BACKTEST_FILE);
    }

    public Path environmentFile(String environment) {
        return configDir.resolve("application-" + environment + ".json");
    }

    public Path runtimeOverrideFile(RuntimeProfile profile, ExchangeProperties target) {
        return configDir.resolve("runtime")
                .resolve(profile.id())
                .resolve(target.provider())
                .resolve(target.environment())
                .resolve(target.account())
                .resolve(target.market() + ".json");
    }

    public List<Path> filesFor(RuntimeProfile profile, ExchangeProperties target) {
        if (profile == RuntimeProfile.BACKTEST) {
            return List.of(backtestFile());
        }
        return List.of(
                catalogFile(),
                environmentFile(target.environment()),
                activeFile(),
                runtimeOverrideFile(profile, target)
        );
    }

    public void ensureEmptyRuntimeOverrideFile(RuntimeProfile profile, ExchangeProperties target) throws IOException {
        if (profile != RuntimeProfile.LIVE) {
            return;
        }

        Path runtimeFile = runtimeOverrideFile(profile, target);
        if (Files.exists(runtimeFile)) {
            return;
        }

        Path parent = runtimeFile.getParent();
        if (parent == null) {
            throw new IOException("Runtime override file has no parent directory: " + runtimeFile);
        }
        Files.createDirectories(parent);
        Path temporaryFile = runtimeFile.resolveSibling(runtimeFile.getFileName() + ".tmp");
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), jsonMapper.createObjectNode());
        try {
            Files.move(temporaryFile, runtimeFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile, runtimeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
