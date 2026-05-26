package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

final class BinanceRestSnapshotEventMapper {

    List<TradingEventEnvelope<OrderResultEvent>> openOrders(
            List<BinanceOrderResult> orders,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        List<TradingEventEnvelope<OrderResultEvent>> envelopes = new ArrayList<>();
        for (BinanceOrderResult order : Objects.requireNonNull(orders, "orders")) {
            envelopes.add(orderResult(order, normalized));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> futuresBalances(
            List<BinanceFuturesBalance> balances,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        List<TradingEventEnvelope<BalanceUpdateEvent>> envelopes = new ArrayList<>();
        for (BinanceFuturesBalance balance : Objects.requireNonNull(balances, "balances")) {
            envelopes.add(balance(
                    normalized,
                    requireText(balance.asset(), "asset"),
                    decimal(balance.balance()),
                    decimal(balance.crossWalletBalance()),
                    decimal(balance.availableBalance()),
                    null,
                    instant(balance.updateTime(), normalized),
                    attributes("futures_balance",
                            "accountAlias", balance.accountAlias(),
                            "crossUnrealizedPnl", decimal(balance.crossUnrealizedPnl()),
                            "maxWithdrawAmount", decimal(balance.maxWithdrawAmount()),
                            "withdrawAvailable", decimal(balance.withdrawAvailable()),
                            "marginAvailable", string(balance.marginAvailable())
                    )
            ));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<?>> futuresAccount(
            BinanceFuturesAccountSnapshot snapshot,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        BinanceFuturesAccountSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        for (BinanceFuturesAssetSnapshot asset : checked.assets()) {
            envelopes.add(futuresAsset(asset, normalized, checked));
        }
        for (BinanceFuturesPositionSnapshot position : checked.positions()) {
            envelopes.add(position(position, normalized, "futures_account"));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<PositionUpdateEvent>> futuresPositions(
            List<BinanceFuturesPositionSnapshot> positions,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        List<TradingEventEnvelope<PositionUpdateEvent>> envelopes = new ArrayList<>();
        for (BinanceFuturesPositionSnapshot position : Objects.requireNonNull(positions, "positions")) {
            envelopes.add(position(position, normalized, "position_risk"));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> crossMarginAccount(
            BinanceCrossMarginAccountSnapshot snapshot,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        BinanceCrossMarginAccountSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        List<TradingEventEnvelope<BalanceUpdateEvent>> envelopes = new ArrayList<>();
        for (BinanceMarginAssetBalance asset : checked.userAssets()) {
            envelopes.add(balance(
                    normalized,
                    requireText(asset.asset(), "asset"),
                    decimal(asset.netAsset()),
                    null,
                    decimal(asset.free()),
                    null,
                    normalized.observedAt(),
                    attributes("cross_margin_account",
                            "borrowed", decimal(asset.borrowed()),
                            "interest", decimal(asset.interest()),
                            "locked", decimal(asset.locked()),
                            "marginLevel", decimal(checked.marginLevel()),
                            "collateralMarginLevel", decimal(checked.collateralMarginLevel()),
                            "accountType", checked.accountType(),
                            "borrowEnabled", string(checked.borrowEnabled()),
                            "tradeEnabled", string(checked.tradeEnabled())
                    )
            ));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> isolatedMarginAccount(
            BinanceIsolatedMarginAccountSnapshot snapshot,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        BinanceIsolatedMarginAccountSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        List<TradingEventEnvelope<BalanceUpdateEvent>> envelopes = new ArrayList<>();
        for (BinanceIsolatedMarginPairSnapshot pair : checked.assets()) {
            envelopes.add(isolatedMarginAsset(pair.baseAsset(), pair, normalized));
            envelopes.add(isolatedMarginAsset(pair.quoteAsset(), pair, normalized));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<?>> optionsMarginAccount(
            BinanceOptionsMarginAccountSnapshot snapshot,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        BinanceOptionsMarginAccountSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        List<TradingEventEnvelope<?>> envelopes = new ArrayList<>();
        for (BinanceOptionsAccountAsset asset : checked.assets()) {
            envelopes.add(balance(
                    normalized,
                    requireText(asset.asset(), "asset"),
                    decimal(asset.marginBalance()),
                    null,
                    decimal(asset.available()),
                    null,
                    instant(checked.time(), normalized),
                    attributes("options_margin_account",
                            "equity", decimal(asset.equity()),
                            "initialMargin", decimal(asset.initialMargin()),
                            "maintenanceMargin", decimal(asset.maintenanceMargin()),
                            "unrealizedPnl", decimal(asset.unrealizedPnl()),
                            "adjustedEquity", decimal(asset.adjustedEquity()),
                            "canTrade", string(checked.canTrade()),
                            "canDeposit", string(checked.canDeposit()),
                            "canWithdraw", string(checked.canWithdraw()),
                            "reduceOnly", string(checked.reduceOnly()),
                            "tradeGroupId", string(checked.tradeGroupId())
                    )
            ));
        }
        for (BinanceOptionsGreek greek : checked.greeks()) {
            envelopes.add(optionsGreek(greek, normalized, checked));
        }
        return List.copyOf(envelopes);
    }

    List<TradingEventEnvelope<PositionUpdateEvent>> optionsPositions(
            List<BinanceOptionsPositionSnapshot> positions,
            Context context
    ) {
        Context normalized = Objects.requireNonNull(context, "context").normalize();
        List<TradingEventEnvelope<PositionUpdateEvent>> envelopes = new ArrayList<>();
        for (BinanceOptionsPositionSnapshot position : Objects.requireNonNull(positions, "positions")) {
            envelopes.add(optionsPosition(position, normalized));
        }
        return List.copyOf(envelopes);
    }

    private TradingEventEnvelope<OrderResultEvent> orderResult(BinanceOrderResult order, Context context) {
        String symbol = requireText(order.symbol(), "symbol");
        String clientOrderId = requireText(order.clientOrderId(), "clientOrderId");
        OrderResultEvent value = OrderResultEvent.newBuilder()
                .setEventId(eventId(context, "ORDER_RECONCILIATION", symbol, clientOrderId, string(order.orderId()), string(order.updateTime())))
                .setSchemaVersion(1)
                .setCommandId("reconciliation:" + clientOrderId)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setClientOrderId(clientOrderId)
                .setExchangeOrderId(string(order.orderId()))
                .setStatus(status(order.status()))
                .setExchangeStatus(order.status())
                .setPrice(decimal(order.price()))
                .setOriginalQuantity(decimal(order.originalQuantity()))
                .setExecutedQuantity(decimal(order.executedQuantity()))
                .setAveragePrice(decimal(order.averagePrice()))
                .setCumulativeQuote(decimal(order.cumulativeQuote()))
                .setExchangeTransactTimeMicros(instant(order.updateTime(), context))
                .setObservedAtMicros(context.observedAt())
                .setRejectCode(null)
                .setRejectMessage(null)
                .setAttributes(attributes("open_orders",
                        "side", order.side(),
                        "orderType", order.type(),
                        "positionSide", order.positionSide()
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(
                        TradingEventType.ORDER_RESULT,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol,
                        clientOrderId
                ),
                value
        );
    }

    private TradingEventEnvelope<BalanceUpdateEvent> futuresAsset(
            BinanceFuturesAssetSnapshot asset,
            Context context,
            BinanceFuturesAccountSnapshot account
    ) {
        return balance(
                context,
                requireText(asset.asset(), "asset"),
                decimal(asset.walletBalance()),
                decimal(asset.crossWalletBalance()),
                decimal(asset.availableBalance()),
                null,
                instant(asset.updateTime(), context),
                attributes("futures_account",
                        "unrealizedProfit", decimal(asset.unrealizedProfit()),
                        "marginBalance", decimal(asset.marginBalance()),
                        "maintMargin", decimal(asset.maintMargin()),
                        "initialMargin", decimal(asset.initialMargin()),
                        "positionInitialMargin", decimal(asset.positionInitialMargin()),
                        "openOrderInitialMargin", decimal(asset.openOrderInitialMargin()),
                        "crossUnrealizedPnl", decimal(asset.crossUnrealizedPnl()),
                        "maxWithdrawAmount", decimal(asset.maxWithdrawAmount()),
                        "accountAvailableBalance", decimal(account.availableBalance()),
                        "canTrade", string(account.canTrade())
                )
        );
    }

    private TradingEventEnvelope<BalanceUpdateEvent> isolatedMarginAsset(
            BinanceIsolatedMarginAssetBalance asset,
            BinanceIsolatedMarginPairSnapshot pair,
            Context context
    ) {
        return balance(
                context,
                requireText(asset.asset(), "asset"),
                decimal(asset.netAsset()),
                null,
                decimal(asset.free()),
                null,
                context.observedAt(),
                attributes("isolated_margin_account",
                        "symbol", pair.symbol(),
                        "borrowed", decimal(asset.borrowed()),
                        "interest", decimal(asset.interest()),
                        "locked", decimal(asset.locked()),
                        "totalAsset", decimal(asset.totalAsset()),
                        "netAssetOfBtc", decimal(asset.netAssetOfBtc()),
                        "marginLevel", decimal(pair.marginLevel()),
                        "marginLevelStatus", pair.marginLevelStatus(),
                        "tradeEnabled", string(pair.tradeEnabled()),
                        "borrowEnabled", string(asset.borrowEnabled()),
                        "repayEnabled", string(asset.repayEnabled())
                )
        );
    }

    private TradingEventEnvelope<BalanceUpdateEvent> balance(
            Context context,
            String asset,
            String walletBalance,
            String crossWalletBalance,
            String availableBalance,
            String balanceDelta,
            Instant eventTime,
            Map<CharSequence, CharSequence> attributes
    ) {
        BalanceUpdateEvent value = BalanceUpdateEvent.newBuilder()
                .setEventId(eventId(context, "BALANCE_RECONCILIATION", asset, eventTime.toString()))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setAsset(asset)
                .setWalletBalance(walletBalance)
                .setCrossWalletBalance(crossWalletBalance)
                .setAvailableBalance(availableBalance)
                .setBalanceDelta(balanceDelta)
                .setUpdateReason("REST_SNAPSHOT")
                .setEventTimeMicros(eventTime)
                .setAttributes(attributes)
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.BALANCE_UPDATE,
                TradingEventKeys.account(
                        TradingEventType.BALANCE_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market()
                ),
                value
        );
    }

    private TradingEventEnvelope<PositionUpdateEvent> position(
            BinanceFuturesPositionSnapshot position,
            Context context,
            String snapshotType
    ) {
        String symbol = requireText(position.symbol(), "symbol");
        String positionSide = hasText(position.positionSide()) ? position.positionSide() : "BOTH";
        Instant eventTime = instant(position.updateTime(), context);
        PositionUpdateEvent value = PositionUpdateEvent.newBuilder()
                .setEventId(eventId(context, "POSITION_RECONCILIATION", symbol, positionSide, eventTime.toString()))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setPositionSide(positionSide)
                .setPositionAmount(decimal(position.positionAmount()))
                .setEntryPrice(decimal(position.entryPrice()))
                .setMarkPrice(decimal(position.markPrice()))
                .setLiquidationPrice(decimal(position.liquidationPrice()))
                .setUnrealizedPnl(decimal(position.unrealizedProfit()))
                .setLeverage(string(position.leverage()))
                .setMarginType(position.marginType())
                .setIsolatedMargin(decimal(position.isolatedMargin()))
                .setEventTimeMicros(eventTime)
                .setAttributes(attributes(snapshotType,
                        "breakEvenPrice", decimal(position.breakEvenPrice()),
                        "maxQuantity", decimal(position.maxQuantity()),
                        "isolated", string(position.isolated()),
                        "autoAddMargin", string(position.autoAddMargin()),
                        "isolatedWallet", decimal(position.isolatedWallet()),
                        "notional", decimal(position.notional()),
                        "marginAsset", position.marginAsset(),
                        "initialMargin", decimal(position.initialMargin()),
                        "maintMargin", decimal(position.maintMargin()),
                        "positionInitialMargin", decimal(position.positionInitialMargin()),
                        "openOrderInitialMargin", decimal(position.openOrderInitialMargin()),
                        "adl", string(position.adl()),
                        "bidNotional", decimal(position.bidNotional()),
                        "askNotional", decimal(position.askNotional())
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.POSITION_UPDATE,
                TradingEventKeys.symbol(
                        TradingEventType.POSITION_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol
                ),
                value
        );
    }

    private TradingEventEnvelope<RiskUpdateEvent> optionsGreek(
            BinanceOptionsGreek greek,
            Context context,
            BinanceOptionsMarginAccountSnapshot account
    ) {
        String underlying = requireText(greek.underlying(), "underlying");
        Instant eventTime = instant(account.time(), context);
        RiskUpdateEvent value = RiskUpdateEvent.newBuilder()
                .setEventId(eventId(context, "RISK_RECONCILIATION", underlying, eventTime.toString()))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setRiskScope("UNDERLYING")
                .setSymbol(underlying)
                .setUnderlying(underlying)
                .setRiskLevel(null)
                .setDelta(decimal(greek.delta()))
                .setGamma(decimal(greek.gamma()))
                .setTheta(decimal(greek.theta()))
                .setVega(decimal(greek.vega()))
                .setMarginBalance(null)
                .setMaintenanceMargin(null)
                .setEventTimeMicros(eventTime)
                .setTransactionTimeMicros(null)
                .setAttributes(attributes("options_margin_account_greek",
                        "canTrade", string(account.canTrade()),
                        "reduceOnly", string(account.reduceOnly()),
                        "tradeGroupId", string(account.tradeGroupId())
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.RISK_UPDATE,
                TradingEventKeys.symbol(
                        TradingEventType.RISK_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        underlying
                ),
                value
        );
    }

    private TradingEventEnvelope<PositionUpdateEvent> optionsPosition(
            BinanceOptionsPositionSnapshot position,
            Context context
    ) {
        String symbol = requireText(position.symbol(), "symbol");
        String positionSide = hasText(position.side()) ? position.side() : "BOTH";
        Instant eventTime = instant(position.time(), context);
        PositionUpdateEvent value = PositionUpdateEvent.newBuilder()
                .setEventId(eventId(context, "POSITION_RECONCILIATION", symbol, positionSide, eventTime.toString()))
                .setSchemaVersion(1)
                .setProvider(context.provider())
                .setEnvironment(context.environment())
                .setAccount(context.account())
                .setMarket(context.market())
                .setSymbol(symbol)
                .setPositionSide(positionSide)
                .setPositionAmount(decimal(position.quantity()))
                .setEntryPrice(decimal(position.entryPrice()))
                .setMarkPrice(decimal(position.markPrice()))
                .setLiquidationPrice(null)
                .setUnrealizedPnl(decimal(position.unrealizedPnl()))
                .setLeverage(null)
                .setMarginType(null)
                .setIsolatedMargin(null)
                .setEventTimeMicros(eventTime)
                .setAttributes(attributes("options_position",
                        "markValue", decimal(position.markValue()),
                        "strikePrice", decimal(position.strikePrice()),
                        "expiryDate", string(position.expiryDate()),
                        "priceScale", string(position.priceScale()),
                        "quantityScale", string(position.quantityScale()),
                        "optionSide", position.optionSide(),
                        "quoteAsset", position.quoteAsset(),
                        "bidQuantity", decimal(position.bidQuantity()),
                        "askQuantity", decimal(position.askQuantity())
                ))
                .build();
        return TradingEventEnvelope.of(
                TradingEventType.POSITION_UPDATE,
                TradingEventKeys.symbol(
                        TradingEventType.POSITION_UPDATE,
                        context.provider(),
                        context.environment(),
                        context.account(),
                        context.market(),
                        symbol
                ),
                value
        );
    }

    private OrderResultStatus status(String value) {
        if (!hasText(value)) {
            return OrderResultStatus.UNKNOWN;
        }
        return switch (value) {
            case "NEW" -> OrderResultStatus.ACCEPTED;
            case "PARTIALLY_FILLED" -> OrderResultStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderResultStatus.FILLED;
            case "CANCELED" -> OrderResultStatus.CANCELED;
            case "EXPIRED" -> OrderResultStatus.EXPIRED;
            case "REJECTED" -> OrderResultStatus.REJECTED;
            default -> OrderResultStatus.UNKNOWN;
        };
    }

    private Map<CharSequence, CharSequence> attributes(String snapshotType, String... optionalPairs) {
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("source", "rest_snapshot");
        attributes.put("snapshotType", snapshotType);
        if (optionalPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Optional attribute pairs must be key/value pairs");
        }
        for (int index = 0; index < optionalPairs.length; index += 2) {
            String key = optionalPairs[index];
            String value = optionalPairs[index + 1];
            if (hasText(value)) {
                attributes.put(key, value);
            }
        }
        return attributes;
    }

    private Instant instant(Long epochMillis, Context context) {
        return epochMillis == null ? context.observedAt() : Instant.ofEpochMilli(epochMillis);
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String eventId(Context context, String eventType, String... parts) {
        StringJoiner joiner = new StringJoiner(":");
        joiner.add(context.provider());
        joiner.add(context.environment());
        joiner.add(context.account());
        joiner.add(context.market());
        joiner.add(eventType);
        for (String part : parts) {
            joiner.add(part == null || part.isBlank() ? "-" : part.trim());
        }
        return joiner.toString();
    }

    private String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Context(String provider, String environment, String account, String market, Instant observedAt) {

        private Context normalize() {
            return new Context(
                    require(provider, "provider"),
                    require(environment, "environment"),
                    require(account, "account"),
                    require(market, "market"),
                    Objects.requireNonNull(observedAt, "observedAt")
            );
        }

        private static String require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }
}
