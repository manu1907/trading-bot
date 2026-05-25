package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.messaging.TradingEventBus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

final class BinanceUserDataEventPublisher implements BinanceWebSocketListener {

    private final BinanceUserDataEventMapper mapper;
    private final BinanceUserDataEventMapper.Context context;
    private final TradingEventBus eventBus;
    private final BinanceWebSocketListener delegate;

    BinanceUserDataEventPublisher(
            BinanceUserDataEventMapper mapper,
            BinanceUserDataEventMapper.Context context,
            TradingEventBus eventBus,
            BinanceWebSocketListener delegate
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.context = Objects.requireNonNull(context, "context");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onOpen(BinanceWebSocketConnectionPlan plan) {
        delegate.onOpen(plan);
    }

    @Override
    public void onText(String text) {
        List<TradingEventEnvelope<?>> envelopes;
        try {
            envelopes = mapper.map(text, context);
        } catch (RuntimeException e) {
            delegate.onError(e);
            return;
        }
        for (TradingEventEnvelope<?> envelope : envelopes) {
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
