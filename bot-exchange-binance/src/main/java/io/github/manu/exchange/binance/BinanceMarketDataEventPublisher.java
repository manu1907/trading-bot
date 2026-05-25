package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

final class BinanceMarketDataEventPublisher implements BinanceWebSocketListener {

    private final BinanceMarketDataEventMapper mapper;
    private final BinanceMarketDataEventMapper.Context context;
    private final TradingEventBus eventBus;
    private final BinanceWebSocketListener delegate;
    private final Clock clock;

    BinanceMarketDataEventPublisher(
            BinanceMarketDataEventMapper mapper,
            BinanceMarketDataEventMapper.Context context,
            TradingEventBus eventBus,
            BinanceWebSocketListener delegate,
            Clock clock
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.context = Objects.requireNonNull(context, "context");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onOpen(BinanceWebSocketConnectionPlan plan) {
        delegate.onOpen(plan);
    }

    @Override
    public void onText(String text) {
        List<TradingEventEnvelope<MarketDataEvent>> envelopes;
        try {
            envelopes = mapper.map(text, context.withReceivedAt(clock.instant()));
        } catch (RuntimeException e) {
            delegate.onError(e);
            return;
        }
        for (TradingEventEnvelope<MarketDataEvent> envelope : envelopes) {
            eventBus.publish(envelope).whenComplete((published, error) -> {
                if (error != null) {
                    delegate.onError(unwrap(error));
                }
            });
        }
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }

    @Override
    public void onClose() {
        delegate.onClose();
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return error;
    }
}
