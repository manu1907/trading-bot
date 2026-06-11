package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandPositionSide;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandTimeInForce;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.StrategySignalEvent;
import io.github.manu.events.v1.StrategySignalType;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.messaging.TradingEventHandler;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationTargetConfidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class StrategySignalPlanner implements TradingEventHandler {

    private static final String FEATURE_ORDER_TYPE = "order_type";
    private static final String FEATURE_TIME_IN_FORCE = "time_in_force";
    private static final String FEATURE_POSITION_SIDE = "position_side";
    private static final String FEATURE_CLIENT_ORDER_ID = "client_order_id";
    private static final String FEATURE_CLOSE_POSITION = "close_position";

    private final ExecutionProperties properties;
    private final TradingEventBus eventBus;
    private final TradingStateProjection tradingStateProjection;
    private final ReconciliationConfidenceTracker reconciliationConfidenceTracker;
    private final StrategyInstrumentUniverseResolver instrumentUniverseResolver;
    private final Clock clock;

    public StrategySignalPlanner(ExecutionProperties properties, TradingEventBus eventBus) {
        this(properties, eventBus, new TradingStateProjection(), null, Clock.systemUTC());
    }

    public StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection
    ) {
        this(properties, eventBus, tradingStateProjection, null, Clock.systemUTC());
    }

    public StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker
    ) {
        this(properties, eventBus, tradingStateProjection, reconciliationConfidenceTracker, null, Clock.systemUTC());
    }

    public StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver
    ) {
        this(
                properties,
                eventBus,
                tradingStateProjection,
                reconciliationConfidenceTracker,
                instrumentUniverseResolver,
                Clock.systemUTC()
        );
    }

    StrategySignalPlanner(ExecutionProperties properties, TradingEventBus eventBus, Clock clock) {
        this(properties, eventBus, new TradingStateProjection(), null, null, clock);
    }

    StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection,
            Clock clock
    ) {
        this(properties, eventBus, tradingStateProjection, null, null, clock);
    }

    StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            Clock clock
    ) {
        this(properties, eventBus, tradingStateProjection, reconciliationConfidenceTracker, null, clock);
    }

    StrategySignalPlanner(
            ExecutionProperties properties,
            TradingEventBus eventBus,
            TradingStateProjection tradingStateProjection,
            ReconciliationConfidenceTracker reconciliationConfidenceTracker,
            StrategyInstrumentUniverseResolver instrumentUniverseResolver,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.tradingStateProjection = Objects.requireNonNull(tradingStateProjection, "tradingStateProjection");
        this.reconciliationConfidenceTracker = reconciliationConfidenceTracker;
        this.instrumentUniverseResolver = instrumentUniverseResolver;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.STRATEGY_SIGNAL) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected STRATEGY_SIGNAL envelope"));
        }
        return handleSignal((StrategySignalEvent) envelope.value());
    }

    public CompletableFuture<Void> handleSignal(StrategySignalEvent signal) {
        Objects.requireNonNull(signal, "signal");
        return plan(signal)
                .map(order -> eventBus.publish(envelope(order)).thenRun(() -> { }))
                .orElseGet(() -> CompletableFuture.<Void>completedFuture(null));
    }

    Optional<OrderCommandEvent> plan(StrategySignalEvent signal) {
        StrategySignalType signalType = Objects.requireNonNull(signal.getSignalType(), "signalType");
        if (signalType == StrategySignalType.HOLD || signalType == StrategySignalType.CANCEL) {
            return Optional.empty();
        }

        Map<CharSequence, CharSequence> features = signal.getFeatures() == null ? Map.of() : signal.getFeatures();
        String signalId = requireText(value(signal.getSignalId()), "signalId");
        String provider = resolve(signal.getProvider(), properties.signalPlanner().defaults().provider(), "provider");
        String environment = resolve(signal.getEnvironment(), properties.signalPlanner().defaults().environment(), "environment");
        String account = resolve(signal.getAccount(), properties.signalPlanner().defaults().account(), "account");
        String market = resolve(signal.getMarket(), properties.signalPlanner().defaults().market(), "market");
        String symbol = resolve(signal.getSymbol(), properties.signalPlanner().defaults().symbol(), "symbol");
        OrderCommandType orderType = orderType(signal, features);
        String quantity = value(signal.getTargetQuantity());
        String quoteOrderQuantity = quantity == null ? value(signal.getTargetNotional()) : null;
        String price = value(signal.getLimitPrice());
        String stopPrice = value(signal.getStopPrice());
        if (instrumentUniverseBlocked(
                provider,
                environment,
                account,
                market,
                symbol,
                orderType,
                quantity,
                quoteOrderQuantity,
                price
        )) {
            return Optional.empty();
        }
        if (admissionBlocked(provider, environment, account, market, symbol)) {
            return Optional.empty();
        }
        if (orderLimitBlocked(provider, environment, account, market, symbol, quantity, quoteOrderQuantity, price, stopPrice)) {
            return Optional.empty();
        }
        Map<CharSequence, CharSequence> attributes = attributes(
                signal,
                features,
                provider,
                environment,
                account,
                market,
                symbol,
                orderType
        );

        OrderCommandEvent command = OrderCommandEvent.newBuilder()
                .setEventId("order-command:" + signalId)
                .setSchemaVersion(1)
                .setCommandId("cmd:" + signalId)
                .setStrategyId(requireText(value(signal.getStrategyId()), "strategyId"))
                .setProvider(provider)
                .setEnvironment(environment)
                .setAccount(account)
                .setMarket(market)
                .setSymbol(symbol)
                .setAction(OrderCommandAction.NEW)
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(null)
                .setSide(side(signalType))
                .setOrderType(orderType)
                .setPositionSide(positionSide(features).orElse(null))
                .setTimeInForce(timeInForce(orderType, features).orElse(null))
                .setQuantity(quantity)
                .setQuoteOrderQuantity(quoteOrderQuantity)
                .setPrice(price)
                .setStopPrice(stopPrice)
                .setActivationPrice(null)
                .setCallbackRate(null)
                .setReduceOnly(reduceOnly(signalType))
                .setClosePosition(booleanFeature(features, FEATURE_CLOSE_POSITION))
                .setClientOrderId(clientOrderId(signalId, features))
                .setIdempotencyKey("signal:" + signalId)
                .setRequestedAtMicros(Instant.now(clock))
                .setAttributes(attributes)
                .build();
        return Optional.of(command);
    }

    private boolean orderLimitBlocked(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String quantity,
            String quoteOrderQuantity,
            String price,
            String stopPrice
    ) {
        ExecutionProperties.OrderLimit orderLimit = properties.riskGate().orderLimit();
        if (!orderLimit.enabled()) {
            return false;
        }
        EffectiveOrderLimit effectiveLimit = effectiveOrderLimit(
                orderLimit,
                provider,
                environment,
                account,
                market,
                symbol
        );
        DecimalField quantityField = decimalField(quantity);
        DecimalField quoteOrderQuantityField = decimalField(quoteOrderQuantity);
        DecimalField priceField = decimalField(price);
        DecimalField stopPriceField = decimalField(stopPrice);
        if (orderLimit.rejectInvalidNumericFields()
                && List.of(quantityField, quoteOrderQuantityField, priceField, stopPriceField).stream()
                .anyMatch(DecimalField::invalid)) {
            return true;
        }
        if (isNonPositive(quantityField)
                || isNonPositive(quoteOrderQuantityField)
                || isNonPositive(priceField)
                || isNonPositive(stopPriceField)) {
            return true;
        }

        if (!effectiveLimit.action().blocksNewCommands()) {
            return false;
        }
        BigDecimal maxQuantity = decimal(effectiveLimit.maxQuantity());
        if (maxQuantity != null
                && quantityField.value() != null
                && quantityField.value().compareTo(maxQuantity) > 0) {
            return true;
        }
        if (effectiveLimit.maxOpenOrders() != null
                && tradingStateProjection.openOrderStates(provider, environment, account, market, effectiveLimit.symbol()).size()
                >= effectiveLimit.maxOpenOrders().intValue()) {
            return true;
        }
        BigDecimal maxNotional = decimal(effectiveLimit.maxNotional());
        if (maxNotional == null) {
            return false;
        }
        BigDecimal notional = notional(quantityField.value(), quoteOrderQuantityField.value(), priceField.value());
        if (notional == null) {
            return effectiveLimit.rejectUnboundedNotional();
        }
        return notional.compareTo(maxNotional) > 0;
    }

    private EffectiveOrderLimit effectiveOrderLimit(
            ExecutionProperties.OrderLimit orderLimit,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        ExecutionProperties.OrderLimit.TargetLimit selected = null;
        int selectedSpecificity = -1;
        for (ExecutionProperties.OrderLimit.TargetLimit candidate : orderLimit.targetLimits()) {
            int specificity = specificity(candidate, provider, environment, account, market, symbol);
            if (specificity > selectedSpecificity) {
                selected = candidate;
                selectedSpecificity = specificity;
            }
        }
        if (selected == null) {
            return new EffectiveOrderLimit(
                    orderLimit.maxQuantity(),
                    orderLimit.maxNotional(),
                    orderLimit.rejectUnboundedNotional(),
                    orderLimit.maxOpenOrders(),
                    orderLimit.action(),
                    null
            );
        }
        return new EffectiveOrderLimit(
                selected.maxQuantity() == null ? orderLimit.maxQuantity() : selected.maxQuantity(),
                selected.maxNotional() == null ? orderLimit.maxNotional() : selected.maxNotional(),
                selected.rejectUnboundedNotional() == null
                        ? orderLimit.rejectUnboundedNotional()
                        : selected.rejectUnboundedNotional(),
                selected.maxOpenOrders() == null ? orderLimit.maxOpenOrders() : selected.maxOpenOrders(),
                selected.action() == null ? orderLimit.action() : selected.action(),
                value(selected.symbol())
        );
    }

    private int specificity(
            ExecutionProperties.OrderLimit.TargetLimit candidate,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        int specificity = 0;
        specificity = match(candidate.provider(), provider, specificity);
        specificity = match(candidate.environment(), environment, specificity);
        specificity = match(candidate.account(), account, specificity);
        specificity = match(candidate.market(), market, specificity);
        specificity = match(candidate.symbol(), symbol, specificity);
        return specificity;
    }

    private int match(String expected, String actual, int specificity) {
        if (specificity < 0) {
            return -1;
        }
        if (expected == null || expected.isBlank()) {
            return specificity;
        }
        if (actual != null && expected.equalsIgnoreCase(actual)) {
            return specificity + 1;
        }
        return -1;
    }

    private DecimalField decimalField(String rawValue) {
        String value = value(rawValue);
        if (value == null) {
            return new DecimalField(null, false);
        }
        try {
            return new DecimalField(new BigDecimal(value), false);
        } catch (NumberFormatException e) {
            return new DecimalField(null, true);
        }
    }

    private BigDecimal decimal(String rawValue) {
        String value = value(rawValue);
        return value == null ? null : new BigDecimal(value);
    }

    private boolean isNonPositive(DecimalField field) {
        return field.value() != null && field.value().compareTo(BigDecimal.ZERO) <= 0;
    }

    private BigDecimal notional(BigDecimal quantity, BigDecimal quoteOrderQuantity, BigDecimal price) {
        if (quoteOrderQuantity != null) {
            return quoteOrderQuantity;
        }
        if (quantity == null || price == null) {
            return null;
        }
        return quantity.multiply(price);
    }

    private boolean admissionBlocked(
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        return tradingStateProjection.symbolPaused(provider, environment, account, market, symbol, Instant.now(clock))
                || reconciliationBlocked(provider, environment, account, market)
                || tradingStateProjection.hasExternalOrderInterventions(provider, environment, account, market)
                || tradingStateProjection.hasExternalPositionInterventions(provider, environment, account, market)
                || tradingStateProjection.hasUnknownOrderStatuses(provider, environment, account, market)
                || tradingStateProjection.hasUnresolvedOrderCommands(provider, environment, account, market);
    }

    private boolean instrumentUniverseBlocked(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            OrderCommandType orderType,
            String quantity,
            String quoteOrderQuantity,
            String price
    ) {
        ExecutionProperties.SignalPlanner.InstrumentUniverse universe =
                properties.signalPlanner().instrumentUniverse();
        if (!universe.enabled()) {
            return false;
        }
        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        if (universe.excludedSymbols().contains(normalizedSymbol)) {
            return true;
        }
        if (universe.requireIncludedSymbol() && !universe.includedSymbols().contains(normalizedSymbol)) {
            return true;
        }
        if (instrumentUniverseResolver != null
                && !instrumentUniverseResolver.eligible(
                        universe,
                        provider,
                        environment,
                        account,
                        market,
                        symbol,
                        orderType)) {
            return true;
        }
        ExecutionProperties.SignalPlanner.SymbolPolicy policy =
                selectedSymbolPolicy(universe, provider, environment, account, market, normalizedSymbol);
        if (policy == null) {
            return universe.requirePromotionReady();
        }
        if (universe.requireSymbolEnabled() && !Boolean.TRUE.equals(policy.enabled())) {
            return true;
        }
        if (universe.requirePromotionReady() && !Boolean.TRUE.equals(policy.promotionReady())) {
            return true;
        }
        BigDecimal maxOrderNotional = decimal(policy.maxOrderNotional());
        if (maxOrderNotional == null) {
            return false;
        }
        BigDecimal orderNotional = notional(
                decimalField(quantity).value(),
                decimalField(quoteOrderQuantity).value(),
                decimalField(price).value()
        );
        return orderNotional == null || orderNotional.compareTo(maxOrderNotional) > 0;
    }

    private ExecutionProperties.SignalPlanner.SymbolPolicy selectedSymbolPolicy(
            ExecutionProperties.SignalPlanner.InstrumentUniverse universe,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        ExecutionProperties.SignalPlanner.SymbolPolicy selected = null;
        int selectedSpecificity = -1;
        for (ExecutionProperties.SignalPlanner.SymbolPolicy candidate : universe.symbolPolicies()) {
            int specificity = specificity(candidate, provider, environment, account, market, symbol);
            if (specificity > selectedSpecificity) {
                selected = candidate;
                selectedSpecificity = specificity;
            }
        }
        return selected;
    }

    private int specificity(
            ExecutionProperties.SignalPlanner.SymbolPolicy candidate,
            String provider,
            String environment,
            String account,
            String market,
            String symbol
    ) {
        int specificity = 0;
        specificity = match(candidate.provider(), provider, specificity);
        specificity = match(candidate.environment(), environment, specificity);
        specificity = match(candidate.account(), account, specificity);
        specificity = match(candidate.market(), market, specificity);
        specificity = match(candidate.symbol(), symbol, specificity);
        return specificity;
    }

    private boolean reconciliationBlocked(
            String provider,
            String environment,
            String account,
            String market
    ) {
        if (reconciliationConfidenceTracker == null || !properties.riskGate().reconciliation().required()) {
            return false;
        }
        ReconciliationTargetConfidence confidence =
                reconciliationConfidenceTracker.targetConfidence(provider, environment, account, market);
        if (confidence.status() == ReconciliationTargetConfidence.Status.NO_OBSERVATIONS) {
            return properties.riskGate().reconciliation().rejectNoObservations();
        }
        if (confidence.status() == ReconciliationTargetConfidence.Status.DEGRADED) {
            return properties.riskGate().reconciliation().rejectDegraded();
        }
        return false;
    }

    private TradingEventEnvelope<OrderCommandEvent> envelope(OrderCommandEvent command) {
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(
                        TradingEventType.ORDER_COMMAND,
                        command.getProvider().toString(),
                        command.getEnvironment().toString(),
                        command.getAccount().toString(),
                        command.getMarket().toString(),
                        command.getSymbol().toString(),
                        command.getClientOrderId().toString()
                ),
                command
        );
    }

    private OrderCommandType orderType(StrategySignalEvent signal, Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_ORDER_TYPE);
        if (configured != null) {
            return OrderCommandType.valueOf(configured.toUpperCase(Locale.ROOT));
        }
        return value(signal.getLimitPrice()) == null ? OrderCommandType.MARKET : OrderCommandType.LIMIT;
    }

    private Optional<OrderCommandTimeInForce> timeInForce(
            OrderCommandType orderType,
            Map<CharSequence, CharSequence> features
    ) {
        String configured = feature(features, FEATURE_TIME_IN_FORCE);
        if (configured != null) {
            return Optional.of(OrderCommandTimeInForce.valueOf(configured.toUpperCase(Locale.ROOT)));
        }
        if (orderType == OrderCommandType.LIMIT) {
            return Optional.of(OrderCommandTimeInForce.valueOf(
                    properties.signalPlanner().defaults().limitOrderTimeInForce().toUpperCase(Locale.ROOT)
            ));
        }
        return Optional.empty();
    }

    private Optional<OrderCommandPositionSide> positionSide(Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_POSITION_SIDE);
        if (configured != null) {
            return Optional.of(OrderCommandPositionSide.valueOf(configured.toUpperCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    private OrderCommandSide side(StrategySignalType signalType) {
        return switch (signalType) {
            case ENTER_LONG, EXIT_SHORT, REDUCE_SHORT -> OrderCommandSide.BUY;
            case ENTER_SHORT, EXIT_LONG, REDUCE_LONG -> OrderCommandSide.SELL;
            case HOLD, CANCEL -> throw new IllegalArgumentException(signalType + " does not map to a new order side");
        };
    }

    private boolean reduceOnly(StrategySignalType signalType) {
        return switch (signalType) {
            case EXIT_LONG, EXIT_SHORT, REDUCE_LONG, REDUCE_SHORT -> true;
            case ENTER_LONG, ENTER_SHORT, HOLD, CANCEL -> false;
        };
    }

    private String clientOrderId(String signalId, Map<CharSequence, CharSequence> features) {
        String configured = feature(features, FEATURE_CLIENT_ORDER_ID);
        if (configured != null) {
            return configured;
        }
        return properties.signalPlanner().defaults().clientOrderIdPrefix() + "-" + signalId;
    }

    private Map<CharSequence, CharSequence> attributes(
            StrategySignalEvent signal,
            Map<CharSequence, CharSequence> features,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            OrderCommandType orderType
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        if (signal.getAttributes() != null) {
            attributes.putAll(signal.getAttributes());
        }
        attributes.putAll(plannerProfileAttributes(
                signal,
                features,
                provider,
                environment,
                account,
                market,
                symbol,
                orderType
        ));
        attributes.putAll(features);
        attributes.put("signal_id", requireText(value(signal.getSignalId()), "signalId"));
        attributes.put("signal_type", signal.getSignalType().name());
        attributes.put("source", "strategy_signal_planner");
        if (signal.getConfidence() != null) {
            attributes.put("signal_confidence", signal.getConfidence().toString());
        }
        if (signal.getTargetNotional() != null) {
            attributes.put("signal_target_notional", signal.getTargetNotional().toString());
        }
        return Map.copyOf(attributes);
    }

    private Map<CharSequence, CharSequence> plannerProfileAttributes(
            StrategySignalEvent signal,
            Map<CharSequence, CharSequence> features,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            OrderCommandType orderType
    ) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        int index = 0;
        for (ExecutionProperties.SignalPlanner.FeatureProfile profile : properties.signalPlanner().featureProfiles()) {
            if (matches(profile, signal, features, provider, environment, account, market, symbol, orderType)) {
                attributes.putAll(profile.attributes());
                attributes.put("planner_feature_profile_index", Integer.toString(index));
            }
            index++;
        }
        return Map.copyOf(attributes);
    }

    private boolean matches(
            ExecutionProperties.SignalPlanner.FeatureProfile profile,
            StrategySignalEvent signal,
            Map<CharSequence, CharSequence> features,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            OrderCommandType orderType
    ) {
        return matches(profile.provider(), provider)
                && matches(profile.environment(), environment)
                && matches(profile.account(), account)
                && matches(profile.market(), market)
                && matches(profile.symbol(), symbol)
                && matches(profile.signalType(), signal.getSignalType().name())
                && matches(profile.orderType(), orderType.name())
                && matchesConfidence(profile.minConfidence(), signal.getConfidence())
                && matchesFeatures(profile.matchFeatures(), features);
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equalsIgnoreCase(actual);
    }

    private boolean matchesConfidence(Double minConfidence, Double confidence) {
        return minConfidence == null || confidence != null && confidence >= minConfidence;
    }

    private boolean matchesFeatures(
            Map<String, String> expectedFeatures,
            Map<CharSequence, CharSequence> actualFeatures
    ) {
        for (Map.Entry<String, String> expected : expectedFeatures.entrySet()) {
            String actual = feature(actualFeatures, expected.getKey());
            if (actual == null || !actual.equalsIgnoreCase(expected.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean booleanFeature(Map<CharSequence, CharSequence> features, String key) {
        return Boolean.parseBoolean(Optional.ofNullable(feature(features, key)).orElse("false"));
    }

    private String feature(Map<CharSequence, CharSequence> features, String key) {
        CharSequence value = features.get(key);
        return value(value);
    }

    private String resolve(CharSequence signalValue, String defaultValue, String field) {
        String value = value(signalValue);
        if (value != null) {
            return value;
        }
        return requireText(defaultValue, field);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private record DecimalField(BigDecimal value, boolean invalid) {
    }

    private record EffectiveOrderLimit(
            String maxQuantity,
            String maxNotional,
            Boolean rejectUnboundedNotional,
            Integer maxOpenOrders,
            ExecutionProperties.InterventionAction action,
            String symbol
    ) {
    }
}
