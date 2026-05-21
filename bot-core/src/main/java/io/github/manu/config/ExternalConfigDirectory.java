package io.github.manu.config;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ExternalConfigDirectory {

    private static final String CONFIG_DIR_PROPERTY = "bot.config.dir";
    private static final String CONFIG_DIR_ENV = "BOT_CONFIG_DIR";

    private ExternalConfigDirectory() {
    }

    public static Path resolve() {
        String configured = System.getProperty(CONFIG_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CONFIG_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }

        Path local = Path.of("config");
        if (Files.exists(local.resolve("catalog.json")) || Files.exists(local.resolve("application-backtest.json"))) {
            return local;
        }

        Path parent = Path.of("..", "config");
        if (Files.exists(parent.resolve("catalog.json")) || Files.exists(parent.resolve("application-backtest.json"))) {
            return parent;
        }

        return local;
    }
}
