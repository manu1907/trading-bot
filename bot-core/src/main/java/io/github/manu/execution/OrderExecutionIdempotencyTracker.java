package io.github.manu.execution;

import io.github.manu.events.v1.OrderCommandEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class OrderExecutionIdempotencyTracker {

    private static final String COMMAND_ID_KIND = "command_id";
    private static final String IDEMPOTENCY_KEY_KIND = "idempotency_key";

    private final boolean enabled;
    private final int maxTrackedKeys;
    private final LinkedHashMap<String, String> trackedKeys = new LinkedHashMap<>();

    public OrderExecutionIdempotencyTracker(ExecutionProperties properties) {
        ExecutionProperties.Idempotency idempotency = Objects.requireNonNull(properties, "properties").idempotency();
        this.enabled = idempotency.enabled();
        this.maxTrackedKeys = idempotency.maxTrackedKeys();
    }

    public synchronized Admission admit(OrderCommandEvent command) {
        Objects.requireNonNull(command, "command");
        if (!enabled) {
            return Admission.admitted();
        }
        String commandKey = scopedKey(command, COMMAND_ID_KIND, command.getCommandId());
        String idempotencyKey = scopedKey(command, IDEMPOTENCY_KEY_KIND, command.getIdempotencyKey());
        if (commandKey != null && trackedKeys.containsKey(commandKey)) {
            return Admission.duplicateCommandId(value(command.getCommandId()));
        }
        if (idempotencyKey != null && trackedKeys.containsKey(idempotencyKey)) {
            return Admission.duplicateIdempotencyKey(value(command.getIdempotencyKey()));
        }
        track(commandKey);
        track(idempotencyKey);
        return Admission.admitted();
    }

    private void track(String key) {
        if (key == null) {
            return;
        }
        trackedKeys.put(key, key);
        while (trackedKeys.size() > maxTrackedKeys) {
            String oldestKey = trackedKeys.keySet().iterator().next();
            trackedKeys.remove(oldestKey);
        }
    }

    private String scopedKey(OrderCommandEvent command, String kind, CharSequence identity) {
        String value = value(identity);
        if (value == null) {
            return null;
        }
        return scope(command) + "|" + kind + "|" + value;
    }

    private String scope(OrderCommandEvent command) {
        return lowerValue(command.getProvider())
                + "|" + lowerValue(command.getEnvironment())
                + "|" + lowerValue(command.getAccount())
                + "|" + lowerValue(command.getMarket());
    }

    private String lowerValue(CharSequence value) {
        String text = value(value);
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    public static final class Admission {

        private final Status status;
        private final String reason;
        private final Map<CharSequence, CharSequence> attributes;

        private Admission(Status status, String reason, Map<CharSequence, CharSequence> attributes) {
            this.status = Objects.requireNonNull(status, "status");
            this.reason = reason;
            this.attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }

        public Status status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        public Map<CharSequence, CharSequence> attributes() {
            return Map.copyOf(attributes);
        }

        static Admission admitted() {
            return new Admission(Status.ADMITTED, null, Map.of());
        }

        static Admission duplicateCommandId(String commandId) {
            return new Admission(
                    Status.DUPLICATE_COMMAND_ID,
                    "execution:duplicate_command_id",
                    Map.of("duplicate_command_id", commandId)
            );
        }

        static Admission duplicateIdempotencyKey(String idempotencyKey) {
            return new Admission(
                    Status.DUPLICATE_IDEMPOTENCY_KEY,
                    "execution:duplicate_idempotency_key",
                    Map.of("duplicate_idempotency_key", idempotencyKey)
            );
        }
    }

    public enum Status {
        ADMITTED,
        DUPLICATE_COMMAND_ID,
        DUPLICATE_IDEMPOTENCY_KEY
    }
}
