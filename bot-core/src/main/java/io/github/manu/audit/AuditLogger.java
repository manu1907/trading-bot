package io.github.manu.audit;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.runtime.RuntimeDescriptor;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

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

    private String attribute(RiskDecisionEvent decision, String key) {
        if (decision.getAttributes() == null) {
            return null;
        }
        return value(decision.getAttributes().get(key));
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
