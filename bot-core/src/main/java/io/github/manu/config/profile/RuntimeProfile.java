package io.github.manu.config.profile;

import java.util.Arrays;

public enum RuntimeProfile {
    LIVE("live"),
    BACKTEST("backtest");

    private final String id;

    RuntimeProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static RuntimeProfile fromNullable(String value) {
        return Arrays.stream(values())
                .filter(profile -> profile.id.equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }
}
