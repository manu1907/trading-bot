package io.github.manu.intervention;

import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.projection.TradingStateProjection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InterventionRemediationExecutorService {

    private static final String REAL_ENVIRONMENT = "real";

    private final TradingStateProjection projection;
    private final InterventionRemediationCommandPlanner commandPlanner;
    private final InterventionProperties.RemediationExecutorPolicy policy;

    public InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties
    ) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.commandPlanner = Objects.requireNonNull(commandPlanner, "commandPlanner");
        this.policy = Objects.requireNonNull(properties, "properties").remediationExecutorPolicy();
    }

    public List<InterventionRemediationCommandPlanner.RemediationCommandPlan> plans(
            String provider,
            String environment,
            String account,
            String market
    ) {
        return decisionStates(provider, environment, account, market)
                .stream()
                .map(this::toDecisionEvent)
                .map(commandPlanner::plan)
                .toList();
    }

    public RemediationExecutionBatch dryRun(
            String provider,
            String environment,
            String account,
            String market
    ) {
        if (!policy.enabled()) {
            return new RemediationExecutionBatch(
                    false,
                    policy.exchangeExecutionEnabled(),
                    policy.dryRunOnly(),
                    0,
                    0,
                    0,
                    0,
                    List.of()
            );
        }

        List<RemediationExecutionReport> reports = plans(provider, environment, account, market)
                .stream()
                .limit(policy.maxPlansPerRun())
                .map(this::evaluate)
                .toList();
        long blockedCount = reports.stream().filter(report -> report.status() == ExecutionStatus.BLOCKED).count();
        long dryRunCount = reports.stream().filter(report -> report.status() == ExecutionStatus.DRY_RUN).count();
        long noActionCount = reports.stream().filter(report -> report.status() == ExecutionStatus.NO_ACTION).count();
        return new RemediationExecutionBatch(
                true,
                policy.exchangeExecutionEnabled(),
                policy.dryRunOnly(),
                reports.size(),
                Math.toIntExact(blockedCount),
                Math.toIntExact(dryRunCount),
                Math.toIntExact(noActionCount),
                reports
        );
    }

    private List<TradingStateProjection.RemediationDecisionState> decisionStates(
            String provider,
            String environment,
            String account,
            String market
    ) {
        return projection.remediationDecisionStates(
                requireText(provider, "provider"),
                requireText(environment, "environment"),
                requireText(account, "account"),
                requireText(market, "market")
        );
    }

    private RemediationExecutionReport evaluate(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan
    ) {
        Map<String, String> attributes = new LinkedHashMap<>(plan.attributes());
        attributes.put("executor_policy_enabled", Boolean.toString(policy.enabled()));
        attributes.put("executor_exchange_execution_enabled", Boolean.toString(policy.exchangeExecutionEnabled()));
        attributes.put("executor_dry_run_only", Boolean.toString(policy.dryRunOnly()));

        if (plan.status() == InterventionRemediationCommandPlanner.PlanStatus.NO_ACTION) {
            return report(plan, ExecutionStatus.NO_ACTION, "executor:no_action_plan", attributes);
        }
        if (policy.rejectStaleProjection()
                && plan.status() == InterventionRemediationCommandPlanner.PlanStatus.STALE_PROJECTION) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:stale_projection", attributes);
        }
        if (policy.rejectUnsupportedPlans()
                && plan.status() == InterventionRemediationCommandPlanner.PlanStatus.NOT_SUPPORTED) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:unsupported_plan", attributes);
        }
        if (policy.rejectInsufficientDataPlans()
                && plan.status() == InterventionRemediationCommandPlanner.PlanStatus.INSUFFICIENT_DATA) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:insufficient_data", attributes);
        }
        if (policy.requireReadyPlan()
                && plan.status() != InterventionRemediationCommandPlanner.PlanStatus.READY) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:ready_plan_required", attributes);
        }
        if (policy.rejectOperatorReviewPlans()
                && plan.operation() == InterventionRemediationCommandPlanner.Operation.OPERATOR_REVIEW) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:operator_review_required", attributes);
        }
        if (policy.rejectUnsupportedPlans()
                && plan.operation() == InterventionRemediationCommandPlanner.Operation.UNSUPPORTED) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:unsupported_operation", attributes);
        }
        if (!policy.allowRealEnvironment() && REAL_ENVIRONMENT.equalsIgnoreCase(text(plan.environment()))) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:real_environment_blocked", attributes);
        }
        if (policy.requireProjectionTargetIdentity() && !hasRequiredTargetIdentity(plan)) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:target_identity_required", attributes);
        }
        if (!plan.exchangeExecutable()) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:plan_not_exchange_executable", attributes);
        }
        if (policy.exchangeExecutionEnabled() && !allowedOperations().contains(toExecutableOperation(plan.operation()))) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:operation_not_allowed", attributes);
        }
        if (!policy.exchangeExecutionEnabled()) {
            return report(plan, ExecutionStatus.DRY_RUN, "executor:exchange_execution_disabled", attributes);
        }
        if (policy.dryRunOnly()) {
            return report(plan, ExecutionStatus.DRY_RUN, "executor:dry_run_only", attributes);
        }
        return report(plan, ExecutionStatus.DRY_RUN, "executor:report_only_no_exchange_submission", attributes);
    }

    private RemediationExecutionReport report(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            ExecutionStatus status,
            String reason,
            Map<String, String> attributes
    ) {
        List<String> reasons = new ArrayList<>(plan.reasons());
        reasons.add(reason);
        attributes.put("executor_status", status.name());
        attributes.put("executor_reason", reason);
        return new RemediationExecutionReport(
                plan.remediationId(),
                plan.scope(),
                plan.action(),
                plan.provider(),
                plan.environment(),
                plan.account(),
                plan.market(),
                plan.symbol(),
                plan.clientOrderId(),
                plan.positionSide(),
                plan.status(),
                plan.operation(),
                status,
                plan.exchangeExecutable(),
                reasons,
                attributes,
                plan
        );
    }

    private boolean hasRequiredTargetIdentity(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan
    ) {
        return switch (plan.operation()) {
            case ADOPT_ORDER, AMEND_ORDER, CANCEL_ORDER -> text(plan.clientOrderId()) != null;
            case ADOPT_POSITION, CLOSE_POSITION, REDUCE_POSITION, HEDGE_POSITION -> text(plan.positionSide()) != null;
            case PAUSE_SYMBOL -> text(plan.symbol()) != null;
            case PAUSE_ACCOUNT, REPLAN_FROM_PROJECTION, IGNORE -> true;
            case OPERATOR_REVIEW, UNSUPPORTED -> false;
        };
    }

    private EnumSet<InterventionProperties.ExecutableOperation> allowedOperations() {
        if (policy.allowedOperations().isEmpty()) {
            return EnumSet.noneOf(InterventionProperties.ExecutableOperation.class);
        }
        return EnumSet.copyOf(policy.allowedOperations());
    }

    private InterventionProperties.ExecutableOperation toExecutableOperation(
            InterventionRemediationCommandPlanner.Operation operation
    ) {
        return switch (operation) {
            case CANCEL_ORDER -> InterventionProperties.ExecutableOperation.CANCEL_ORDER;
            case ADOPT_ORDER -> InterventionProperties.ExecutableOperation.ADOPT_ORDER;
            case AMEND_ORDER -> InterventionProperties.ExecutableOperation.AMEND_ORDER;
            case REPLAN_FROM_PROJECTION -> InterventionProperties.ExecutableOperation.REPLAN_FROM_PROJECTION;
            case CLOSE_POSITION -> InterventionProperties.ExecutableOperation.CLOSE_POSITION;
            case REDUCE_POSITION -> InterventionProperties.ExecutableOperation.REDUCE_POSITION;
            case HEDGE_POSITION -> InterventionProperties.ExecutableOperation.HEDGE_POSITION;
            case ADOPT_POSITION -> InterventionProperties.ExecutableOperation.ADOPT_POSITION;
            case PAUSE_SYMBOL -> InterventionProperties.ExecutableOperation.PAUSE_SYMBOL;
            case PAUSE_ACCOUNT -> InterventionProperties.ExecutableOperation.PAUSE_ACCOUNT;
            case IGNORE -> InterventionProperties.ExecutableOperation.IGNORE;
            case OPERATOR_REVIEW, UNSUPPORTED -> null;
        };
    }

    private RemediationDecisionEvent toDecisionEvent(TradingStateProjection.RemediationDecisionState state) {
        return RemediationDecisionEvent.newBuilder()
                .setEventId(state.eventId())
                .setSchemaVersion(1)
                .setRemediationId(state.remediationId())
                .setProvider(state.provider())
                .setEnvironment(state.environment())
                .setAccount(state.account())
                .setMarket(state.market())
                .setSymbol(state.symbol())
                .setScope(state.scope())
                .setAction(state.action())
                .setClientOrderId(state.clientOrderId())
                .setPositionSide(state.positionSide())
                .setInterventionReason(state.interventionReason())
                .setReasons(List.copyOf(state.reasons()))
                .setDecidedBy(state.decidedBy())
                .setDecisionReason(state.decisionReason())
                .setDecidedAtMicros(state.updatedAt())
                .setAttributes(Map.copyOf(state.attributes()))
                .build();
    }

    private String requireText(String value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public enum ExecutionStatus {
        BLOCKED,
        DRY_RUN,
        NO_ACTION
    }

    public record RemediationExecutionBatch(
            boolean enabled,
            boolean exchangeExecutionEnabled,
            boolean dryRunOnly,
            int evaluatedCount,
            int blockedCount,
            int dryRunCount,
            int noActionCount,
            List<RemediationExecutionReport> reports
    ) {

        public RemediationExecutionBatch {
            reports = reports == null ? List.of() : List.copyOf(reports);
        }
    }

    public record RemediationExecutionReport(
            String remediationId,
            String scope,
            String action,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String positionSide,
            InterventionRemediationCommandPlanner.PlanStatus planStatus,
            InterventionRemediationCommandPlanner.Operation operation,
            ExecutionStatus status,
            boolean exchangeExecutable,
            List<String> reasons,
            Map<String, String> attributes,
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan
    ) {

        public RemediationExecutionReport {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            plan = Objects.requireNonNull(plan, "plan");
        }
    }
}
