package io.github.manu.audit;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.runtime.RuntimeDescriptor;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final PauseGovernanceAuditTrail pauseGovernanceAuditTrail;

    public AuditLogger() {
        this(new PauseGovernanceAuditTrail());
    }

    @Autowired
    public AuditLogger(PauseGovernanceAuditTrail pauseGovernanceAuditTrail) {
        this.pauseGovernanceAuditTrail =
                Objects.requireNonNull(pauseGovernanceAuditTrail, "pauseGovernanceAuditTrail");
    }

    public void runtimeBootstrapped(RuntimeDescriptor descriptor) {
        log.info(
                "runtime bootstrapped",
                StructuredArguments.keyValue("event", "runtime_bootstrapped"),
                StructuredArguments.keyValue("target_id", descriptor.targetId()),
                StructuredArguments.keyValue("instance_id", descriptor.instanceId()),
                StructuredArguments.keyValue("provider", descriptor.provider()),
                StructuredArguments.keyValue("environment", descriptor.environment()),
                StructuredArguments.keyValue("account", descriptor.account()),
                StructuredArguments.keyValue("market", descriptor.market()),
                StructuredArguments.keyValue("runtime_profile", descriptor.runtimeProfile()),
                StructuredArguments.keyValue("config_version", descriptor.configVersion()),
                StructuredArguments.keyValue("active_target_source", descriptor.activeTargetSource()),
                StructuredArguments.keyValue("image_version", descriptor.imageVersion())
        );
    }

    public void configurationReloaded(RuntimeDescriptor descriptor) {
        log.info(
                "configuration reloaded",
                StructuredArguments.keyValue("event", "configuration_reloaded"),
                StructuredArguments.keyValue("target_id", descriptor.targetId()),
                StructuredArguments.keyValue("instance_id", descriptor.instanceId()),
                StructuredArguments.keyValue("provider", descriptor.provider()),
                StructuredArguments.keyValue("environment", descriptor.environment()),
                StructuredArguments.keyValue("account", descriptor.account()),
                StructuredArguments.keyValue("market", descriptor.market()),
                StructuredArguments.keyValue("runtime_profile", descriptor.runtimeProfile()),
                StructuredArguments.keyValue("config_version", descriptor.configVersion())
        );
    }

    public void configurationReloadRejected(RuntimeDescriptor descriptor, String reason) {
        log.warn(
                "configuration reload rejected",
                StructuredArguments.keyValue("event", "configuration_reload_rejected"),
                StructuredArguments.keyValue("target_id", descriptor.targetId()),
                StructuredArguments.keyValue("instance_id", descriptor.instanceId()),
                StructuredArguments.keyValue("provider", descriptor.provider()),
                StructuredArguments.keyValue("environment", descriptor.environment()),
                StructuredArguments.keyValue("account", descriptor.account()),
                StructuredArguments.keyValue("market", descriptor.market()),
                StructuredArguments.keyValue("runtime_profile", descriptor.runtimeProfile()),
                StructuredArguments.keyValue("config_version", descriptor.configVersion()),
                StructuredArguments.keyValue("reason", reason)
        );
    }

    public void pauseGovernanceReleased(
            RemediationDecisionEvent event,
            String pauseScope,
            String pauseTarget,
            String sourcePauseRemediationId
    ) {
        pauseGovernanceAuditTrail.record(new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_governance_released",
                value(event.getProvider()),
                value(event.getEnvironment()),
                value(event.getAccount()),
                value(event.getMarket()),
                value(event.getSymbol()),
                pauseScope,
                pauseTarget,
                value(event.getRemediationId()),
                value(event.getEventId()),
                sourcePauseRemediationId,
                null,
                null,
                null,
                null,
                "released",
                value(event.getDecidedBy()),
                value(event.getDecisionReason()),
                attribute(event, "pause_expires_at"),
                null,
                instant(event.getDecidedAtMicros())
        ));
        log.info(
                "pause governance released",
                StructuredArguments.keyValue("event", "pause_governance_released"),
                StructuredArguments.keyValue("provider", value(event.getProvider())),
                StructuredArguments.keyValue("environment", value(event.getEnvironment())),
                StructuredArguments.keyValue("account", value(event.getAccount())),
                StructuredArguments.keyValue("market", value(event.getMarket())),
                StructuredArguments.keyValue("symbol", value(event.getSymbol())),
                StructuredArguments.keyValue("pause_scope", pauseScope),
                StructuredArguments.keyValue("pause_target", pauseTarget),
                StructuredArguments.keyValue("remediation_id", value(event.getRemediationId())),
                StructuredArguments.keyValue("event_id", value(event.getEventId())),
                StructuredArguments.keyValue("source_pause_remediation_id", sourcePauseRemediationId),
                StructuredArguments.keyValue("released_by", value(event.getDecidedBy())),
                StructuredArguments.keyValue("release_reason", value(event.getDecisionReason()))
        );
    }

    public void pauseOverrideEvaluated(OrderCommandEvent command, RiskDecisionEvent decision) {
        pauseGovernanceAuditTrail.record(new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_override_evaluated",
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket()),
                value(command.getSymbol()),
                null,
                null,
                null,
                value(decision.getEventId()),
                null,
                value(command.getCommandId()),
                value(command.getClientOrderId()),
                value(decision.getDecisionId()),
                decision.getDecision() == null ? null : decision.getDecision().name(),
                "true".equals(attribute(decision, "pause_override_allowed")) ? "allowed" : "rejected",
                attribute(decision, "pause_override_by"),
                attribute(decision, "pause_override_reason"),
                attribute(decision, "pause_override_expires_at"),
                attribute(decision, "pause_override_invalid_reason"),
                instant(decision.getDecidedAtMicros())
        ));
        log.info(
                "pause override evaluated",
                StructuredArguments.keyValue("event", "pause_override_evaluated"),
                StructuredArguments.keyValue("provider", value(command.getProvider())),
                StructuredArguments.keyValue("environment", value(command.getEnvironment())),
                StructuredArguments.keyValue("account", value(command.getAccount())),
                StructuredArguments.keyValue("market", value(command.getMarket())),
                StructuredArguments.keyValue("symbol", value(command.getSymbol())),
                StructuredArguments.keyValue("command_id", value(command.getCommandId())),
                StructuredArguments.keyValue("client_order_id", value(command.getClientOrderId())),
                StructuredArguments.keyValue("decision_id", value(decision.getDecisionId())),
                StructuredArguments.keyValue("decision", decision.getDecision() == null ? null : decision.getDecision().name()),
                StructuredArguments.keyValue("pause_override_requested", attribute(decision, "pause_override_requested")),
                StructuredArguments.keyValue("pause_override_allowed", attribute(decision, "pause_override_allowed")),
                StructuredArguments.keyValue("pause_override_by", attribute(decision, "pause_override_by")),
                StructuredArguments.keyValue("pause_override_reason", attribute(decision, "pause_override_reason")),
                StructuredArguments.keyValue("pause_override_expires_at", attribute(decision, "pause_override_expires_at")),
                StructuredArguments.keyValue("pause_override_invalid_reason", attribute(decision, "pause_override_invalid_reason"))
        );
    }

    public void pauseGovernanceExpired(TradingStateProjection.PauseGovernanceState state, Instant observedAt) {
        Objects.requireNonNull(state, "state");
        Instant expiresAt = state.expiresAt();
        pauseGovernanceAuditTrail.record(new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_governance_expired",
                value(state.provider()),
                value(state.environment()),
                value(state.account()),
                value(state.market()),
                value(state.symbol()),
                value(state.pauseScope()),
                value(state.pauseTarget()),
                value(state.remediationId()),
                value(state.eventId()),
                null,
                null,
                null,
                null,
                null,
                "expired",
                "pause_governance_expiry_monitor",
                "pause_expires_at_elapsed",
                expiresAt == null ? null : expiresAt.toString(),
                null,
                observedAt
        ));
        log.info(
                "pause governance expired",
                StructuredArguments.keyValue("event", "pause_governance_expired"),
                StructuredArguments.keyValue("provider", value(state.provider())),
                StructuredArguments.keyValue("environment", value(state.environment())),
                StructuredArguments.keyValue("account", value(state.account())),
                StructuredArguments.keyValue("market", value(state.market())),
                StructuredArguments.keyValue("symbol", value(state.symbol())),
                StructuredArguments.keyValue("pause_scope", value(state.pauseScope())),
                StructuredArguments.keyValue("pause_target", value(state.pauseTarget())),
                StructuredArguments.keyValue("remediation_id", value(state.remediationId())),
                StructuredArguments.keyValue("event_id", value(state.eventId())),
                StructuredArguments.keyValue("pause_expires_at", expiresAt == null ? null : expiresAt.toString()),
                StructuredArguments.keyValue("observed_at", observedAt)
        );
    }

    private String attribute(RiskDecisionEvent decision, String key) {
        if (decision.getAttributes() == null) {
            return null;
        }
        return value(decision.getAttributes().get(key));
    }

    private String attribute(RemediationDecisionEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return value(event.getAttributes().get(key));
    }

    private Instant instant(Object value) {
        return value instanceof Instant instant ? instant : null;
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
