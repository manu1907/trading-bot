package io.github.manu.exchange.binance;

import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class BinanceReactorNettyWebSocketTransport implements BinanceWebSocketTransport {

    private final WebSocketClient webSocketClient;
    private final Clock clock;

    BinanceReactorNettyWebSocketTransport() {
        this(new ReactorNettyWebSocketClient(), Clock.systemUTC());
    }

    BinanceReactorNettyWebSocketTransport(WebSocketClient webSocketClient, Clock clock) {
        this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public BinanceWebSocketConnection connect(
            BinanceWebSocketConnectionPlan plan,
            BinanceWebSocketListener listener
    ) {
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(listener, "listener is required");

        Sinks.Empty<Void> closeSignal = Sinks.empty();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean closeNotified = new AtomicBoolean(false);
        Mono<Void> socketFlow = webSocketClient.execute(plan.uri(), session -> {
            listener.onOpen(plan);
            Mono<Void> inbound = session.receive()
                    .filter(message -> WebSocketMessage.Type.TEXT.equals(message.getType()))
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(listener::onText)
                    .then();
            return Mono.firstWithSignal(inbound, closeSignal.asMono());
        }).doOnError(listener::onError)
                .doFinally(ignored -> notifyClosed(listener, closeNotified));

        subscription.set(socketFlow.subscribe());
        return new BinanceWebSocketConnection(plan, clock.instant(), () -> {
            closeSignal.tryEmitEmpty();
            Disposable current = subscription.get();
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
        });
    }

    private static void notifyClosed(BinanceWebSocketListener listener, AtomicBoolean closeNotified) {
        if (closeNotified.compareAndSet(false, true)) {
            listener.onClose();
        }
    }
}
