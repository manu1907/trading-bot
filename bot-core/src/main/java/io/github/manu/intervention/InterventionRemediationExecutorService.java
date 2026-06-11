package io.github.manu.intervention;

import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandPositionSide;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.execution.OrderExecutionPipeline;
import io.github.manu.observability.RemediationExecutorMetrics;
import io.github.manu.projection.TradingStateProjection;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InterventionRemediationExecutorService {

    private static final String REAL_ENVIRONMENT = "real";
    private static final String COMMAND_SOURCE = "intervention_remediation_executor";
    private static final String STRATEGY_ID = "intervention-remediation-executor";

    private final TradingStateProjection projection;
    private final InterventionRemediationCommandPlanner commandPlanner;
    private final InterventionProperties.RemediationExecutorPolicy policy;
    private final OrderExecutionPipeline orderExecutionPipeline;
    private final RemediationExecutorMetrics metrics;
    private final Clock clock;

    public InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties
    ) {
        this(projection, commandPlanner, properties, null, Clock.systemUTC());
    }

    public InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties,
            OrderExecutionPipeline orderExecutionPipeline
    ) {
        this(projection, commandPlanner, properties, orderExecutionPipeline, Clock.systemUTC());
    }

    public InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties,
            OrderExecutionPipeline orderExecutionPipeline,
            RemediationExecutorMetrics metrics
    ) {
        this(projection, commandPlanner, properties, orderExecutionPipeline, metrics, Clock.systemUTC());
    }

    InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties,
            OrderExecutionPipeline orderExecutionPipeline,
            Clock clock
    ) {
        this(projection, commandPlanner, properties, orderExecutionPipeline, new RemediationExecutorMetrics(), clock);
    }

    InterventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties,
            OrderExecutionPipeline orderExecutionPipeline,
            RemediationExecutorMetrics metrics,
            Clock clock
    ) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.commandPlanner = Objects.requireNonNull(commandPlanner, "commandPlanner");
        this.policy = Objects.requireNonNull(properties, "properties").remediationExecutorPolicy();
        this.orderExecutionPipeline = orderExecutionPipeline;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
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

    public RemediationExecutionBatch preview(
            String provider,
            String environment,
            String account,
            String market
    ) {
        if (!policy.enabled()) {
            metrics.executorDisabled(provider, environment, account, market, ExecutionMode.PREVIEW.name());
            return new RemediationExecutionBatch(
                    false,
                    policy.exchangeExecutionEnabled(),
                    policy.reportOnly(),
                    0,
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
                .map(plan -> evaluate(plan, ExecutionMode.PREVIEW))
                .toList();
        reports.forEach(report -> metrics.executorOutcome(report, ExecutionMode.PREVIEW.name()));
        long blockedCount = reports.stream().filter(report -> report.status() == ExecutionStatus.BLOCKED).count();
        long previewOnlyCount = reports.stream().filter(report -> report.status() == ExecutionStatus.PREVIEW_ONLY).count();
        long submittedCount = reports.stream()
                .filter(report -> report.status() == ExecutionStatus.SUBMITTED_TO_PIPELINE)
                .count();
        long noActionCount = reports.stream().filter(report -> report.status() == ExecutionStatus.NO_ACTION).count();
        return new RemediationExecutionBatch(
                true,
                policy.exchangeExecutionEnabled(),
                policy.reportOnly(),
                reports.size(),
                Math.toIntExact(blockedCount),
                Math.toIntExact(previewOnlyCount),
                Math.toIntExact(submittedCount),
                Math.toIntExact(noActionCount),
                reports
        );
    }

    public RemediationExecutionBatch execute(
            String provider,
            String environment,
            String account,
            String market
    ) {
        if (!policy.enabled()) {
            metrics.executorDisabled(provider, environment, account, market, ExecutionMode.EXECUTE.name());
            return new RemediationExecutionBatch(
                    false,
                    policy.exchangeExecutionEnabled(),
                    policy.reportOnly(),
                    0,
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
                .map(plan -> evaluate(plan, ExecutionMode.EXECUTE))
                .toList();
        reports.forEach(report -> metrics.executorOutcome(report, ExecutionMode.EXECUTE.name()));
        long blockedCount = reports.stream().filter(report -> report.status() == ExecutionStatus.BLOCKED).count();
        long previewOnlyCount = reports.stream().filter(report -> report.status() == ExecutionStatus.PREVIEW_ONLY).count();
        long submittedCount = reports.stream()
                .filter(report -> report.status() == ExecutionStatus.SUBMITTED_TO_PIPELINE)
                .count();
        long noActionCount = reports.stream().filter(report -> report.status() == ExecutionStatus.NO_ACTION).count();
        return new RemediationExecutionBatch(
                true,
                policy.exchangeExecutionEnabled(),
                policy.reportOnly(),
                reports.size(),
                Math.toIntExact(blockedCount),
                Math.toIntExact(previewOnlyCount),
                Math.toIntExact(submittedCount),
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
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            ExecutionMode mode
    ) {
        Map<String, String> attributes = new LinkedHashMap<>(plan.attributes());
        attributes.put("executor_policy_enabled", Boolean.toString(policy.enabled()));
        attributes.put("executor_exchange_execution_enabled", Boolean.toString(policy.exchangeExecutionEnabled()));
        attributes.put("executor_report_only", Boolean.toString(policy.reportOnly()));
        attributes.put("executor_mode", mode.name());

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
            return report(plan, ExecutionStatus.PREVIEW_ONLY, "executor:exchange_execution_disabled", attributes);
        }
        if (policy.reportOnly()) {
            return report(plan, ExecutionStatus.PREVIEW_ONLY, "executor:report_only_policy", attributes);
        }
        if (mode == ExecutionMode.PREVIEW) {
            return report(plan, ExecutionStatus.PREVIEW_ONLY, "executor:preview_request", attributes);
        }
        if (orderExecutionPipeline == null) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:order_execution_pipeline_missing", attributes);
        }
        if (plan.operation() != InterventionRemediationCommandPlanner.Operation.CANCEL_ORDER
                && plan.operation() != InterventionRemediationCommandPlanner.Operation.AMEND_ORDER
                && plan.operation() != InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION
                && plan.operation() != InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION
                && plan.operation() != InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION) {
            return report(plan, ExecutionStatus.BLOCKED, "executor:operation_execution_not_implemented", attributes);
        }
        OrderCommandEvent command = switch (plan.operation()) {
            case CANCEL_ORDER -> cancelOrderCommand(plan, attributes);
            case AMEND_ORDER -> amendOrderCommand(plan, attributes);
            default -> positionRemediationOrderCommand(plan, attributes);
        };
        try {
            orderExecutionPipeline.handleOrderCommand(command).join();
        } catch (RuntimeException exception) {
            attributes.put("executor_submission_failure", exception.getClass().getSimpleName());
            if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
                attributes.put("executor_submission_failure_message", exception.getMessage());
            }
            return report(plan, ExecutionStatus.BLOCKED, "executor:pipeline_submission_failed", attributes);
        }
        attributes.put("executor_submitted_command_id", command.getCommandId().toString());
        attributes.put("executor_submitted_idempotency_key", command.getIdempotencyKey().toString());
        put(attributes, "executor_submitted_client_order_id", command.getClientOrderId());
        put(attributes, "executor_submitted_target_client_order_id", command.getTargetClientOrderId());
        put(attributes, "executor_submitted_target_exchange_order_id", command.getTargetExchangeOrderId());
        put(attributes, "executor_submitted_side", command.getSide() == null ? null : command.getSide().name());
        put(attributes, "executor_submitted_order_type", command.getOrderType() == null ? null : command.getOrderType().name());
        put(attributes, "executor_submitted_position_side",
                command.getPositionSide() == null ? null : command.getPositionSide().name());
        put(attributes, "executor_submitted_quantity", command.getQuantity());
        put(attributes, "executor_submitted_price", command.getPrice());
        attributes.put("executor_submitted_reduce_only", Boolean.toString(Boolean.TRUE.equals(command.getReduceOnly())));
        attributes.put("executor_submitted_close_position",
                Boolean.toString(Boolean.TRUE.equals(command.getClosePosition())));
        return report(plan, ExecutionStatus.SUBMITTED_TO_PIPELINE, "executor:submitted_to_order_execution_pipeline", attributes);
    }

    private OrderCommandEvent cancelOrderCommand(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            Map<String, String> reportAttributes
    ) {
        String remediationId = requireText(plan.remediationId(), "remediationId");
        String commandId = "remediation-command:" + remediationId + ":cancel-order";
        String commandClientOrderId = "rm-cxl-" + safeId(remediationId);
        String targetExchangeOrderId = text(plan.attributes().get("target_exchange_order_id"));
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.putAll(plan.attributes());
        attributes.put("command_source", COMMAND_SOURCE);
        attributes.put("remediation_operation", plan.operation().name());
        attributes.put("remediation_executor_policy_enabled", Boolean.toString(policy.enabled()));
        attributes.put("target_client_order_id", requireText(plan.clientOrderId(), "clientOrderId"));
        if (targetExchangeOrderId != null) {
            attributes.put("target_exchange_order_id", targetExchangeOrderId);
        }
        reportAttributes.put("executor_command_id", commandId);
        reportAttributes.put("executor_command_client_order_id", commandClientOrderId);
        return OrderCommandEvent.newBuilder()
                .setEventId(commandId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setStrategyId(STRATEGY_ID)
                .setProvider(requireText(plan.provider(), "provider"))
                .setEnvironment(requireText(plan.environment(), "environment"))
                .setAccount(requireText(plan.account(), "account"))
                .setMarket(requireText(plan.market(), "market"))
                .setSymbol(requireText(plan.symbol(), "symbol"))
                .setAction(OrderCommandAction.CANCEL)
                .setTargetClientOrderId(requireText(plan.clientOrderId(), "clientOrderId"))
                .setTargetExchangeOrderId(targetExchangeOrderId)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setPositionSide(null)
                .setTimeInForce(null)
                .setQuantity(null)
                .setQuoteOrderQuantity(null)
                .setPrice(null)
                .setStopPrice(null)
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId(commandClientOrderId)
                .setIdempotencyKey(commandId)
                .setRequestedAtMicros(Instant.now(clock))
                .setAttributes(Map.copyOf(attributes))
                .build();
    }

    private OrderCommandEvent amendOrderCommand(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            Map<String, String> reportAttributes
    ) {
        String remediationId = requireText(plan.remediationId(), "remediationId");
        String commandId = "remediation-command:" + remediationId + ":amend-order";
        String commandClientOrderId = "rm-amd-" + safeId(remediationId);
        String targetExchangeOrderId = text(plan.attributes().get("target_exchange_order_id"));
        String side = requireText(plan.attributes().get("amendment_command_side"), "amendmentCommandSide");
        String orderType = requireText(plan.attributes().get("amendment_command_order_type"), "amendmentCommandOrderType");
        String quantity = requireText(plan.attributes().get("amendment_command_quantity"), "amendmentCommandQuantity");
        String price = requireText(plan.attributes().get("amendment_command_price"), "amendmentCommandPrice");
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.putAll(plan.attributes());
        attributes.put("command_source", COMMAND_SOURCE);
        attributes.put("remediation_operation", plan.operation().name());
        attributes.put("remediation_executor_policy_enabled", Boolean.toString(policy.enabled()));
        attributes.put("target_client_order_id", requireText(plan.clientOrderId(), "clientOrderId"));
        if (targetExchangeOrderId != null) {
            attributes.put("target_exchange_order_id", targetExchangeOrderId);
        }
        reportAttributes.put("executor_command_id", commandId);
        reportAttributes.put("executor_command_client_order_id", commandClientOrderId);
        return OrderCommandEvent.newBuilder()
                .setEventId(commandId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setStrategyId(STRATEGY_ID)
                .setProvider(requireText(plan.provider(), "provider"))
                .setEnvironment(requireText(plan.environment(), "environment"))
                .setAccount(requireText(plan.account(), "account"))
                .setMarket(requireText(plan.market(), "market"))
                .setSymbol(requireText(plan.symbol(), "symbol"))
                .setAction(OrderCommandAction.MODIFY)
                .setTargetClientOrderId(requireText(plan.clientOrderId(), "clientOrderId"))
                .setTargetExchangeOrderId(targetExchangeOrderId)
                .setSide(OrderCommandSide.valueOf(side))
                .setOrderType(OrderCommandType.valueOf(orderType))
                .setPositionSide(null)
                .setTimeInForce(null)
                .setQuantity(quantity)
                .setQuoteOrderQuantity(null)
                .setPrice(price)
                .setStopPrice(null)
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId(commandClientOrderId)
                .setIdempotencyKey(commandId)
                .setRequestedAtMicros(Instant.now(clock))
                .setAttributes(Map.copyOf(attributes))
                .build();
    }

    private OrderCommandEvent positionRemediationOrderCommand(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            Map<String, String> reportAttributes
    ) {
        String remediationId = requireText(plan.remediationId(), "remediationId");
        String operationId = operationId(plan.operation());
        String commandId = "remediation-command:" + remediationId + ":" + operationId;
        String commandClientOrderId = "rm-pos-" + safeId(remediationId);
        String side = requireText(plan.attributes().get("remediation_order_side"), "remediationOrderSide");
        String quantity = requireText(plan.attributes().get("target_position_quantity"), "targetPositionQuantity");
        String positionSide = text(plan.attributes().get("position_order_command_position_side"));
        if (positionSide == null) {
            positionSide = text(plan.positionSide());
        }
        String orderType = requireText(plan.attributes().get("position_order_type"), "positionOrderType");
        boolean reduceOnly = requiredBooleanPlanAttribute(plan, "position_order_reduce_only");
        boolean closePosition = requiredBooleanPlanAttribute(plan, "position_order_close_position");
        String executionMode = requireText(plan.attributes().get("position_execution_mode"), "positionExecutionMode");
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.putAll(plan.attributes());
        attributes.put("command_source", COMMAND_SOURCE);
        attributes.put("remediation_operation", plan.operation().name());
        attributes.put("remediation_executor_policy_enabled", Boolean.toString(policy.enabled()));
        attributes.put("position_order_type", orderType);
        attributes.put("position_order_reduce_only", Boolean.toString(reduceOnly));
        attributes.put("position_order_close_position", Boolean.toString(closePosition));
        attributes.put("position_execution_mode", executionMode);
        reportAttributes.put("executor_command_id", commandId);
        reportAttributes.put("executor_command_client_order_id", commandClientOrderId);
        return OrderCommandEvent.newBuilder()
                .setEventId(commandId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setStrategyId(STRATEGY_ID)
                .setProvider(requireText(plan.provider(), "provider"))
                .setEnvironment(requireText(plan.environment(), "environment"))
                .setAccount(requireText(plan.account(), "account"))
                .setMarket(requireText(plan.market(), "market"))
                .setSymbol(requireText(plan.symbol(), "symbol"))
                .setAction(OrderCommandAction.NEW)
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(null)
                .setSide(OrderCommandSide.valueOf(side))
                .setOrderType(OrderCommandType.valueOf(orderType))
                .setPositionSide(positionSide == null ? null : OrderCommandPositionSide.valueOf(positionSide))
                .setTimeInForce(null)
                .setQuantity(quantity)
                .setQuoteOrderQuantity(null)
                .setPrice(null)
                .setStopPrice(null)
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(reduceOnly)
                .setClosePosition(closePosition)
                .setClientOrderId(commandClientOrderId)
                .setIdempotencyKey(commandId)
                .setRequestedAtMicros(Instant.now(clock))
                .setAttributes(Map.copyOf(attributes))
                .build();
    }

    private boolean requiredBooleanPlanAttribute(
            InterventionRemediationCommandPlanner.RemediationCommandPlan plan,
            String name
    ) {
        String value = requireText(plan.attributes().get(name), name);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
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
            case PAUSE_ACCOUNT, RELEASE_PAUSE, REPLAN_FROM_PROJECTION, IGNORE -> true;
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
            case RELEASE_PAUSE, OPERATOR_REVIEW, UNSUPPORTED -> null;
        };
    }

    private String operationId(InterventionRemediationCommandPlanner.Operation operation) {
        return operation.name().toLowerCase().replace('_', '-');
    }

    private void put(Map<String, String> attributes, String key, CharSequence value) {
        String text = text(value);
        if (text != null) {
            attributes.put(key, text);
        }
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

    private String text(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private String safeId(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.length() <= 28 ? safe : safe.substring(0, 28);
    }

    private enum ExecutionMode {
        PREVIEW,
        EXECUTE
    }

    public enum ExecutionStatus {
        BLOCKED,
        PREVIEW_ONLY,
        SUBMITTED_TO_PIPELINE,
        NO_ACTION
    }

    public record RemediationExecutionBatch(
            boolean enabled,
            boolean exchangeExecutionEnabled,
            boolean reportOnly,
            int evaluatedCount,
            int blockedCount,
            int previewOnlyCount,
            int submittedCount,
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
