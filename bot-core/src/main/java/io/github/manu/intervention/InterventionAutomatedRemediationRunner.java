package io.github.manu.intervention;

import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import io.github.manu.config.runtime.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class InterventionAutomatedRemediationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterventionAutomatedRemediationRunner.class);

    private final InterventionAutomatedDecisionService automatedDecisionService;
    private final InterventionRemediationExecutorService remediationExecutorService;
    private final InterventionProperties.AutomatedRemediationRunner properties;
    private final Supplier<ExchangeProperties> activeTargetSupplier;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public InterventionAutomatedRemediationRunner(
            InterventionAutomatedDecisionService automatedDecisionService,
            InterventionRemediationExecutorService remediationExecutorService,
            InterventionProperties properties,
            ConfigManager configManager
    ) {
        this(
                automatedDecisionService,
                remediationExecutorService,
                properties.automatedRemediationRunner(),
                () -> activeTarget(configManager)
        );
    }

    InterventionAutomatedRemediationRunner(
            InterventionAutomatedDecisionService automatedDecisionService,
            InterventionRemediationExecutorService remediationExecutorService,
            InterventionProperties.AutomatedRemediationRunner properties,
            Supplier<ExchangeProperties> activeTargetSupplier
    ) {
        this.automatedDecisionService = Objects.requireNonNull(automatedDecisionService, "automatedDecisionService");
        this.remediationExecutorService = Objects.requireNonNull(remediationExecutorService, "remediationExecutorService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.activeTargetSupplier = Objects.requireNonNull(activeTargetSupplier, "activeTargetSupplier");
    }

    @Scheduled(
            fixedDelayString = "${trading.intervention.automated-remediation-runner.interval-millis:30000}",
            initialDelayString = "${trading.intervention.automated-remediation-runner.initial-delay-millis:30000}"
    )
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException exception) {
            log.warn("Automated remediation runner failed: {}", exception.getMessage(), exception);
        }
    }

    public AutomatedRemediationRunResult runOnce() {
        if (!properties.enabled()) {
            return disabledResult();
        }
        if (!running.compareAndSet(false, true)) {
            return AutomatedRemediationRunResult.skipped("runner:already_running");
        }
        try {
            InterventionProperties.Target target = resolveTarget();
            InterventionRemediationExecutorService.RemediationExecutionBatch executionBatch =
                    properties.executeRemediation()
                            ? remediationExecutorService.execute(
                                    target.provider(),
                                    target.environment(),
                                    target.account(),
                                    target.market()
                            )
                            : disabledExecutionBatch();
            InterventionAutomatedDecisionService.AutomatedDecisionBatch decisionBatch =
                    properties.publishDecisions()
                            ? automatedDecisionService.decide(
                                    target.provider(),
                                    target.environment(),
                                    target.account(),
                                    target.market()
                            ).join()
                            : new InterventionAutomatedDecisionService.AutomatedDecisionBatch(false, List.of());
            return new AutomatedRemediationRunResult(
                    true,
                    "runner:completed",
                    target,
                    executionBatch,
                    decisionBatch
            );
        } finally {
            running.set(false);
        }
    }

    private InterventionProperties.Target resolveTarget() {
        InterventionProperties.Target configured = properties.target();
        ExchangeProperties active = activeTargetSupplier.get();
        return new InterventionProperties.Target(
                first(configured.provider(), active == null ? null : active.provider()),
                first(configured.environment(), active == null ? null : active.environment()),
                first(configured.account(), active == null ? null : active.account()),
                first(configured.market(), active == null ? null : active.market())
        );
    }

    private String first(String configured, String active) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (active != null && !active.isBlank()) {
            return active.trim();
        }
        throw new IllegalArgumentException("automated remediation runner target is incomplete");
    }

    private AutomatedRemediationRunResult disabledResult() {
        return new AutomatedRemediationRunResult(
                false,
                "runner:disabled",
                null,
                disabledExecutionBatch(),
                new InterventionAutomatedDecisionService.AutomatedDecisionBatch(false, List.of())
        );
    }

    private InterventionRemediationExecutorService.RemediationExecutionBatch disabledExecutionBatch() {
        return new InterventionRemediationExecutorService.RemediationExecutionBatch(
                false,
                false,
                true,
                0,
                0,
                0,
                0,
                0,
                List.of()
        );
    }

    private static ExchangeProperties activeTarget(ConfigManager configManager) {
        TradingBotProperties config = Objects.requireNonNull(configManager, "configManager").getConfig();
        return config == null ? null : config.getExchange();
    }

    public record AutomatedRemediationRunResult(
            boolean enabled,
            String reason,
            InterventionProperties.Target target,
            InterventionRemediationExecutorService.RemediationExecutionBatch executionBatch,
            InterventionAutomatedDecisionService.AutomatedDecisionBatch decisionBatch
    ) {

        static AutomatedRemediationRunResult skipped(String reason) {
            return new AutomatedRemediationRunResult(
                    true,
                    reason,
                    null,
                    null,
                    null
            );
        }
    }
}
