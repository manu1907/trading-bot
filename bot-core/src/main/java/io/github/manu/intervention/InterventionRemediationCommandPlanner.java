package io.github.manu.intervention;

import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.projection.TradingStateProjection;

import java.math.BigDecimal;
import java.math.MathContext;
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

    public InterventionRemediationCommandPlanner(TradingStateProjection projection) {
        this(projection, InterventionProperties.PositionOrderPolicy.disabled());
    }

    public InterventionRemediationCommandPlanner(
            TradingStateProjection projection,
            InterventionProperties.PositionOrderPolicy positionOrderPolicy
    ) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.positionOrderPolicy = Objects.requireNonNull(positionOrderPolicy, "positionOrderPolicy");
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
            case ACTION_AMEND -> ready(event, Operation.AMEND_ORDER, false, "remediation:amend_order", attributes);
            case ACTION_CLOSE -> ready(
                    event,
                    Operation.CANCEL_ORDER,
                    true,
                    "remediation:cancel_external_order",
                    exchangeExecutionAttributes(attributes, "order_execution_pipeline")
            );
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
        putPositionOrderRiskPolicyAttributes(attributes, position, operation, size);
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
        put(attributes, "position_order_max_account_margin_utilization", positionOrderPolicy.maxAccountMarginUtilization());
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
        String marginBalancePolicyBlocker = accountMarginBalancePolicyBlocker(position, operation);
        if (marginBalancePolicyBlocker != null) {
            return marginBalancePolicyBlocker;
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

    private void putOrderAttributes(Map<String, String> attributes, TradingStateProjection.OrderState order) {
        put(attributes, "target_client_order_id", order.clientOrderId());
        put(attributes, "target_exchange_order_id", order.exchangeOrderId());
        put(attributes, "target_order_status", order.status());
        put(attributes, "target_exchange_status", order.exchangeStatus());
        put(attributes, "target_update_source", order.updateSource());
        put(attributes, "target_event_id", order.eventId());
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
        put(attributes, "account_maintenance_margin", risk.maintenanceMargin());
        BigDecimal marginBalance = decimal(risk.marginBalance());
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
        return value.stripTrailingZeros().toPlainString();
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
}
