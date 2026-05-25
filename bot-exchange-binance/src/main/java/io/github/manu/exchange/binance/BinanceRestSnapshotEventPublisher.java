package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
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
        return publish(mapper.openOrders(orders, mapperContext()));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesBalances(List<BinanceFuturesBalance> balances) {
        return publish(mapper.futuresBalances(balances, mapperContext()));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesAccount(BinanceFuturesAccountSnapshot snapshot) {
        return publish(mapper.futuresAccount(snapshot, mapperContext()));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishFuturesPositions(
            List<BinanceFuturesPositionSnapshot> positions
    ) {
        return publish(mapper.futuresPositions(positions, mapperContext()));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishCrossMarginAccount(
            BinanceCrossMarginAccountSnapshot snapshot
    ) {
        return publish(mapper.crossMarginAccount(snapshot, mapperContext()));
    }

    CompletableFuture<List<PublishedTradingEvent>> publishIsolatedMarginAccount(
            BinanceIsolatedMarginAccountSnapshot snapshot
    ) {
        return publish(mapper.isolatedMarginAccount(snapshot, mapperContext()));
    }

    private CompletableFuture<List<PublishedTradingEvent>> publish(
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
