package io.github.manu.intervention;

import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class InterventionRemediationCommandPlanner {

    private static final String SCOPE_ORDER = "ORDER";
    private static final String SCOPE_POSITION = "POSITION";
    private static final String SCOPE_PAUSE_GOVERNANCE = "PAUSE_GOVERNANCE";
    private static final String ACTION_OPERATOR_REVIEW = "OPERATOR_REVIEW";
    private static final String ACTION_REPLAN_FROM_PROJECTION = "REPLAN_FROM_PROJECTION";
    private static final String ACTION_HEDGE_OR_REPLAN = "HEDGE_OR_REPLAN";
    private static final String ACTION_ADOPT = "ADOPT";
    private static final String ACTION_AMEND = "AMEND";
    private static final String ACTION_REDUCE = "REDUCE";
    private static final String ACTION_CLOSE = "CLOSE";
    private static final String ACTION_HEDGE = "HEDGE";
    private static final String ACTION_PAUSE_SYMBOL = "PAUSE_SYMBOL";
    private static final String ACTION_PAUSE_ACCOUNT = "PAUSE_ACCOUNT";
    private static final String ACTION_RELEASE_SYMBOL_PAUSE = "RELEASE_SYMBOL_PAUSE";
    private static final String ACTION_RELEASE_ACCOUNT_PAUSE = "RELEASE_ACCOUNT_PAUSE";
    private static final String ACTION_IGNORE = "IGNORE";
    private static final String ATTR_HEDGE_FRACTION = "hedge_fraction";
    private static final String ATTR_HEDGE_QUANTITY = "hedge_quantity";
    private static final String ATTR_REDUCE_FRACTION = "reduce_fraction";
    private static final String ATTR_REDUCE_QUANTITY = "reduce_quantity";

    private final TradingStateProjection projection;
    private final InterventionProperties.PositionOrderPolicy positionOrderPolicy;
    private final InterventionProperties.ManagedOrderAmendmentPolicy managedOrderAmendmentPolicy;
    private final InterventionProperties.AdoptedOrderLifecyclePolicy adoptedOrderLifecyclePolicy;

    public InterventionRemediationCommandPlanner(TradingStateProjection projection) {
        this(
                projection,
                InterventionProperties.PositionOrderPolicy.disabled(),
                InterventionProperties.ManagedOrderAmendmentPolicy.disabled(),
                InterventionProperties.AdoptedOrderLifecyclePolicy.disabled()
        );
    }

    public InterventionRemediationCommandPlanner(
            TradingStateProjection projection,
            InterventionProperties.PositionOrderPolicy positionOrderPolicy
    ) {
        this(
                projection,
                positionOrderPolicy,
                InterventionProperties.ManagedOrderAmendmentPolicy.disabled(),
                InterventionProperties.AdoptedOrderLifecyclePolicy.disabled()
        );
    }

    public InterventionRemediationCommandPlanner(
            TradingStateProjection projection,
            InterventionProperties.PositionOrderPolicy positionOrderPolicy,
            InterventionProperties.ManagedOrderAmendmentPolicy managedOrderAmendmentPolicy
    ) {
        this(
                projection,
                positionOrderPolicy,
                managedOrderAmendmentPolicy,
                InterventionProperties.AdoptedOrderLifecyclePolicy.disabled()
        );
    }

    public InterventionRemediationCommandPlanner(
            TradingStateProjection projection,
            InterventionProperties.PositionOrderPolicy positionOrderPolicy,
            InterventionProperties.ManagedOrderAmendmentPolicy managedOrderAmendmentPolicy,
            InterventionProperties.AdoptedOrderLifecyclePolicy adoptedOrderLifecyclePolicy
    ) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.positionOrderPolicy = Objects.requireNonNull(positionOrderPolicy, "positionOrderPolicy");
        this.managedOrderAmendmentPolicy = Objects.requireNonNull(
                managedOrderAmendmentPolicy,
                "managedOrderAmendmentPolicy"
        );
        this.adoptedOrderLifecyclePolicy = Objects.requireNonNull(
                adoptedOrderLifecyclePolicy,
                "adoptedOrderLifecyclePolicy"
        );
    }

    public RemediationCommandPlan plan(RemediationDecisionEvent event) {
        Objects.requireNonNull(event, "event");
        String action = requireText(event.getAction(), "action");
        String scope = requireText(event.getScope(), "scope");
        return switch (scope) {
            case SCOPE_ORDER -> orderPlan(event, action);
            case SCOPE_POSITION -> positionPlan(event, action);
            case SCOPE_PAUSE_GOVERNANCE -> pauseGovernancePlan(event, action);
            default -> unsupported(event, Operation.UNSUPPORTED, "remediation:unsupported_scope");
        };
    }

    private RemediationCommandPlan pauseGovernancePlan(RemediationDecisionEvent event, String action) {
        Map<String, String> attributes = baseAttributes(event);
        put(attributes, "pause_scope", attribute(event, "pause_scope"));
        put(attributes, "pause_target", attribute(event, "pause_target"));
        put(attributes, "source_pause_remediation_id", attribute(event, "source_pause_remediation_id"));
        return switch (action) {
            case ACTION_RELEASE_SYMBOL_PAUSE, ACTION_RELEASE_ACCOUNT_PAUSE -> noAction(
                    event,
                    Operation.RELEASE_PAUSE,
                    "remediation:pause_release_recorded",
                    attributes
            );
            default -> unsupported(event, Operation.UNSUPPORTED, "remediation:unsupported_pause_governance_action");
        };
    }

    private RemediationCommandPlan orderPlan(RemediationDecisionEvent event, String action) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        String clientOrderId = requireText(event.getClientOrderId(), "clientOrderId");
        TradingStateProjection.OrderState order = projection.order(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        clientOrderId
                )
                .orElse(null);
        if (order == null) {
            return stale(event, Operation.UNSUPPORTED, "projection:order_missing");
        }
        if (!order.externalIntervention()) {
            return stale(event, Operation.UNSUPPORTED, "projection:order_intervention_resolved");
        }
        String interventionReason = text(event.getInterventionReason());
        if (interventionReason != null && !interventionReason.equals(text(order.interventionReason()))) {
            return stale(event, Operation.UNSUPPORTED, "projection:order_intervention_reason_mismatch");
        }

        Map<String, String> attributes = baseAttributes(event);
        putOrderAttributes(attributes, order);
        return switch (action) {
            case ACTION_ADOPT -> ready(event, Operation.ADOPT_ORDER, false, "remediation:adopt_order", attributes);
            case ACTION_AMEND -> amendOrderPlan(event, order, attributes);
            case ACTION_CLOSE -> cancelOrderPlan(event, order, attributes);
            case ACTION_REPLAN_FROM_PROJECTION -> ready(
                    event,
                    Operation.REPLAN_FROM_PROJECTION,
                    false,
                    "remediation:replan_from_projection",
                    attributes
            );
            case ACTION_PAUSE_SYMBOL -> ready(event, Operation.PAUSE_SYMBOL, false, "remediation:pause_symbol", attributes);
            case ACTION_PAUSE_ACCOUNT -> ready(event, Operation.PAUSE_ACCOUNT, false, "remediation:pause_account", attributes);
            case ACTION_IGNORE -> ready(event, Operation.IGNORE, false, "remediation:ignore", attributes);
            case ACTION_OPERATOR_REVIEW -> unsupported(event, Operation.OPERATOR_REVIEW, "remediation:operator_review_required");
            default -> unsupported(event, Operation.UNSUPPORTED, "remediation:unsupported_order_action");
        };
    }

    private RemediationCommandPlan cancelOrderPlan(
            RemediationDecisionEvent event,
            TradingStateProjection.OrderState order,
            Map<String, String> attributes
    ) {
        if (!adoptedOrder(order)) {
            return ready(
                    event,
                    Operation.CANCEL_ORDER,
                    true,
                    "remediation:cancel_external_order",
                    exchangeExecutionAttributes(attributes, "order_execution_pipeline")
            );
        }
        putAdoptedOrderLifecyclePolicyAttributes(attributes);
        String blocker = adoptedOrderLifecycleBlocker(event, order, "CANCEL");
        if (blocker != null) {
            attributes.put("adopted_order_lifecycle_result", "blocked");
            attributes.put("exchange_execution_blocker", blocker);
            putAdoptedOrderLifecycleAmbiguousOutcomeAttributes(attributes, blocker);
            return ready(event, Operation.CANCEL_ORDER, false, "remediation:cancel_adopted_order", attributes);
        }
        attributes.put("adopted_order_lifecycle_result", "eligible");
        attributes.put("adopted_order_lifecycle_operation", "CANCEL");
        attributes.put("adopted_order_lifecycle_allow_cancel", "true");
        put(attributes, "adopted_order_ownership", amendmentOrderOwnership(order));
        exchangeExecutionAttributes(attributes, "order_execution_pipeline");
        return ready(event, Operation.CANCEL_ORDER, true, "remediation:cancel_adopted_order", attributes);
    }

    private RemediationCommandPlan amendOrderPlan(
            RemediationDecisionEvent event,
            TradingStateProjection.OrderState order,
            Map<String, String> attributes
    ) {
        putManagedOrderAmendmentPolicyAttributes(attributes);
        putAdoptedOrderLifecyclePolicyAttributes(attributes);
        AmendmentRequest request = amendmentRequest(event);
        put(attributes, "amendment_requested_price", request.priceText());
        put(attributes, "amendment_requested_quantity", request.quantityText());
        put(attributes, "amendment_requested_order_type", request.orderType());
        put(attributes, "amendment_projected_side", order.side());
        put(attributes, "amendment_projected_order_type", order.orderType());
        String blocker = managedOrderAmendmentBlocker(event, order, request);
        if (blocker != null) {
            attributes.put("amendment_policy_result", "blocked");
            attributes.put("exchange_execution_blocker", blocker);
            putAdoptedOrderLifecycleAmbiguousOutcomeAttributes(attributes, blocker);
            return ready(event, Operation.AMEND_ORDER, false, "remediation:amend_order", attributes);
        }
        if (!request.valid()) {
            return insufficientData(event, Operation.AMEND_ORDER, request.invalidReason(), attributes);
        }
        if (!request.hasChange()) {
            return insufficientData(event, Operation.AMEND_ORDER, "remediation:amendment_target_missing", attributes);
        }
        AmendmentDrift drift = amendmentDrift(order, request);
        put(attributes, "amendment_order_ownership", amendmentOrderOwnership(order));
        if (adoptedOrder(order)) {
            attributes.put("adopted_order_lifecycle_result", "eligible");
            attributes.put("adopted_order_lifecycle_operation", "AMEND");
        }
        put(attributes, "amendment_current_price", order.price());
        put(attributes, "amendment_current_quantity", order.originalQuantity());
        put(attributes, "amendment_price_drift_fraction", normalize(drift.priceDrift()));
        put(attributes, "amendment_quantity_drift_fraction", normalize(drift.quantityDrift()));
        if (!drift.valid()) {
            return insufficientData(event, Operation.AMEND_ORDER, drift.invalidReason(), attributes);
        }
        if (drift.noop()) {
            return noAction(event, Operation.AMEND_ORDER, "remediation:amendment_already_matches_projection", attributes);
        }
        blocker = amendmentDriftBlocker(drift);
        if (blocker != null) {
            attributes.put("amendment_policy_result", "blocked");
            attributes.put("exchange_execution_blocker", blocker);
            return ready(event, Operation.AMEND_ORDER, false, "remediation:amend_order", attributes);
        }
        attributes.put("amendment_policy_result", "eligible");
        attributes.put("amendment_execution_mode", "managed_order_modify");
        put(attributes, "amendment_command_side", order.side());
        put(attributes, "amendment_command_order_type", order.orderType());
        put(attributes, "amendment_command_price", request.priceText() == null ? order.price() : request.priceText());
        put(
                attributes,
                "amendment_command_quantity",
                request.quantityText() == null ? order.originalQuantity() : request.quantityText()
        );
        exchangeExecutionAttributes(attributes, "order_execution_pipeline");
        return ready(event, Operation.AMEND_ORDER, true, "remediation:amend_order", attributes);
    }

    private RemediationCommandPlan positionPlan(RemediationDecisionEvent event, String action) {
        String provider = requireText(event.getProvider(), "provider");
        String environment = requireText(event.getEnvironment(), "environment");
        String account = requireText(event.getAccount(), "account");
        String market = requireText(event.getMarket(), "market");
        String symbol = requireText(event.getSymbol(), "symbol");
        String positionSide = requireText(event.getPositionSide(), "positionSide");
        TradingStateProjection.PositionState position = projection.position(
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        positionSide
                )
                .orElse(null);
        if (position == null) {
            return stale(event, Operation.UNSUPPORTED, "projection:position_missing");
        }
        if (!position.externalIntervention()) {
            return stale(event, Operation.UNSUPPORTED, "projection:position_intervention_resolved");
        }
        String interventionReason = text(event.getInterventionReason());
        if (interventionReason != null && !interventionReason.equals(text(position.interventionReason()))) {
            return stale(event, Operation.UNSUPPORTED, "projection:position_intervention_reason_mismatch");
        }

        Map<String, String> attributes = baseAttributes(event);
        putPositionAttributes(attributes, position);
        return switch (action) {
            case ACTION_ADOPT -> ready(event, Operation.ADOPT_POSITION, false, "remediation:adopt_position", attributes);
            case ACTION_CLOSE -> positionSizePlan(event, position, Operation.CLOSE_POSITION, "remediation:close_position", attributes);
            case ACTION_REDUCE -> positionSizePlan(event, position, Operation.REDUCE_POSITION, "remediation:reduce_position", attributes);
            case ACTION_HEDGE -> positionSizePlan(event, position, Operation.HEDGE_POSITION, "remediation:hedge_position", attributes);
            case ACTION_HEDGE_OR_REPLAN -> hedgeOrReplanPlan(event, position, attributes);
            case ACTION_REPLAN_FROM_PROJECTION -> ready(
                    event,
                    Operation.REPLAN_FROM_PROJECTION,
                    false,
                    "remediation:replan_from_projection",
                    attributes
            );
            case ACTION_PAUSE_SYMBOL -> ready(event, Operation.PAUSE_SYMBOL, false, "remediation:pause_symbol", attributes);
            case ACTION_PAUSE_ACCOUNT -> ready(event, Operation.PAUSE_ACCOUNT, false, "remediation:pause_account", attributes);
            case ACTION_IGNORE -> ready(event, Operation.IGNORE, false, "remediation:ignore", attributes);
            case ACTION_OPERATOR_REVIEW -> unsupported(event, Operation.OPERATOR_REVIEW, "remediation:operator_review_required");
            default -> unsupported(event, Operation.UNSUPPORTED, "remediation:unsupported_position_action");
        };
    }

    private RemediationCommandPlan hedgeOrReplanPlan(
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            Map<String, String> attributes
    ) {
        attributes.put("alternative_operation", Operation.REPLAN_FROM_PROJECTION.name());
        return positionSizePlan(event, position, Operation.HEDGE_POSITION, "remediation:hedge_or_replan", attributes);
    }

    private RemediationCommandPlan positionSizePlan(
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            Operation operation,
            String reason,
            Map<String, String> attributes
    ) {
        BigDecimal amount = decimal(position.positionAmount());
        if (amount == null) {
            return insufficientData(event, operation, "projection:position_amount_invalid", attributes);
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return noAction(event, operation, "projection:position_already_flat", attributes);
        }
        PositionRemediationSize size = positionRemediationSize(event, amount, operation);
        if (!size.valid()) {
            return insufficientData(event, operation, size.invalidReason(), attributes);
        }
        size = chunkedCloseSize(operation, size);
        attributes.put("position_abs_amount", normalize(amount.abs()));
        attributes.put("position_direction", amount.signum() > 0 ? "LONG" : "SHORT");
        attributes.put("remediation_order_side", amount.signum() > 0 ? "SELL" : "BUY");
        put(attributes, "position_order_command_position_side", commandPositionSide(operation, amount));
        attributes.put("target_position_quantity", normalize(size.quantity()));
        attributes.put("position_sizing_policy", size.policy());
        attributes.put("position_close_chunked", Boolean.toString(size.closeChunked()));
        if (size.closeChunked()) {
            attributes.put("position_close_remaining_quantity_estimate", normalize(amount.abs().subtract(size.quantity())));
        }
        attributes.put("reduce_only_required", Boolean.toString(size.reduceOnlyRequired()));
        attributes.put("hedge_mode_required", Boolean.toString(size.hedgeModeRequired()));
        putPositionOrderRiskPolicyAttributes(attributes, event, position, operation, size);
        PositionOrderExecutionPolicy executionPolicy = positionOrderExecutionPolicy(event, position, operation, size);
        attributes.put("position_order_type", "MARKET");
        attributes.put("position_order_reduce_only", Boolean.toString(executionPolicy.reduceOnly()));
        attributes.put("position_order_close_position", "false");
        String executionBlocker = executionPolicy.blocker();
        boolean exchangeExecutable = executionBlocker == null;
        if (exchangeExecutable) {
            attributes.put("position_execution_mode", executionPolicy.executionMode());
            exchangeExecutionAttributes(attributes, "order_execution_pipeline");
        } else {
            attributes.put("exchange_execution_blocker", executionBlocker);
        }
        return ready(event, operation, exchangeExecutable, reason, attributes);
    }

    private PositionRemediationSize chunkedCloseSize(Operation operation, PositionRemediationSize size) {
        if (operation != Operation.CLOSE_POSITION || !positionOrderPolicy.chunkCloseWhenMaxQuantityExceeded()) {
            return size;
        }
        BigDecimal maxQuantity = decimal(positionOrderPolicy.maxPositionQuantity());
        if (maxQuantity == null || size.quantity().compareTo(maxQuantity) <= 0) {
            return size;
        }
        return PositionRemediationSize.valid(
                maxQuantity,
                "bounded_projection_chunked_close",
                size.reduceOnlyRequired(),
                size.hedgeModeRequired(),
                size.exchangeExecutionBlocker(),
                true
        );
    }

    private void putPositionOrderRiskPolicyAttributes(
            Map<String, String> attributes,
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            Operation operation,
            PositionRemediationSize size
    ) {
        if (!positionOrderPolicy.allowedSymbols().isEmpty()) {
            attributes.put("position_order_allowed_symbols", String.join(",", positionOrderPolicy.allowedSymbols()));
        }
        put(attributes, "position_order_max_quantity", positionOrderPolicy.maxPositionQuantity());
        put(attributes, "position_order_max_notional", positionOrderPolicy.maxPositionNotional());
        put(attributes, "position_order_required_margin_type", positionOrderPolicy.requiredMarginType());
        put(attributes, "position_order_required_position_mode", positionOrderPolicy.requiredPositionMode());
        put(attributes, "position_order_min_leverage", positionOrderPolicy.minLeverage());
        put(attributes, "position_order_max_leverage", positionOrderPolicy.maxLeverage());
        put(attributes, "position_order_max_account_position_notional", positionOrderPolicy.maxAccountPositionNotional());
        put(attributes, "position_order_max_symbol_position_notional", positionOrderPolicy.maxSymbolPositionNotional());
        put(attributes, "position_order_max_account_unrealized_loss", positionOrderPolicy.maxAccountUnrealizedLoss());
        put(attributes, "position_order_max_symbol_unrealized_loss", positionOrderPolicy.maxSymbolUnrealizedLoss());
        put(attributes, "position_order_min_account_margin_balance", positionOrderPolicy.minAccountMarginBalance());
        put(
                attributes,
                "position_order_max_account_margin_drawdown_fraction",
                positionOrderPolicy.maxAccountMarginDrawdownFraction()
        );
        put(attributes, "position_order_max_account_margin_utilization", positionOrderPolicy.maxAccountMarginUtilization());
        put(attributes, "position_order_max_account_daily_realized_loss", positionOrderPolicy.maxAccountDailyRealizedLoss());
        put(attributes, "position_order_max_symbol_daily_realized_loss", positionOrderPolicy.maxSymbolDailyRealizedLoss());
        PositionExposure exposure = projectedPositionExposure(position, operation, size);
        if (exposure.valid()) {
            attributes.put("current_account_position_notional", normalize(exposure.currentAccountNotional()));
            attributes.put("current_symbol_position_notional", normalize(exposure.currentSymbolNotional()));
            attributes.put("projected_account_position_notional", normalize(exposure.projectedAccountNotional()));
            attributes.put("projected_symbol_position_notional", normalize(exposure.projectedSymbolNotional()));
        }
        PositionLoss loss = currentUnrealizedLoss(position);
        if (loss.valid()) {
            attributes.put("current_account_unrealized_loss", normalize(loss.accountLoss()));
            attributes.put("current_symbol_unrealized_loss", normalize(loss.symbolLoss()));
        }
        DailyRealizedLoss dailyRealizedLoss = currentDailyRealizedLoss(position, event);
        if (dailyRealizedLoss.valid()) {
            put(attributes, "current_account_daily_realized_loss", normalize(dailyRealizedLoss.accountLoss()));
            put(attributes, "current_account_daily_realized_pnl", normalize(dailyRealizedLoss.accountPnl()));
            put(attributes, "current_symbol_daily_realized_loss", normalize(dailyRealizedLoss.symbolLoss()));
            put(attributes, "current_symbol_daily_realized_pnl", normalize(dailyRealizedLoss.symbolPnl()));
            attributes.put("current_account_daily_realized_pnl_trading_day", dailyRealizedLoss.tradingDay());
        }
        projection.risk(
                        position.provider(),
                        position.environment(),
                        position.account(),
                        position.market(),
                        "ACCOUNT",
                        position.account()
                )
                .ifPresent(risk -> putAccountRiskAttributes(attributes, risk));
        attributes.put(
                "position_order_reject_unbounded_notional",
                Boolean.toString(positionOrderPolicy.rejectUnboundedPositionNotional())
        );
        attributes.put(
                "position_order_reject_missing_account_risk_metadata",
                Boolean.toString(positionOrderPolicy.rejectMissingAccountRiskMetadata())
        );
        BigDecimal maxNotional = decimal(positionOrderPolicy.maxPositionNotional());
        BigDecimal markPrice = decimal(position.markPrice());
        if (maxNotional != null && markPrice != null && markPrice.compareTo(BigDecimal.ZERO) > 0) {
            attributes.put("position_order_estimated_notional", normalize(size.quantity().multiply(markPrice.abs())));
        }
    }

    private PositionOrderExecutionPolicy positionOrderExecutionPolicy(
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            Operation operation,
            PositionRemediationSize size
    ) {
        if (operation == Operation.HEDGE_POSITION) {
            return hedgePositionOrderExecutionPolicy(event, position, size);
        }
        if (operation != Operation.CLOSE_POSITION && operation != Operation.REDUCE_POSITION) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_execution_policy_missing");
        }
        if (!size.reduceOnlyRequired()) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_reduce_only_required");
        }
        if (!positionOrderPolicy.oneWayReduceOnlyEnabled() && !positionOrderPolicy.hedgeModeExecutionEnabled()) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_execution_policy_disabled");
        }
        String provider = text(event.getProvider());
        if (!positionOrderPolicy.provider().equalsIgnoreCase(provider)) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_provider_policy_missing");
        }
        String market = text(event.getMarket());
        if (!positionOrderPolicy.market().equalsIgnoreCase(market)) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_market_policy_missing");
        }
        if (!"MARKET".equalsIgnoreCase(positionOrderPolicy.orderType())) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_type_policy_unsupported");
        }
        if (!positionOrderPolicy.requireClosePositionFalse()) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_close_position_policy_required");
        }
        String riskPolicyBlocker = positionOrderRiskPolicyBlocker(event, position, operation, size);
        if (riskPolicyBlocker != null) {
            return PositionOrderExecutionPolicy.blocked(false, riskPolicyBlocker);
        }
        String positionSide = text(position.positionSide());
        if (positionOrderPolicy.positionSide().equalsIgnoreCase(positionSide)) {
            if (!positionOrderPolicy.oneWayReduceOnlyEnabled()) {
                return PositionOrderExecutionPolicy.blocked(false, "position_order_execution_policy_disabled");
            }
            if (!positionOrderPolicy.requireReduceOnly()) {
                return PositionOrderExecutionPolicy.blocked(false, "position_order_reduce_only_policy_required");
            }
            return PositionOrderExecutionPolicy.executable(true, "one_way_reduce_only");
        }
        if (!"LONG".equals(positionSide) && !"SHORT".equals(positionSide)) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_position_side_policy_missing");
        }
        if (!positionOrderPolicy.hedgeModeExecutionEnabled()) {
            return PositionOrderExecutionPolicy.blocked(false, "hedge_mode_reduce_only_position_side_unsupported");
        }
        String positionModeBlocker = hedgePositionModePolicyBlocker(position);
        if (positionModeBlocker != null) {
            return PositionOrderExecutionPolicy.blocked(false, positionModeBlocker);
        }
        return PositionOrderExecutionPolicy.executable(false, "hedge_mode_position_side_close_reduce");
    }

    private PositionOrderExecutionPolicy hedgePositionOrderExecutionPolicy(
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            PositionRemediationSize size
    ) {
        if (!size.hedgeModeRequired()) {
            return PositionOrderExecutionPolicy.blocked(false, "hedge_mode_required");
        }
        if (!positionOrderPolicy.hedgePositionOrderEnabled()) {
            return PositionOrderExecutionPolicy.blocked(false, "hedge_position_order_policy_disabled");
        }
        if (!positionOrderPolicy.hedgeModeExecutionEnabled()) {
            return PositionOrderExecutionPolicy.blocked(false, "hedge_mode_execution_policy_disabled");
        }
        String provider = text(event.getProvider());
        if (!positionOrderPolicy.provider().equalsIgnoreCase(provider)) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_provider_policy_missing");
        }
        String market = text(event.getMarket());
        if (!positionOrderPolicy.market().equalsIgnoreCase(market)) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_market_policy_missing");
        }
        if (!"MARKET".equalsIgnoreCase(positionOrderPolicy.orderType())) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_type_policy_unsupported");
        }
        if (!positionOrderPolicy.requireClosePositionFalse()) {
            return PositionOrderExecutionPolicy.blocked(false, "position_order_close_position_policy_required");
        }
        String riskPolicyBlocker = positionOrderRiskPolicyBlocker(event, position, Operation.HEDGE_POSITION, size);
        if (riskPolicyBlocker != null) {
            return PositionOrderExecutionPolicy.blocked(false, riskPolicyBlocker);
        }
        String positionModeBlocker = hedgePositionModePolicyBlocker(position);
        if (positionModeBlocker != null) {
            return PositionOrderExecutionPolicy.blocked(false, positionModeBlocker);
        }
        return PositionOrderExecutionPolicy.executable(false, "hedge_mode_position_side_hedge");
    }

    private String positionOrderRiskPolicyBlocker(
            RemediationDecisionEvent event,
            TradingStateProjection.PositionState position,
            Operation operation,
            PositionRemediationSize size
    ) {
        String symbol = text(event.getSymbol());
        if (!positionOrderPolicy.allowedSymbols().isEmpty()
                && (symbol == null || !positionOrderPolicy.allowedSymbols().contains(symbol.toUpperCase(Locale.ROOT)))) {
            return "position_order_symbol_policy_missing";
        }
        String accountRiskPolicyBlocker = positionOrderAccountRiskPolicyBlocker(position);
        if (accountRiskPolicyBlocker != null) {
            return accountRiskPolicyBlocker;
        }
        String exposurePolicyBlocker = positionExposurePolicyBlocker(position, operation, size);
        if (exposurePolicyBlocker != null) {
            return exposurePolicyBlocker;
        }
        String lossPolicyBlocker = positionUnrealizedLossPolicyBlocker(position, operation);
        if (lossPolicyBlocker != null) {
            return lossPolicyBlocker;
        }
        String dailyRealizedLossPolicyBlocker = dailyRealizedLossPolicyBlocker(position, operation, event);
        if (dailyRealizedLossPolicyBlocker != null) {
            return dailyRealizedLossPolicyBlocker;
        }
        String marginBalancePolicyBlocker = accountMarginBalancePolicyBlocker(position, operation);
        if (marginBalancePolicyBlocker != null) {
            return marginBalancePolicyBlocker;
        }
        String marginDrawdownPolicyBlocker = accountMarginDrawdownPolicyBlocker(position, operation);
        if (marginDrawdownPolicyBlocker != null) {
            return marginDrawdownPolicyBlocker;
        }
        BigDecimal maxQuantity = decimal(positionOrderPolicy.maxPositionQuantity());
        if (maxQuantity != null
                && size.quantity().compareTo(maxQuantity) > 0
                && (!positionOrderPolicy.chunkCloseWhenMaxQuantityExceeded()
                        || size.policy() == null
                        || !size.policy().equals("bounded_projection_full_close"))) {
            return "position_order_max_quantity_exceeded";
        }
        BigDecimal maxNotional = decimal(positionOrderPolicy.maxPositionNotional());
        if (maxNotional == null) {
            return null;
        }
        BigDecimal markPrice = decimal(position.markPrice());
        if (markPrice == null || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return positionOrderPolicy.rejectUnboundedPositionNotional()
                    ? "position_order_notional_unbounded"
                    : null;
        }
        BigDecimal estimatedNotional = size.quantity().multiply(markPrice.abs());
        if (estimatedNotional.compareTo(maxNotional) > 0) {
            return "position_order_max_notional_exceeded";
        }
        return null;
    }

    private String positionOrderAccountRiskPolicyBlocker(TradingStateProjection.PositionState position) {
        String requiredMarginType = text(positionOrderPolicy.requiredMarginType());
        if (requiredMarginType != null) {
            String marginType = text(position.marginType());
            if (marginType == null) {
                if (positionOrderPolicy.rejectMissingAccountRiskMetadata()) {
                    return "position_order_margin_type_missing";
                }
            } else if (!requiredMarginType.equalsIgnoreCase(marginType)) {
                return "position_order_margin_type_policy_missing";
            }
        }
        Integer minLeverage = integer(positionOrderPolicy.minLeverage());
        Integer maxLeverage = integer(positionOrderPolicy.maxLeverage());
        String marginUtilizationBlocker = accountMarginUtilizationBlocker(position);
        if (marginUtilizationBlocker != null) {
            return marginUtilizationBlocker;
        }
        if (minLeverage == null && maxLeverage == null) {
            return null;
        }
        Integer leverage = integer(position.leverage());
        if (leverage == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_leverage_missing"
                    : null;
        }
        if (minLeverage != null && leverage < minLeverage) {
            return "position_order_min_leverage_violated";
        }
        if (maxLeverage != null && leverage > maxLeverage) {
            return "position_order_max_leverage_violated";
        }
        return null;
    }

    private String positionExposurePolicyBlocker(
            TradingStateProjection.PositionState position,
            Operation operation,
            PositionRemediationSize size
    ) {
        BigDecimal maxAccountNotional = decimal(positionOrderPolicy.maxAccountPositionNotional());
        BigDecimal maxSymbolNotional = decimal(positionOrderPolicy.maxSymbolPositionNotional());
        if (maxAccountNotional == null && maxSymbolNotional == null) {
            return null;
        }
        PositionExposure exposure = projectedPositionExposure(position, operation, size);
        if (!exposure.valid()) {
            return positionOrderPolicy.rejectUnboundedPositionNotional()
                    ? "position_order_position_exposure_unbounded"
                    : null;
        }
        if (maxAccountNotional != null
                && exposure.projectedAccountNotional().compareTo(maxAccountNotional) > 0
                && exposure.projectedAccountNotional().compareTo(exposure.currentAccountNotional()) >= 0) {
            return "position_order_account_position_notional_exceeded";
        }
        if (maxSymbolNotional != null
                && exposure.projectedSymbolNotional().compareTo(maxSymbolNotional) > 0
                && exposure.projectedSymbolNotional().compareTo(exposure.currentSymbolNotional()) >= 0) {
            return "position_order_symbol_position_notional_exceeded";
        }
        return null;
    }

    private PositionExposure projectedPositionExposure(
            TradingStateProjection.PositionState target,
            Operation operation,
            PositionRemediationSize size
    ) {
        BigDecimal currentAccountNotional = BigDecimal.ZERO;
        BigDecimal currentSymbolNotional = BigDecimal.ZERO;
        BigDecimal projectedAccountNotional = BigDecimal.ZERO;
        BigDecimal projectedSymbolNotional = BigDecimal.ZERO;
        String targetSymbol = text(target.symbol());
        String targetPositionSide = text(target.positionSide());
        for (TradingStateProjection.PositionState position : projection.openPositionStates(
                target.provider(),
                target.environment(),
                target.account(),
                target.market()
        )) {
            BigDecimal amount = decimal(position.positionAmount());
            BigDecimal markPrice = decimal(position.markPrice());
            if (amount == null || markPrice == null || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return PositionExposure.invalid();
            }
            BigDecimal currentNotional = amount.abs().multiply(markPrice.abs());
            currentAccountNotional = currentAccountNotional.add(currentNotional);
            if (sameSymbol(position.symbol(), targetSymbol)) {
                currentSymbolNotional = currentSymbolNotional.add(currentNotional);
            }

            BigDecimal projectedNotional = currentNotional;
            if (sameSymbol(position.symbol(), targetSymbol)
                    && samePositionSide(position.positionSide(), targetPositionSide)
                    && operation != Operation.HEDGE_POSITION) {
                BigDecimal projectedAmount = amount.abs().subtract(size.quantity());
                if (projectedAmount.compareTo(BigDecimal.ZERO) < 0) {
                    projectedAmount = BigDecimal.ZERO;
                }
                projectedNotional = projectedAmount.multiply(markPrice.abs());
            }
            projectedAccountNotional = projectedAccountNotional.add(projectedNotional);
            if (sameSymbol(position.symbol(), targetSymbol)) {
                projectedSymbolNotional = projectedSymbolNotional.add(projectedNotional);
            }
        }

        if (operation == Operation.HEDGE_POSITION) {
            BigDecimal targetMarkPrice = decimal(target.markPrice());
            if (targetMarkPrice == null || targetMarkPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return PositionExposure.invalid();
            }
            BigDecimal hedgeNotional = size.quantity().multiply(targetMarkPrice.abs());
            projectedAccountNotional = projectedAccountNotional.add(hedgeNotional);
            projectedSymbolNotional = projectedSymbolNotional.add(hedgeNotional);
        }
        return PositionExposure.valid(
                currentAccountNotional,
                currentSymbolNotional,
                projectedAccountNotional,
                projectedSymbolNotional
        );
    }

    private String positionUnrealizedLossPolicyBlocker(
            TradingStateProjection.PositionState position,
            Operation operation
    ) {
        BigDecimal maxAccountLoss = decimal(positionOrderPolicy.maxAccountUnrealizedLoss());
        BigDecimal maxSymbolLoss = decimal(positionOrderPolicy.maxSymbolUnrealizedLoss());
        if (maxAccountLoss == null && maxSymbolLoss == null) {
            return null;
        }
        PositionLoss loss = currentUnrealizedLoss(position);
        if (!loss.valid()) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_unrealized_pnl_missing"
                    : null;
        }
        if (operation != Operation.HEDGE_POSITION) {
            return null;
        }
        if (maxAccountLoss != null && loss.accountLoss().compareTo(maxAccountLoss) > 0) {
            return "position_order_account_unrealized_loss_exceeded";
        }
        if (maxSymbolLoss != null && loss.symbolLoss().compareTo(maxSymbolLoss) > 0) {
            return "position_order_symbol_unrealized_loss_exceeded";
        }
        return null;
    }

    private PositionLoss currentUnrealizedLoss(TradingStateProjection.PositionState target) {
        BigDecimal accountLoss = BigDecimal.ZERO;
        BigDecimal symbolLoss = BigDecimal.ZERO;
        String targetSymbol = text(target.symbol());
        for (TradingStateProjection.PositionState position : projection.openPositionStates(
                target.provider(),
                target.environment(),
                target.account(),
                target.market()
        )) {
            BigDecimal unrealizedPnl = decimal(position.unrealizedPnl());
            if (unrealizedPnl == null) {
                return PositionLoss.invalid();
            }
            BigDecimal loss = unrealizedPnl.signum() < 0 ? unrealizedPnl.abs() : BigDecimal.ZERO;
            accountLoss = accountLoss.add(loss);
            if (sameSymbol(position.symbol(), targetSymbol)) {
                symbolLoss = symbolLoss.add(loss);
            }
        }
        return PositionLoss.valid(accountLoss, symbolLoss);
    }

    private String dailyRealizedLossPolicyBlocker(
            TradingStateProjection.PositionState position,
            Operation operation,
            RemediationDecisionEvent event
    ) {
        BigDecimal maxAccountLoss = decimal(positionOrderPolicy.maxAccountDailyRealizedLoss());
        BigDecimal maxSymbolLoss = decimal(positionOrderPolicy.maxSymbolDailyRealizedLoss());
        if (maxAccountLoss == null && maxSymbolLoss == null) {
            return null;
        }
        if (operation != Operation.HEDGE_POSITION) {
            return null;
        }
        DailyRealizedLoss loss = currentDailyRealizedLoss(position, event);
        if (!loss.valid()) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_daily_realized_pnl_missing"
                    : null;
        }
        if (maxAccountLoss != null && loss.accountPnl() == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_daily_realized_pnl_missing"
                    : null;
        }
        if (maxSymbolLoss != null && loss.symbolPnl() == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_daily_realized_pnl_missing"
                    : null;
        }
        if (maxAccountLoss != null && loss.accountLoss().compareTo(maxAccountLoss) > 0) {
            return "position_order_account_daily_realized_loss_exceeded";
        }
        if (maxSymbolLoss != null && loss.symbolLoss().compareTo(maxSymbolLoss) > 0) {
            return "position_order_symbol_daily_realized_loss_exceeded";
        }
        return null;
    }

    private DailyRealizedLoss currentDailyRealizedLoss(
            TradingStateProjection.PositionState position,
            RemediationDecisionEvent event
    ) {
        Instant decidedAt = event.getDecidedAtMicros();
        if (decidedAt == null) {
            return DailyRealizedLoss.invalid();
        }
        String tradingDay = tradingDay(decidedAt);
        TradingStateProjection.DailyRealizedPnlState accountRealizedPnl = projection.dailyRealizedPnl(
                        position.provider(),
                        position.environment(),
                        position.account(),
                        position.market(),
                        tradingDay
                )
                .orElse(null);
        TradingStateProjection.DailyRealizedPnlState symbolRealizedPnl = projection.dailyRealizedPnl(
                        position.provider(),
                        position.environment(),
                        position.account(),
                        position.market(),
                        position.symbol(),
                        tradingDay
                )
                .orElse(null);
        BigDecimal accountPnl = accountRealizedPnl == null ? null : decimal(accountRealizedPnl.realizedPnl());
        BigDecimal symbolPnl = symbolRealizedPnl == null ? null : decimal(symbolRealizedPnl.realizedPnl());
        if (accountPnl == null && symbolPnl == null) {
            return DailyRealizedLoss.invalid();
        }
        BigDecimal accountLoss = accountPnl == null || accountPnl.signum() >= 0 ? BigDecimal.ZERO : accountPnl.abs();
        BigDecimal symbolLoss = symbolPnl == null || symbolPnl.signum() >= 0 ? BigDecimal.ZERO : symbolPnl.abs();
        return DailyRealizedLoss.valid(accountPnl, accountLoss, symbolPnl, symbolLoss, tradingDay);
    }

    private String accountMarginUtilizationBlocker(TradingStateProjection.PositionState position) {
        BigDecimal maxUtilization = decimal(positionOrderPolicy.maxAccountMarginUtilization());
        if (maxUtilization == null) {
            return null;
        }
        TradingStateProjection.RiskState accountRisk = projection.risk(
                        position.provider(),
                        position.environment(),
                        position.account(),
                        position.market(),
                        "ACCOUNT",
                        position.account()
                )
                .orElse(null);
        if (accountRisk == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_account_risk_missing"
                    : null;
        }
        BigDecimal marginBalance = decimal(accountRisk.marginBalance());
        if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_margin_balance_missing"
                    : null;
        }
        BigDecimal maintenanceMargin = decimal(accountRisk.maintenanceMargin());
        if (maintenanceMargin == null || maintenanceMargin.compareTo(BigDecimal.ZERO) < 0) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_maintenance_margin_missing"
                    : null;
        }
        BigDecimal utilization = maintenanceMargin.divide(marginBalance, MathContext.DECIMAL64);
        if (utilization.compareTo(maxUtilization) > 0) {
            return "position_order_account_margin_utilization_exceeded";
        }
        return null;
    }

    private String accountMarginBalancePolicyBlocker(
            TradingStateProjection.PositionState position,
            Operation operation
    ) {
        BigDecimal minMarginBalance = decimal(positionOrderPolicy.minAccountMarginBalance());
        if (minMarginBalance == null) {
            return null;
        }
        TradingStateProjection.RiskState accountRisk = accountRisk(position);
        if (accountRisk == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_account_risk_missing"
                    : null;
        }
        BigDecimal marginBalance = decimal(accountRisk.marginBalance());
        if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_margin_balance_missing"
                    : null;
        }
        if (operation != Operation.HEDGE_POSITION) {
            return null;
        }
        if (marginBalance.compareTo(minMarginBalance) < 0) {
            return "position_order_min_account_margin_balance_violated";
        }
        return null;
    }

    private String accountMarginDrawdownPolicyBlocker(
            TradingStateProjection.PositionState position,
            Operation operation
    ) {
        BigDecimal maxDrawdown = decimal(positionOrderPolicy.maxAccountMarginDrawdownFraction());
        if (maxDrawdown == null) {
            return null;
        }
        TradingStateProjection.RiskState accountRisk = accountRisk(position);
        if (accountRisk == null) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_account_risk_missing"
                    : null;
        }
        BigDecimal marginBalance = decimal(accountRisk.marginBalance());
        if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_margin_balance_missing"
                    : null;
        }
        BigDecimal maxMarginBalance = decimal(accountRisk.maxMarginBalance());
        if (maxMarginBalance == null || maxMarginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return positionOrderPolicy.rejectMissingAccountRiskMetadata()
                    ? "position_order_max_margin_balance_missing"
                    : null;
        }
        if (operation != Operation.HEDGE_POSITION) {
            return null;
        }
        BigDecimal drawdown = marginBalance.compareTo(maxMarginBalance) >= 0
                ? BigDecimal.ZERO
                : maxMarginBalance.subtract(marginBalance).divide(maxMarginBalance, MathContext.DECIMAL64);
        if (drawdown.compareTo(maxDrawdown) > 0) {
            return "position_order_account_margin_drawdown_exceeded";
        }
        return null;
    }

    private TradingStateProjection.RiskState accountRisk(TradingStateProjection.PositionState position) {
        return projection.risk(
                        position.provider(),
                        position.environment(),
                        position.account(),
                        position.market(),
                        "ACCOUNT",
                        position.account()
                )
                .orElse(null);
    }

    private String hedgePositionModePolicyBlocker(TradingStateProjection.PositionState position) {
        String requiredPositionMode = text(positionOrderPolicy.requiredPositionMode());
        if (requiredPositionMode == null) {
            return null;
        }
        String positionMode = text(position.positionMode());
        if (positionMode == null) {
            return "position_order_position_mode_missing";
        }
        if (!requiredPositionMode.equalsIgnoreCase(positionMode)) {
            return "position_order_position_mode_policy_missing";
        }
        return null;
    }

    private PositionRemediationSize positionRemediationSize(
            RemediationDecisionEvent event,
            BigDecimal positionAmount,
            Operation operation
    ) {
        BigDecimal absoluteAmount = positionAmount.abs();
        return switch (operation) {
            case CLOSE_POSITION -> PositionRemediationSize.valid(
                    absoluteAmount,
                    "bounded_projection_full_close",
                    true,
                    false,
                    "position_order_execution_policy_missing"
            );
            case REDUCE_POSITION -> boundedQuantity(
                    event,
                    absoluteAmount,
                    ATTR_REDUCE_QUANTITY,
                    ATTR_REDUCE_FRACTION,
                    "bounded_projection_reduce",
                    true,
                    false,
                    "position_order_execution_policy_missing",
                    "remediation:reduce_size_missing",
                    "remediation:reduce_size_invalid",
                    "remediation:reduce_size_exceeds_position"
            );
            case HEDGE_POSITION -> boundedQuantity(
                    event,
                    absoluteAmount,
                    ATTR_HEDGE_QUANTITY,
                    ATTR_HEDGE_FRACTION,
                    "bounded_projection_hedge",
                    false,
                    true,
                    "hedge_mode_provider_policy_missing",
                    absoluteAmount
            );
            default -> PositionRemediationSize.invalid("remediation:unsupported_position_sizing_operation");
        };
    }

    private String commandPositionSide(Operation operation, BigDecimal positionAmount) {
        if (operation != Operation.HEDGE_POSITION) {
            return null;
        }
        return positionAmount.signum() > 0 ? "SHORT" : "LONG";
    }

    private PositionRemediationSize boundedQuantity(
            RemediationDecisionEvent event,
            BigDecimal absoluteAmount,
            String quantityAttribute,
            String fractionAttribute,
            String policy,
            boolean reduceOnlyRequired,
            boolean hedgeModeRequired,
            String exchangeExecutionBlocker,
            BigDecimal defaultQuantity
    ) {
        BigDecimal quantity = decimal(attribute(event, quantityAttribute));
        if (quantity == null) {
            BigDecimal fraction = decimal(attribute(event, fractionAttribute));
            quantity = fraction == null ? defaultQuantity : absoluteAmount.multiply(fraction);
        }
        return boundedQuantity(
                quantity,
                absoluteAmount,
                policy,
                reduceOnlyRequired,
                hedgeModeRequired,
                exchangeExecutionBlocker,
                "remediation:position_size_missing",
                "remediation:position_size_invalid",
                "remediation:position_size_exceeds_position"
        );
    }

    private PositionRemediationSize boundedQuantity(
            RemediationDecisionEvent event,
            BigDecimal absoluteAmount,
            String quantityAttribute,
            String fractionAttribute,
            String policy,
            boolean reduceOnlyRequired,
            boolean hedgeModeRequired,
            String exchangeExecutionBlocker,
            String missingReason,
            String invalidReason,
            String exceedsReason
    ) {
        BigDecimal quantity = decimal(attribute(event, quantityAttribute));
        if (quantity == null) {
            BigDecimal fraction = decimal(attribute(event, fractionAttribute));
            if (fraction != null) {
                quantity = fraction.compareTo(BigDecimal.ZERO) > 0 && fraction.compareTo(BigDecimal.ONE) <= 0
                        ? absoluteAmount.multiply(fraction)
                        : BigDecimal.ZERO;
            }
        }
        return boundedQuantity(
                quantity,
                absoluteAmount,
                policy,
                reduceOnlyRequired,
                hedgeModeRequired,
                exchangeExecutionBlocker,
                missingReason,
                invalidReason,
                exceedsReason
        );
    }

    private PositionRemediationSize boundedQuantity(
            BigDecimal quantity,
            BigDecimal absoluteAmount,
            String policy,
            boolean reduceOnlyRequired,
            boolean hedgeModeRequired,
            String exchangeExecutionBlocker,
            String missingReason,
            String invalidReason,
            String exceedsReason
    ) {
        if (quantity == null) {
            return PositionRemediationSize.invalid(missingReason);
        }
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return PositionRemediationSize.invalid(invalidReason);
        }
        if (quantity.compareTo(absoluteAmount) > 0) {
            return PositionRemediationSize.invalid(exceedsReason);
        }
        return PositionRemediationSize.valid(
                quantity,
                policy,
                reduceOnlyRequired,
                hedgeModeRequired,
                exchangeExecutionBlocker
        );
    }

    private RemediationCommandPlan ready(
            RemediationDecisionEvent event,
            Operation operation,
            boolean exchangeExecutable,
            String reason,
            Map<String, String> attributes
    ) {
        attributes.put("exchange_executable", Boolean.toString(exchangeExecutable));
        return plan(event, PlanStatus.READY, operation, exchangeExecutable, List.of(reason), attributes);
    }

    private Map<String, String> exchangeExecutionAttributes(Map<String, String> attributes, String executionPath) {
        attributes.put("exchange_execution_path", executionPath);
        return attributes;
    }

    private RemediationCommandPlan noAction(
            RemediationDecisionEvent event,
            Operation operation,
            String reason,
            Map<String, String> attributes
    ) {
        attributes.put("exchange_executable", "false");
        return plan(event, PlanStatus.NO_ACTION, operation, false, List.of(reason), attributes);
    }

    private RemediationCommandPlan stale(RemediationDecisionEvent event, Operation operation, String reason) {
        return plan(event, PlanStatus.STALE_PROJECTION, operation, false, List.of(reason), baseAttributes(event));
    }

    private RemediationCommandPlan insufficientData(
            RemediationDecisionEvent event,
            Operation operation,
            String reason,
            Map<String, String> attributes
    ) {
        attributes.put("exchange_executable", "false");
        return plan(event, PlanStatus.INSUFFICIENT_DATA, operation, false, List.of(reason), attributes);
    }

    private RemediationCommandPlan unsupported(RemediationDecisionEvent event, Operation operation, String reason) {
        Map<String, String> attributes = baseAttributes(event);
        attributes.put("exchange_executable", "false");
        return plan(event, PlanStatus.NOT_SUPPORTED, operation, false, List.of(reason), attributes);
    }

    private RemediationCommandPlan plan(
            RemediationDecisionEvent event,
            PlanStatus status,
            Operation operation,
            boolean exchangeExecutable,
            List<String> reasons,
            Map<String, String> attributes
    ) {
        return new RemediationCommandPlan(
                text(event.getRemediationId()),
                text(event.getScope()),
                text(event.getAction()),
                text(event.getProvider()),
                text(event.getEnvironment()),
                text(event.getAccount()),
                text(event.getMarket()),
                text(event.getSymbol()),
                text(event.getClientOrderId()),
                text(event.getPositionSide()),
                status,
                operation,
                exchangeExecutable,
                reasons,
                attributes
        );
    }

    private Map<String, String> baseAttributes(RemediationDecisionEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "remediation_id", event.getRemediationId());
        put(attributes, "remediation_event_id", event.getEventId());
        put(attributes, "remediation_scope", event.getScope());
        put(attributes, "remediation_action", event.getAction());
        put(attributes, "intervention_reason", event.getInterventionReason());
        return attributes;
    }

    private void putManagedOrderAmendmentPolicyAttributes(Map<String, String> attributes) {
        attributes.put("managed_order_amendment_policy_enabled",
                Boolean.toString(managedOrderAmendmentPolicy.enabled()));
        put(attributes, "managed_order_amendment_policy_provider", managedOrderAmendmentPolicy.provider());
        put(attributes, "managed_order_amendment_policy_market", managedOrderAmendmentPolicy.market());
        attributes.put("managed_order_amendment_allow_bot_created_orders",
                Boolean.toString(managedOrderAmendmentPolicy.allowBotCreatedOrders()));
        attributes.put("managed_order_amendment_allow_adopted_orders",
                Boolean.toString(managedOrderAmendmentPolicy.allowAdoptedOrders()));
        if (!managedOrderAmendmentPolicy.allowedSymbols().isEmpty()) {
            attributes.put("managed_order_amendment_allowed_symbols",
                    String.join(",", managedOrderAmendmentPolicy.allowedSymbols()));
        }
        if (!managedOrderAmendmentPolicy.allowedOrderTypes().isEmpty()) {
            attributes.put("managed_order_amendment_allowed_order_types",
                    String.join(",", managedOrderAmendmentPolicy.allowedOrderTypes()));
        }
        if (!managedOrderAmendmentPolicy.allowedFields().isEmpty()) {
            attributes.put("managed_order_amendment_allowed_fields",
                    String.join(",", managedOrderAmendmentPolicy.allowedFields()));
        }
        attributes.put("managed_order_amendment_allow_quantity_increase",
                Boolean.toString(managedOrderAmendmentPolicy.allowQuantityIncrease()));
        attributes.put("managed_order_amendment_allow_quantity_decrease",
                Boolean.toString(managedOrderAmendmentPolicy.allowQuantityDecrease()));
        put(
                attributes,
                "managed_order_amendment_max_quantity_increase_fraction",
                managedOrderAmendmentPolicy.maxQuantityIncreaseFraction()
        );
        put(
                attributes,
                "managed_order_amendment_max_quantity_decrease_fraction",
                managedOrderAmendmentPolicy.maxQuantityDecreaseFraction()
        );
        put(attributes, "managed_order_amendment_max_price_drift_fraction",
                managedOrderAmendmentPolicy.maxPriceDriftFraction());
        attributes.put("managed_order_amendment_cancel_replace_on_unsupported_change",
                Boolean.toString(managedOrderAmendmentPolicy.cancelReplaceOnUnsupportedChange()));
        attributes.put("managed_order_amendment_reject_stale_projection",
                Boolean.toString(managedOrderAmendmentPolicy.rejectStaleProjection()));
        put(
                attributes,
                "managed_order_amendment_max_projection_age_millis",
                managedOrderAmendmentPolicy.maxProjectionAgeMillis() == null
                        ? null
                        : managedOrderAmendmentPolicy.maxProjectionAgeMillis().toString()
        );
        attributes.put("managed_order_amendment_require_open_order_status",
                Boolean.toString(managedOrderAmendmentPolicy.requireOpenOrderStatus()));
        attributes.put("managed_order_amendment_require_exchange_order_id",
                Boolean.toString(managedOrderAmendmentPolicy.requireExchangeOrderId()));
        if (!managedOrderAmendmentPolicy.allowedStatuses().isEmpty()) {
            attributes.put("managed_order_amendment_allowed_statuses",
                    String.join(",", managedOrderAmendmentPolicy.allowedStatuses()));
        }
    }

    private String managedOrderAmendmentBlocker(
            RemediationDecisionEvent event,
            TradingStateProjection.OrderState order,
            AmendmentRequest request
    ) {
        if (!managedOrderAmendmentPolicy.enabled()) {
            return "managed_order_amendment_policy_disabled";
        }
        if (!managedOrderAmendmentPolicy.provider().equalsIgnoreCase(requireText(event.getProvider(), "provider"))) {
            return "managed_order_amendment_provider_policy_missing";
        }
        if (!managedOrderAmendmentPolicy.market().equalsIgnoreCase(requireText(event.getMarket(), "market"))) {
            return "managed_order_amendment_market_policy_missing";
        }
        if (!managedOrderAmendmentPolicy.allowedSymbols().isEmpty()
                && !managedOrderAmendmentPolicy.allowedSymbols().contains(requireText(event.getSymbol(), "symbol")
                        .toUpperCase(Locale.ROOT))) {
            return "managed_order_amendment_symbol_policy_missing";
        }
        if (!order.managedByBot()) {
            return "managed_order_amendment_unmanaged_order";
        }
        String ownership = amendmentOrderOwnership(order);
        if ("ADOPTED".equals(ownership) && !managedOrderAmendmentPolicy.allowAdoptedOrders()) {
            return "managed_order_amendment_adopted_order_policy_disabled";
        }
        if ("ADOPTED".equals(ownership)) {
            String adoptedOrderBlocker = adoptedOrderLifecycleBlocker(event, order, "AMEND");
            if (adoptedOrderBlocker != null) {
                return adoptedOrderBlocker;
            }
        }
        if ("BOT_CREATED".equals(ownership) && !managedOrderAmendmentPolicy.allowBotCreatedOrders()) {
            return "managed_order_amendment_bot_created_order_policy_disabled";
        }
        if ("MODIFY".equals(uppercase(order.executionType())) && "COMMAND_RECEIVED".equals(uppercase(order.status()))) {
            return "managed_order_amendment_modify_pending_reconciliation";
        }
        if ("MODIFY".equals(uppercase(order.executionType())) && "UNKNOWN".equals(uppercase(order.status()))) {
            return "managed_order_amendment_modify_unknown_reconciliation_required";
        }
        if (managedOrderAmendmentPolicy.requireOpenOrderStatus()
                && !managedOrderAmendmentPolicy.allowedStatuses().contains(uppercase(order.status()))) {
            return "managed_order_amendment_order_status_not_open";
        }
        if (managedOrderAmendmentPolicy.requireExchangeOrderId() && text(order.exchangeOrderId()) == null) {
            return "managed_order_amendment_exchange_order_id_missing";
        }
        if (uppercase(order.side()) == null) {
            return "managed_order_amendment_side_missing";
        }
        String projectedOrderType = uppercase(order.orderType());
        if (projectedOrderType == null) {
            return "managed_order_amendment_order_type_missing";
        }
        if (managedOrderAmendmentPolicy.rejectStaleProjection()
                && managedOrderAmendmentPolicy.maxProjectionAgeMillis() != null) {
            long ageMillis = Duration.between(order.updatedAt(), event.getDecidedAtMicros()).toMillis();
            if (ageMillis < 0L || ageMillis > managedOrderAmendmentPolicy.maxProjectionAgeMillis()) {
                return "managed_order_amendment_projection_stale";
            }
        }
        if (!managedOrderAmendmentPolicy.allowedOrderTypes().isEmpty()) {
            String requestedOrderType = uppercase(request.orderType());
            if (requestedOrderType != null && !requestedOrderType.equals(projectedOrderType)) {
                if (managedOrderAmendmentPolicy.cancelReplaceOnUnsupportedChange()) {
                    return "managed_order_amendment_cancel_replace_fallback_not_supported";
                }
                return "managed_order_amendment_order_type_change_not_allowed";
            }
            if (!managedOrderAmendmentPolicy.allowedOrderTypes().contains(projectedOrderType)) {
                return "managed_order_amendment_order_type_not_allowed";
            }
        }
        if (request.price() != null && !managedOrderAmendmentPolicy.allowedFields().contains("PRICE")) {
            if (managedOrderAmendmentPolicy.cancelReplaceOnUnsupportedChange()) {
                return "managed_order_amendment_cancel_replace_fallback_not_supported";
            }
            return "managed_order_amendment_price_field_not_allowed";
        }
        if (request.quantity() != null && !managedOrderAmendmentPolicy.allowedFields().contains("QUANTITY")) {
            if (managedOrderAmendmentPolicy.cancelReplaceOnUnsupportedChange()) {
                return "managed_order_amendment_cancel_replace_fallback_not_supported";
            }
            return "managed_order_amendment_quantity_field_not_allowed";
        }
        return null;
    }

    private String adoptedOrderLifecycleBlocker(
            RemediationDecisionEvent event,
            TradingStateProjection.OrderState order,
            String operation
    ) {
        if (!adoptedOrder(order)) {
            return null;
        }
        if (!adoptedOrderLifecyclePolicy.enabled()) {
            return "adopted_order_lifecycle_policy_disabled";
        }
        if (!adoptedOrderLifecyclePolicy.provider().equalsIgnoreCase(requireText(event.getProvider(), "provider"))) {
            return "adopted_order_lifecycle_provider_policy_missing";
        }
        if (!adoptedOrderLifecyclePolicy.market().equalsIgnoreCase(requireText(event.getMarket(), "market"))) {
            return "adopted_order_lifecycle_market_policy_missing";
        }
        if (!adoptedOrderLifecyclePolicy.allowedSymbols().isEmpty()
                && !adoptedOrderLifecyclePolicy.allowedSymbols().contains(requireText(event.getSymbol(), "symbol")
                        .toUpperCase(Locale.ROOT))) {
            return "adopted_order_lifecycle_symbol_policy_missing";
        }
        if ("CANCEL".equals(operation) && !adoptedOrderLifecyclePolicy.allowCancel()) {
            return "adopted_order_lifecycle_cancel_not_allowed";
        }
        if ("AMEND".equals(operation) && !adoptedOrderLifecyclePolicy.allowAmend()) {
            return "adopted_order_lifecycle_amend_not_allowed";
        }
        if (adoptedOrderLifecyclePolicy.rejectPendingOrUnknownModify()
                && "MODIFY".equals(uppercase(order.executionType()))
                && "COMMAND_RECEIVED".equals(uppercase(order.status()))) {
            return "adopted_order_lifecycle_modify_pending_reconciliation";
        }
        if (adoptedOrderLifecyclePolicy.rejectPendingOrUnknownModify()
                && "MODIFY".equals(uppercase(order.executionType()))
                && "UNKNOWN".equals(uppercase(order.status()))) {
            return "adopted_order_lifecycle_modify_unknown_reconciliation_required";
        }
        if (adoptedOrderLifecyclePolicy.requireOpenOrderStatus()
                && !adoptedOrderLifecyclePolicy.allowedStatuses().contains(uppercase(order.status()))) {
            return "adopted_order_lifecycle_order_status_not_open";
        }
        if (adoptedOrderLifecyclePolicy.requireExchangeOrderId() && text(order.exchangeOrderId()) == null) {
            return "adopted_order_lifecycle_exchange_order_id_missing";
        }
        if (adoptedOrderLifecyclePolicy.rejectStaleProjection()
                && adoptedOrderLifecyclePolicy.maxProjectionAgeMillis() != null) {
            long ageMillis = Duration.between(order.updatedAt(), event.getDecidedAtMicros()).toMillis();
            if (ageMillis < 0L || ageMillis > adoptedOrderLifecyclePolicy.maxProjectionAgeMillis()) {
                return "adopted_order_lifecycle_projection_stale";
            }
        }
        return null;
    }

    private void putAdoptedOrderLifecycleAmbiguousOutcomeAttributes(
            Map<String, String> attributes,
            String blocker
    ) {
        if (!adoptedOrderLifecycleAmbiguousBlocker(blocker)) {
            return;
        }
        attributes.put("adopted_order_lifecycle_ambiguous_outcome_detected", "true");
        attributes.put("adopted_order_lifecycle_ambiguous_outcome_reason", blocker);
        attributes.put("adopted_order_lifecycle_ambiguous_outcome_action", "reconcile_projection_then_repreview");
        attributes.put(
                "adopted_order_lifecycle_rollback_policy_enabled",
                Boolean.toString(adoptedOrderLifecyclePolicy.rollbackOnAmbiguousOutcome())
        );
        attributes.put("adopted_order_lifecycle_retry_blocker", "reconciliation_required");
        attributes.put("adopted_order_lifecycle_rollback_exchange_executable", "false");
        if (adoptedOrderLifecyclePolicy.rollbackOnAmbiguousOutcome()) {
            attributes.put("adopted_order_lifecycle_rollback_result", "blocked");
            attributes.put(
                    "adopted_order_lifecycle_rollback_blocker",
                    "adopted_order_lifecycle_rollback_not_implemented"
            );
        } else {
            attributes.put("adopted_order_lifecycle_rollback_result", "disabled");
            attributes.put(
                    "adopted_order_lifecycle_rollback_blocker",
                    "adopted_order_lifecycle_rollback_policy_disabled"
            );
        }
    }

    private boolean adoptedOrderLifecycleAmbiguousBlocker(String blocker) {
        return "adopted_order_lifecycle_modify_pending_reconciliation".equals(blocker)
                || "adopted_order_lifecycle_modify_unknown_reconciliation_required".equals(blocker);
    }

    private AmendmentRequest amendmentRequest(RemediationDecisionEvent event) {
        String priceText = firstAttribute(event, "amend_price", "target_price", "new_price");
        String quantityText = firstAttribute(event, "amend_quantity", "target_quantity", "new_quantity");
        String orderType = firstAttribute(event, "amend_order_type", "target_order_type", "order_type");
        BigDecimal price = decimal(priceText);
        if (priceText != null && price == null) {
            return AmendmentRequest.invalid(priceText, quantityText, orderType, "remediation:amend_price_invalid");
        }
        BigDecimal quantity = decimal(quantityText);
        if (quantityText != null && quantity == null) {
            return AmendmentRequest.invalid(priceText, quantityText, orderType, "remediation:amend_quantity_invalid");
        }
        if (price != null && price.compareTo(BigDecimal.ZERO) <= 0) {
            return AmendmentRequest.invalid(priceText, quantityText, orderType, "remediation:amend_price_invalid");
        }
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return AmendmentRequest.invalid(priceText, quantityText, orderType, "remediation:amend_quantity_invalid");
        }
        return new AmendmentRequest(priceText, price, quantityText, quantity, orderType, null);
    }

    private AmendmentDrift amendmentDrift(TradingStateProjection.OrderState order, AmendmentRequest request) {
        BigDecimal currentPrice = decimal(order.price());
        BigDecimal currentQuantity = decimal(order.originalQuantity());
        BigDecimal priceDrift = null;
        BigDecimal quantityDrift = null;
        if (request.price() != null) {
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return AmendmentDrift.invalid("projection:order_price_invalid");
            }
            priceDrift = request.price().subtract(currentPrice).abs().divide(currentPrice, MathContext.DECIMAL64);
        }
        if (request.quantity() != null) {
            if (currentQuantity == null || currentQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                return AmendmentDrift.invalid("projection:order_quantity_invalid");
            }
            quantityDrift = request.quantity().subtract(currentQuantity).divide(currentQuantity, MathContext.DECIMAL64);
        }
        if (request.price() == null && (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            return AmendmentDrift.invalid("projection:order_price_invalid");
        }
        if (request.quantity() == null && (currentQuantity == null || currentQuantity.compareTo(BigDecimal.ZERO) <= 0)) {
            return AmendmentDrift.invalid("projection:order_quantity_invalid");
        }
        return new AmendmentDrift(priceDrift, quantityDrift, null);
    }

    private String amendmentDriftBlocker(AmendmentDrift drift) {
        if (drift.quantityDrift() != null && drift.quantityDrift().compareTo(BigDecimal.ZERO) > 0) {
            if (!managedOrderAmendmentPolicy.allowQuantityIncrease()) {
                return "managed_order_amendment_quantity_increase_not_allowed";
            }
            BigDecimal maxIncrease = decimal(managedOrderAmendmentPolicy.maxQuantityIncreaseFraction());
            if (maxIncrease != null && drift.quantityDrift().compareTo(maxIncrease) > 0) {
                return "managed_order_amendment_quantity_increase_drift_exceeded";
            }
        }
        if (drift.quantityDrift() != null && drift.quantityDrift().compareTo(BigDecimal.ZERO) < 0) {
            if (!managedOrderAmendmentPolicy.allowQuantityDecrease()) {
                return "managed_order_amendment_quantity_decrease_not_allowed";
            }
            BigDecimal maxDecrease = decimal(managedOrderAmendmentPolicy.maxQuantityDecreaseFraction());
            if (maxDecrease != null && drift.quantityDrift().abs().compareTo(maxDecrease) > 0) {
                return "managed_order_amendment_quantity_decrease_drift_exceeded";
            }
        }
        BigDecimal maxPriceDrift = decimal(managedOrderAmendmentPolicy.maxPriceDriftFraction());
        if (maxPriceDrift != null && drift.priceDrift() != null && drift.priceDrift().compareTo(maxPriceDrift) > 0) {
            return "managed_order_amendment_price_drift_exceeded";
        }
        return null;
    }

    private String amendmentOrderOwnership(TradingStateProjection.OrderState order) {
        if (!order.managedByBot()) {
            return "UNMANAGED";
        }
        return text(order.commandId()) == null ? "ADOPTED" : "BOT_CREATED";
    }

    private boolean adoptedOrder(TradingStateProjection.OrderState order) {
        return order.managedByBot() && text(order.commandId()) == null;
    }

    private void putAdoptedOrderLifecyclePolicyAttributes(Map<String, String> attributes) {
        attributes.put("adopted_order_lifecycle_policy_enabled",
                Boolean.toString(adoptedOrderLifecyclePolicy.enabled()));
        put(attributes, "adopted_order_lifecycle_policy_provider", adoptedOrderLifecyclePolicy.provider());
        put(attributes, "adopted_order_lifecycle_policy_market", adoptedOrderLifecyclePolicy.market());
        attributes.put("adopted_order_lifecycle_preserve_by_default",
                Boolean.toString(adoptedOrderLifecyclePolicy.preserveByDefault()));
        attributes.put("adopted_order_lifecycle_allow_cancel",
                Boolean.toString(adoptedOrderLifecyclePolicy.allowCancel()));
        attributes.put("adopted_order_lifecycle_allow_amend",
                Boolean.toString(adoptedOrderLifecyclePolicy.allowAmend()));
        attributes.put("adopted_order_lifecycle_allow_replace",
                Boolean.toString(adoptedOrderLifecyclePolicy.allowReplace()));
        attributes.put("adopted_order_lifecycle_rollback_on_ambiguous_outcome",
                Boolean.toString(adoptedOrderLifecyclePolicy.rollbackOnAmbiguousOutcome()));
        attributes.put("adopted_order_lifecycle_reject_stale_projection",
                Boolean.toString(adoptedOrderLifecyclePolicy.rejectStaleProjection()));
        put(
                attributes,
                "adopted_order_lifecycle_max_projection_age_millis",
                adoptedOrderLifecyclePolicy.maxProjectionAgeMillis() == null
                        ? null
                        : adoptedOrderLifecyclePolicy.maxProjectionAgeMillis().toString()
        );
        attributes.put("adopted_order_lifecycle_require_open_order_status",
                Boolean.toString(adoptedOrderLifecyclePolicy.requireOpenOrderStatus()));
        attributes.put("adopted_order_lifecycle_require_exchange_order_id",
                Boolean.toString(adoptedOrderLifecyclePolicy.requireExchangeOrderId()));
        attributes.put("adopted_order_lifecycle_reject_pending_or_unknown_modify",
                Boolean.toString(adoptedOrderLifecyclePolicy.rejectPendingOrUnknownModify()));
        if (!adoptedOrderLifecyclePolicy.allowedSymbols().isEmpty()) {
            attributes.put("adopted_order_lifecycle_allowed_symbols",
                    String.join(",", adoptedOrderLifecyclePolicy.allowedSymbols()));
        }
        if (!adoptedOrderLifecyclePolicy.allowedStatuses().isEmpty()) {
            attributes.put("adopted_order_lifecycle_allowed_statuses",
                    String.join(",", adoptedOrderLifecyclePolicy.allowedStatuses()));
        }
    }

    private void putOrderAttributes(Map<String, String> attributes, TradingStateProjection.OrderState order) {
        put(attributes, "target_client_order_id", order.clientOrderId());
        put(attributes, "target_exchange_order_id", order.exchangeOrderId());
        put(attributes, "target_order_status", order.status());
        put(attributes, "target_exchange_status", order.exchangeStatus());
        put(attributes, "target_order_side", order.side());
        put(attributes, "target_order_type", order.orderType());
        put(attributes, "target_update_source", order.updateSource());
        put(attributes, "target_event_id", order.eventId());
        put(attributes, "target_order_price", order.price());
        put(attributes, "target_order_quantity", order.originalQuantity());
        attributes.put("target_managed_by_bot", Boolean.toString(order.managedByBot()));
    }

    private void putPositionAttributes(Map<String, String> attributes, TradingStateProjection.PositionState position) {
        put(attributes, "position_side", position.positionSide());
        put(attributes, "position_amount", position.positionAmount());
        put(attributes, "entry_price", position.entryPrice());
        put(attributes, "mark_price", position.markPrice());
        put(attributes, "unrealized_pnl", position.unrealizedPnl());
        put(attributes, "position_leverage", position.leverage());
        put(attributes, "position_margin_type", position.marginType());
        put(attributes, "position_mode", position.positionMode());
        put(attributes, "position_isolated_margin", position.isolatedMargin());
        put(attributes, "target_update_source", position.updateSource());
        put(attributes, "target_event_id", position.eventId());
    }

    private void putAccountRiskAttributes(Map<String, String> attributes, TradingStateProjection.RiskState risk) {
        put(attributes, "account_risk_level", risk.riskLevel());
        put(attributes, "account_margin_balance", risk.marginBalance());
        put(attributes, "account_max_margin_balance", risk.maxMarginBalance());
        put(attributes, "account_maintenance_margin", risk.maintenanceMargin());
        BigDecimal marginBalance = decimal(risk.marginBalance());
        BigDecimal maxMarginBalance = decimal(risk.maxMarginBalance());
        if (marginBalance != null && marginBalance.compareTo(BigDecimal.ZERO) > 0
                && maxMarginBalance != null && maxMarginBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdown = marginBalance.compareTo(maxMarginBalance) >= 0
                    ? BigDecimal.ZERO
                    : maxMarginBalance.subtract(marginBalance).divide(maxMarginBalance, MathContext.DECIMAL64);
            put(attributes, "account_margin_drawdown_fraction", normalize(drawdown));
        }
        BigDecimal maintenanceMargin = decimal(risk.maintenanceMargin());
        if (marginBalance != null && marginBalance.compareTo(BigDecimal.ZERO) > 0
                && maintenanceMargin != null && maintenanceMargin.compareTo(BigDecimal.ZERO) >= 0) {
            put(attributes, "account_margin_utilization", normalize(
                    maintenanceMargin.divide(marginBalance, MathContext.DECIMAL64)
            ));
        }
    }

    private void put(Map<String, String> attributes, String key, CharSequence value) {
        String text = text(value);
        if (text != null) {
            attributes.put(key, text);
        }
    }

    private String attribute(RemediationDecisionEvent event, String key) {
        if (event.getAttributes() == null) {
            return null;
        }
        return text(event.getAttributes().get(key));
    }

    private String firstAttribute(RemediationDecisionEvent event, String... keys) {
        for (String key : keys) {
            String value = attribute(event, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal decimal(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer integer(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String tradingDay(Instant eventTime) {
        LocalDate day = eventTime.atZone(ZoneOffset.UTC).toLocalDate();
        return day.toString();
    }

    private boolean sameSymbol(String value, String expected) {
        String text = text(value);
        return text != null && expected != null && text.equalsIgnoreCase(expected);
    }

    private boolean samePositionSide(String value, String expected) {
        String text = text(value);
        return text != null && expected != null && text.equalsIgnoreCase(expected);
    }

    private String requireText(CharSequence value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String text(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private String uppercase(String value) {
        String text = text(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    public enum PlanStatus {
        READY,
        NO_ACTION,
        NOT_SUPPORTED,
        STALE_PROJECTION,
        INSUFFICIENT_DATA
    }

    public enum Operation {
        OPERATOR_REVIEW,
        REPLAN_FROM_PROJECTION,
        ADOPT_ORDER,
        ADOPT_POSITION,
        AMEND_ORDER,
        CANCEL_ORDER,
        REDUCE_POSITION,
        CLOSE_POSITION,
        HEDGE_POSITION,
        PAUSE_SYMBOL,
        PAUSE_ACCOUNT,
        RELEASE_PAUSE,
        IGNORE,
        UNSUPPORTED
    }

    public record RemediationCommandPlan(
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
            PlanStatus status,
            Operation operation,
            boolean exchangeExecutable,
            List<String> reasons,
            Map<String, String> attributes
    ) {

        public RemediationCommandPlan {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private record PositionRemediationSize(
            BigDecimal quantity,
            String policy,
            boolean reduceOnlyRequired,
            boolean hedgeModeRequired,
            String exchangeExecutionBlocker,
            boolean closeChunked,
            String invalidReason
    ) {

        private boolean valid() {
            return quantity != null;
        }

        private static PositionRemediationSize valid(
                BigDecimal quantity,
                String policy,
                boolean reduceOnlyRequired,
                boolean hedgeModeRequired,
                String exchangeExecutionBlocker
        ) {
            return valid(quantity, policy, reduceOnlyRequired, hedgeModeRequired, exchangeExecutionBlocker, false);
        }

        private static PositionRemediationSize valid(
                BigDecimal quantity,
                String policy,
                boolean reduceOnlyRequired,
                boolean hedgeModeRequired,
                String exchangeExecutionBlocker,
                boolean closeChunked
        ) {
            return new PositionRemediationSize(
                    quantity,
                    policy,
                    reduceOnlyRequired,
                    hedgeModeRequired,
                    exchangeExecutionBlocker,
                    closeChunked,
                    null
            );
        }

        private static PositionRemediationSize invalid(String reason) {
            return new PositionRemediationSize(null, null, false, false, null, false, reason);
        }
    }

    private record AmendmentRequest(
            String priceText,
            BigDecimal price,
            String quantityText,
            BigDecimal quantity,
            String orderType,
            String invalidReason
    ) {

        private boolean hasChange() {
            return price != null || quantity != null;
        }

        private boolean valid() {
            return invalidReason == null;
        }

        private static AmendmentRequest invalid(
                String priceText,
                String quantityText,
                String orderType,
                String invalidReason
        ) {
            return new AmendmentRequest(priceText, null, quantityText, null, orderType, invalidReason);
        }
    }

    private record AmendmentDrift(
            BigDecimal priceDrift,
            BigDecimal quantityDrift,
            String invalidReason
    ) {

        private boolean valid() {
            return invalidReason == null;
        }

        private boolean noop() {
            return (priceDrift == null || priceDrift.compareTo(BigDecimal.ZERO) == 0)
                    && (quantityDrift == null || quantityDrift.compareTo(BigDecimal.ZERO) == 0);
        }

        private static AmendmentDrift invalid(String invalidReason) {
            return new AmendmentDrift(null, null, invalidReason);
        }
    }

    private record PositionOrderExecutionPolicy(
            boolean reduceOnly,
            String executionMode,
            String blocker
    ) {

        private static PositionOrderExecutionPolicy executable(boolean reduceOnly, String executionMode) {
            return new PositionOrderExecutionPolicy(reduceOnly, executionMode, null);
        }

        private static PositionOrderExecutionPolicy blocked(boolean reduceOnly, String blocker) {
            return new PositionOrderExecutionPolicy(reduceOnly, null, blocker);
        }
    }

    private record PositionExposure(
            BigDecimal currentAccountNotional,
            BigDecimal currentSymbolNotional,
            BigDecimal projectedAccountNotional,
            BigDecimal projectedSymbolNotional,
            boolean valid
    ) {

        private static PositionExposure valid(
                BigDecimal currentAccountNotional,
                BigDecimal currentSymbolNotional,
                BigDecimal projectedAccountNotional,
                BigDecimal projectedSymbolNotional
        ) {
            return new PositionExposure(
                    currentAccountNotional,
                    currentSymbolNotional,
                    projectedAccountNotional,
                    projectedSymbolNotional,
                    true
            );
        }

        private static PositionExposure invalid() {
            return new PositionExposure(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
    }

    private record PositionLoss(
            BigDecimal accountLoss,
            BigDecimal symbolLoss,
            boolean valid
    ) {

        private static PositionLoss valid(BigDecimal accountLoss, BigDecimal symbolLoss) {
            return new PositionLoss(accountLoss, symbolLoss, true);
        }

        private static PositionLoss invalid() {
            return new PositionLoss(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
    }

    private record DailyRealizedLoss(
            BigDecimal accountPnl,
            BigDecimal accountLoss,
            BigDecimal symbolPnl,
            BigDecimal symbolLoss,
            String tradingDay,
            boolean valid
    ) {

        private static DailyRealizedLoss valid(
                BigDecimal accountPnl,
                BigDecimal accountLoss,
                BigDecimal symbolPnl,
                BigDecimal symbolLoss,
                String tradingDay
        ) {
            return new DailyRealizedLoss(accountPnl, accountLoss, symbolPnl, symbolLoss, tradingDay, true);
        }

        private static DailyRealizedLoss invalid() {
            return new DailyRealizedLoss(null, BigDecimal.ZERO, null, BigDecimal.ZERO, null, false);
        }
    }
}
