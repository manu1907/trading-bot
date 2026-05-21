package io.github.manu.runtime;

import ch.qos.logback.classic.LoggerContext;
import io.github.manu.config.ActiveTargetResolver;
import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.properties.BotProperties;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeIdentityService {

    private static final String IMAGE_VERSION_ENV = "BOT_IMAGE_VERSION";

    private final ActiveProfileProvider profileProvider;
    private final ActiveTargetResolver activeTargetResolver;
    private final Environment environment;

    public RuntimeIdentityService(ActiveProfileProvider profileProvider,
                                  ActiveTargetResolver activeTargetResolver,
                                  Environment environment) {
        this.profileProvider = profileProvider;
        this.activeTargetResolver = activeTargetResolver;
        this.environment = environment;
    }

    public RuntimeDescriptor apply(TradingBotProperties config) {
        BotProperties bot = config.getBot();
        ExchangeProperties exchange = config.getExchange();
        String runtimeProfile = profileProvider.activeProfile().id();
        String imageVersion = environment.getProperty(IMAGE_VERSION_ENV, "unknown");
        String targetId = bot.targetId();

        RuntimeDescriptor descriptor = new RuntimeDescriptor(
                targetId,
                bot.instanceId(),
                exchange.provider(),
                exchange.environment(),
                exchange.account(),
                exchange.market(),
                runtimeProfile,
                config.getVersion(),
                activeTargetResolver.source(),
                imageVersion
        );

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.putProperty("target_id", descriptor.targetId());
        context.putProperty("instance_id", descriptor.instanceId());
        context.putProperty("provider", descriptor.provider());
        context.putProperty("environment", descriptor.environment());
        context.putProperty("account", descriptor.account());
        context.putProperty("market", descriptor.market());
        context.putProperty("runtime_profile", descriptor.runtimeProfile());
        context.putProperty("config_version", String.valueOf(descriptor.configVersion()));
        context.putProperty("active_target_source", descriptor.activeTargetSource());
        context.putProperty("image_version", descriptor.imageVersion());
        return descriptor;
    }
}
