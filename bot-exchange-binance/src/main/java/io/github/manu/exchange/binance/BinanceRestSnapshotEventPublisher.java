package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class BinanceRestSnapshotEventPublisher {

    private final BinanceRestSnapshotEventMapper mapper;
    private final Context context;
    private final TradingEventBus eventBus;
    private final Clock clock;

    BinanceRestSnapshotEventPublisher(
            BinanceRestSnapshotEventMapper mapper,
            Context context,
            TradingEventBus eventBus,
            Clock clock
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.context = Objects.requireNonNull(context, "context").normalize();
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CompletableFuture<List<PublishedTradingEvent>> publishOpenOrders(List<BinanceOrderResult> orders) {
        return publishEnvelopes(mapOpenOrders(orders));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesBalances(List<BinanceFuturesBalance> balances) {
        return publishEnvelopes(mapFuturesBalances(balances));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesAccount(BinanceFuturesAccountSnapshot snapshot) {
        return publishEnvelopes(mapFuturesAccount(snapshot));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesPositions(
            List<BinanceFuturesPositionSnapshot> positions
    ) {
        return publishEnvelopes(mapFuturesPositions(positions));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishCrossMarginAccount(
            BinanceCrossMarginAccountSnapshot snapshot
    ) {
        return publishEnvelopes(mapCrossMarginAccount(snapshot));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishIsolatedMarginAccount(
            BinanceIsolatedMarginAccountSnapshot snapshot
    ) {
        return publishEnvelopes(mapIsolatedMarginAccount(snapshot));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishOptionsMarginAccount(
            BinanceOptionsMarginAccountSnapshot snapshot
    ) {
        return publishEnvelopes(mapOptionsMarginAccount(snapshot));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishOptionsPositions(
            List<BinanceOptionsPositionSnapshot> positions
    ) {
        return publishEnvelopes(mapOptionsPositions(positions));
    }

    List<TradingEventEnvelope<OrderResultEvent>> mapOpenOrders(List<BinanceOrderResult> orders) {
        return mapper.openOrders(orders, mapperContext());
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> mapFuturesBalances(List<BinanceFuturesBalance> balances) {
        return mapper.futuresBalances(balances, mapperContext());
    }

    List<TradingEventEnvelope<?>> mapFuturesAccount(BinanceFuturesAccountSnapshot snapshot) {
        return mapper.futuresAccount(snapshot, mapperContext());
    }

    List<TradingEventEnvelope<PositionUpdateEvent>> mapFuturesPositions(
            List<BinanceFuturesPositionSnapshot> positions
    ) {
        return mapper.futuresPositions(positions, mapperContext());
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> mapCrossMarginAccount(BinanceCrossMarginAccountSnapshot snapshot) {
        return mapper.crossMarginAccount(snapshot, mapperContext());
    }

    List<TradingEventEnvelope<BalanceUpdateEvent>> mapIsolatedMarginAccount(
            BinanceIsolatedMarginAccountSnapshot snapshot
    ) {
        return mapper.isolatedMarginAccount(snapshot, mapperContext());
    }

    List<TradingEventEnvelope<?>> mapOptionsMarginAccount(BinanceOptionsMarginAccountSnapshot snapshot) {
        return mapper.optionsMarginAccount(snapshot, mapperContext());
    }

    List<TradingEventEnvelope<PositionUpdateEvent>> mapOptionsPositions(
            List<BinanceOptionsPositionSnapshot> positions
    ) {
        return mapper.optionsPositions(positions, mapperContext());
    }

    CompletableFuture<List<PublishedTradingEvent>> publishEnvelopes(
            List<? extends TradingEventEnvelope<?>> envelopes
    ) {
        List<CompletableFuture<PublishedTradingEvent>> futures = new ArrayList<>(envelopes.size());
        for (TradingEventEnvelope<?> envelope : envelopes) {
            futures.add(eventBus.publish(envelope));
        }
        CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all)
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }

    private BinanceRestSnapshotEventMapper.Context mapperContext() {
        return new BinanceRestSnapshotEventMapper.Context(
                context.provider(),
                context.environment(),
                context.account(),
                context.market(),
                clock.instant()
        );
    }

    record Context(String provider, String environment, String account, String market) {

        private Context normalize() {
            return new Context(
                    require(provider, "provider"),
                    require(environment, "environment"),
                    require(account, "account"),
                    require(market, "market")
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
