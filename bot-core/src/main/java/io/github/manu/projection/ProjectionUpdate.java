package io.github.manu.projection;

import io.github.manu.events.TradingEventType;

public record ProjectionUpdate(
        ProjectionUpdateStatus status,
        TradingEventType eventType,
        String entityKey,
        String eventId
) {
    public static ProjectionUpdate applied(TradingEventType eventType, String entityKey, String eventId) {
        return new ProjectionUpdate(ProjectionUpdateStatus.APPLIED, eventType, entityKey, eventId);
    }

    public static ProjectionUpdate duplicate(TradingEventType eventType, String entityKey, String eventId) {
        return new ProjectionUpdate(ProjectionUpdateStatus.DUPLICATE, eventType, entityKey, eventId);
    }

    public static ProjectionUpdate stale(TradingEventType eventType, String entityKey, String eventId) {
        return new ProjectionUpdate(ProjectionUpdateStatus.STALE, eventType, entityKey, eventId);
    }

    public static ProjectionUpdate ignored(TradingEventType eventType, String eventId) {
        return new ProjectionUpdate(ProjectionUpdateStatus.IGNORED, eventType, null, eventId);
    }
}
