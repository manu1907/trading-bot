package io.github.manu.observability;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class PauseGovernanceMetrics {

    static final String RELEASE_EVENTS = "trading.pause_governance.release.events";
    static final String OVERRIDE_EVENTS = "trading.pause_governance.override.events";
    static final String ACTIVATION_EVENTS = "trading.pause_governance.activation.events";
    static final String EXPIRY_CONFIGURED_EVENTS = "trading.pause_governance.expiry.configured.events";

    private final MeterRegistry meterRegistry;

    public PauseGovernanceMetrics() {
        this(Metrics.globalRegistry);
    }

    @Autowired
    public PauseGovernanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void pauseReleasePublished(RemediationDecisionEvent event, String pauseScope) {
        pauseRelease(event, pauseScope, "published");
    }

    public void pauseReleaseFailed(RemediationDecisionEvent event, String pauseScope) {
        pauseRelease(event, pauseScope, "publish_failed");
    }

    public void pauseActivationDecision(RemediationDecisionEvent event, String pauseScope, boolean expiryConfigured) {
        Counter.builder(ACTIVATION_EVENTS)
                .description("Pause governance activation decisions observed on the live event path")
                .tag("provider", value(event.getProvider()))
                .tag("environment", value(event.getEnvironment()))
                .tag("account", value(event.getAccount()))
                .tag("market", value(event.getMarket()))
                .tag("scope", tagValue(pauseScope, "unknown"))
                .tag("expiry_configured", Boolean.toString(expiryConfigured))
                .register(meterRegistry)
                .increment();
        if (expiryConfigured) {
            Counter.builder(EXPIRY_CONFIGURED_EVENTS)
                    .description("Pause governance activation decisions with a valid configured expiry")
                    .tag("provider", value(event.getProvider()))
                    .tag("environment", value(event.getEnvironment()))
                    .tag("account", value(event.getAccount()))
                    .tag("market", value(event.getMarket()))
                    .tag("scope", tagValue(pauseScope, "unknown"))
                    .register(meterRegistry)
                    .increment();
        }
    }

    public void pauseOverrideEvaluated(OrderCommandEvent command, RiskDecisionEvent decision) {
        Counter.builder(OVERRIDE_EVENTS)
                .description("Explicit pause governance override attempts evaluated by the order risk gate")
                .tag("provider", value(command.getProvider()))
                .tag("environment", value(command.getEnvironment()))
                .tag("account", value(command.getAccount()))
                .tag("market", value(command.getMarket()))
                .tag("symbol", value(command.getSymbol()))
                .tag("decision", decision.getDecision() == null ? "unknown" : decision.getDecision().name())
                .tag("outcome", "true".equals(attribute(decision, "pause_override_allowed")) ? "allowed" : "rejected")
                .tag("invalid_reason", tagValue(attribute(decision, "pause_override_invalid_reason"), "none"))
                .register(meterRegistry)
                .increment();
    }

    private void pauseRelease(RemediationDecisionEvent event, String pauseScope, String outcome) {
        Counter.builder(RELEASE_EVENTS)
                .description("Pause governance release publication outcomes")
                .tag("provider", value(event.getProvider()))
                .tag("environment", value(event.getEnvironment()))
                .tag("account", value(event.getAccount()))
                .tag("market", value(event.getMarket()))
                .tag("scope", tagValue(pauseScope, "unknown"))
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private String attribute(RiskDecisionEvent decision, String key) {
        if (decision.getAttributes() == null) {
            return null;
        }
        return text(decision.getAttributes().get(key));
    }

    private String value(CharSequence value) {
        return tagValue(text(value), "unknown");
    }

    private String text(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
