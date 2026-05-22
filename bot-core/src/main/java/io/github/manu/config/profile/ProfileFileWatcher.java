package io.github.manu.config.profile;

import io.github.manu.config.ActiveTargetResolver;
import io.github.manu.config.ExternalConfigDirectory;
import io.github.manu.config.ConfigFileLayout;
import io.github.manu.config.service.ConfigReloadService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// Polls content fingerprints for the external config files that affect the
/// active runtime. When those files settle after an edit, the bot reloads the
/// resolved configuration and applies only mutable changes.
@Component
public class ProfileFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(ProfileFileWatcher.class);
    private static final long POLL_INTERVAL_SECONDS = 1L;

    private final ConfigReloadService configReloadService;
    private final ActiveProfileProvider profileProvider;
    private final ActiveTargetResolver activeTargetResolver;
    private final ConfigFileLayout fileLayout;
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "profile-file-watcher");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;

    public ProfileFileWatcher(ConfigReloadService configReloadService,
                              ActiveProfileProvider profileProvider,
                              ActiveTargetResolver activeTargetResolver) {
        this.configReloadService = configReloadService;
        this.profileProvider = profileProvider;
        this.activeTargetResolver = activeTargetResolver;
        this.fileLayout = new ConfigFileLayout(ExternalConfigDirectory.resolve());
    }

    @PostConstruct
    void start() {
        watcherExecutor.submit(this::watch);
    }

    private void watch() {
        log.info("Watching runtime config files in {}", ExternalConfigDirectory.resolve().toAbsolutePath().normalize());

        String appliedFingerprint = null;
        String pendingFingerprint = null;
        while (running) {
            try {
                String currentFingerprint = fingerprintActiveFiles();
                if (appliedFingerprint == null) {
                    appliedFingerprint = currentFingerprint;
                } else if (!currentFingerprint.equals(appliedFingerprint)) {
                    if (currentFingerprint.equals(pendingFingerprint)) {
                        log.info("Runtime config content changed. Reloading...");
                        reload();
                        appliedFingerprint = currentFingerprint;
                        pendingFingerprint = null;
                    } else {
                        pendingFingerprint = currentFingerprint;
                    }
                } else {
                    pendingFingerprint = null;
                }
                TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("File watcher failed to inspect active config files: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        watcherExecutor.shutdownNow();
    }

    private String fingerprintActiveFiles() throws IOException, NoSuchAlgorithmException {
        RuntimeProfile profile = profileProvider.activeProfile();
        List<Path> files = profile == RuntimeProfile.LIVE
                ? fileLayout.filesFor(profile, activeTargetResolver.resolveSelection().target())
                : List.of(fileLayout.backtestFile());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            digest.update(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            if (Files.exists(file)) {
                digest.update(Files.readAllBytes(file));
            } else {
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void reload() {
        try {
            configReloadService.reloadFromProfileFile();
        } catch (Exception e) {
            log.error("Reload failed; old config preserved. Error: {}", e.getMessage());
        }
    }
}
