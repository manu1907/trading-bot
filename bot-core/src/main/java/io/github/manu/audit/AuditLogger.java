package io.github.manu.audit;

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
}
