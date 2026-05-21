package io.github.manu.config.profile;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Single source of truth for the active runtime config profile.
/// This is intentionally stricter than generic Spring profile handling:
/// exactly one supported runtime profile must be active among live/backtest.
@Component
public final class ActiveProfileProvider {

    private final RuntimeProfile activeProfile;

    public ActiveProfileProvider(Environment environment) {
        String[] candidates = environment.getActiveProfiles();
        if (candidates.length == 0) {
            candidates = environment.getDefaultProfiles();
        }

        List<RuntimeProfile> supportedProfiles = Arrays.stream(candidates)
                .map(RuntimeProfile::fromNullable)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (supportedProfiles.isEmpty()) {
            throw new IllegalStateException("No supported runtime profile set. Expected one of: live, backtest.");
        }
        if (supportedProfiles.size() > 1) {
            throw new IllegalStateException("Multiple runtime profiles are active: %s".formatted(supportedProfiles));
        }

        this.activeProfile = supportedProfiles.getFirst();
    }

    public RuntimeProfile activeProfile() {
        return activeProfile;
    }
}
