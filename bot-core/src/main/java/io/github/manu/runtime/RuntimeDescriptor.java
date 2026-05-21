package io.github.manu.runtime;

public record RuntimeDescriptor(
        String targetId,
        String instanceId,
        String provider,
        String environment,
        String account,
        String market,
        String runtimeProfile,
        Integer configVersion,
        String activeTargetSource,
        String imageVersion
) {
}
