package io.github.manu.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record OrderExecutionPreflightRejection(
        String reason,
        Map<CharSequence, CharSequence> attributes
) {

    public OrderExecutionPreflightRejection {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public static OrderExecutionPreflightRejection rejected(
            String reason,
            Map<CharSequence, CharSequence> attributes
    ) {
        return new OrderExecutionPreflightRejection(
                Objects.requireNonNull(reason, "reason"),
                attributes
        );
    }
}
