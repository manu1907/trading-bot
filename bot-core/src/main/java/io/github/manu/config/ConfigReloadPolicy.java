package io.github.manu.config;

import io.github.manu.config.properties.BotProperties;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.springframework.stereotype.Component;

@Component
public class ConfigReloadPolicy {

    public ReloadDecision assess(TradingBotProperties current, TradingBotProperties candidate) {
        if (current == null) {
            return ReloadDecision.mutable();
        }

        if (!sameTarget(current.getExchange(), candidate.getExchange())) {
            return ReloadDecision.restartRequired(
                    "active target changed: provider/environment/account/market are immutable for a running process"
            );
        }

        if (!sameBotIdentity(current.getBot(), candidate.getBot())) {
            return ReloadDecision.restartRequired(
                    "bot identity changed: instance_id/timezone are immutable for a running process"
            );
        }

        if (!current.getProviders().active().equals(candidate.getProviders().active())) {
            return ReloadDecision.restartRequired(
                    "exchange session settings changed: credentials/endpoints/transport settings require restart"
            );
        }

        return ReloadDecision.mutable();
    }

    private boolean sameTarget(ExchangeProperties left, ExchangeProperties right) {
        return left.provider().equals(right.provider())
                && left.environment().equals(right.environment())
                && left.account().equals(right.account())
                && left.market().equals(right.market());
    }

    private boolean sameBotIdentity(BotProperties left, BotProperties right) {
        return left.instanceId().equals(right.instanceId())
                && left.targetId().equals(right.targetId())
                && left.timezone().equals(right.timezone());
    }

    public record ReloadDecision(boolean restartRequired, String reason) {
        public static ReloadDecision mutable() {
            return new ReloadDecision(false, null);
        }

        public static ReloadDecision restartRequired(String reason) {
            return new ReloadDecision(true, reason);
        }
    }
}
