package io.github.manu.journal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties(prefix = "trading.journal")
public record JournalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("data/journal/trading-events") Path directory
) {

    public JournalProperties {
        Objects.requireNonNull(directory, "directory");
    }
}
