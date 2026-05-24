package io.github.manu.storage;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.journal.JournaledTradingEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

public final class TradingEventArchiveLayout {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);
    private static final String ROOT = "trading-events";
    private static final String VERSION = "v1";
    private static final String EXTENSION = ".avrobin";

    private final String prefix;

    public TradingEventArchiveLayout(String prefix) {
        this.prefix = normalizePrefix(prefix);
    }

    public String objectName(JournaledTradingEvent journaled, Instant archivedAt) {
        Objects.requireNonNull(journaled, "journaled");
        Objects.requireNonNull(archivedAt, "archivedAt");
        SerializedTradingEvent event = journaled.event();
        String eventType = event.eventType().name().toLowerCase(Locale.ROOT).replace('_', '-');
        return join(
                prefix,
                ROOT,
                VERSION,
                "event_type=" + eventType,
                "topic=" + event.route().topic(),
                "date=" + DATE_FORMATTER.format(archivedAt),
                "hour=" + HOUR_FORMATTER.format(archivedAt),
                String.format(Locale.ROOT, "%020d%s", journaled.index(), EXTENSION)
        );
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("prefix must not contain empty path segments");
        }
        return normalized;
    }

    private static String join(String... segments) {
        return Stream.of(segments)
                .filter(segment -> !segment.isBlank())
                .peek(TradingEventArchiveLayout::validateSegment)
                .reduce((left, right) -> left + "/" + right)
                .orElseThrow();
    }

    private static void validateSegment(String segment) {
        if (segment.contains("\\")) {
            throw new IllegalArgumentException("archive object path segment must not contain backslash");
        }
    }
}
