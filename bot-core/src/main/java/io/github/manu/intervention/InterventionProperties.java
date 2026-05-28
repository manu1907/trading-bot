package io.github.manu.intervention;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.intervention")
public record InterventionProperties(OperatorApi operatorApi) {

    public InterventionProperties {
        operatorApi = operatorApi == null ? OperatorApi.disabled() : operatorApi;
    }

    public record OperatorApi(
            Boolean enabled,
            String operatorToken
    ) {

        public OperatorApi {
            enabled = Boolean.TRUE.equals(enabled);
            operatorToken = text(operatorToken);
        }

        static OperatorApi disabled() {
            return new OperatorApi(false, null);
        }

        private static String text(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }
}
