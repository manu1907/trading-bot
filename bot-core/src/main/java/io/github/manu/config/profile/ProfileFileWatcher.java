package io.github.manu.config.profile;

import io.github.manu.config.ExternalConfigDirectory;
import io.github.manu.config.service.ConfigReloadService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// Watches the external config files that can affect the active runtime.
/// When the operator edits and saves them, the bot reloads the resolved
/// configuration and applies only mutable changes. Immutable runtime
/// identity/session changes are rejected and require a restart.
@Component
public class ProfileFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(ProfileFileWatcher.class);

    private final ConfigReloadService configReloadService;
    private final ActiveProfileProvider profileProvider;
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "profile-file-watcher");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;

    public ProfileFileWatcher(ConfigReloadService configReloadService, ActiveProfileProvider profileProvider) {
        this.configReloadService = configReloadService;
        this.profileProvider = profileProvider;
    }

    @PostConstruct
    void start() {
        watcherExecutor.submit(this::watch);
    }

    private void watch() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Path configDir = ExternalConfigDirectory.resolve();
            configDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
            );

            log.info("Watching runtime config files in {}", configDir.toAbsolutePath().normalize());

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path changedFile = (Path) event.context();
                    if (shouldReload(changedFile.toString())) {
                        log.info("Config file '{}' changed. Reloading...", changedFile);
                        try {
                            configReloadService.reloadFromProfileFile();
                        } catch (Exception e) {
                            log.error("Reload failed; old config preserved. Error: {}", e.getMessage());
                        }
                    }
                }
                if (!key.reset()) {
                    log.warn("WatchKey invalid; watcher exiting");
                    break;
                }
            }
        } catch (IOException e) {
            log.error("File watcher failed", e);
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        watcherExecutor.shutdownNow();
    }

    private boolean shouldReload(String fileName) {
        return switch (profileProvider.activeProfile()) {
            case LIVE -> "catalog.json".equals(fileName)
                    || "active.json".equals(fileName);
            case BACKTEST -> "application-backtest.json".equals(fileName);
        };
    }
}
