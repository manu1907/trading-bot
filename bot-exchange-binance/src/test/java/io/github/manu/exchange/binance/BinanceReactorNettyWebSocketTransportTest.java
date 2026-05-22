package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceReactorNettyWebSocketTransportTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:01Z"),
            ZoneOffset.UTC
    );

    @Test
    void opens_planned_uri_and_forwards_text_messages_until_closed() {
        FakeSpringWebSocketClient springClient = new FakeSpringWebSocketClient();
        BinanceReactorNettyWebSocketTransport transport = new BinanceReactorNettyWebSocketTransport(
                springClient,
                FIXED_CLOCK
        );
        RecordingWebSocketListener listener = new RecordingWebSocketListener();
        BinanceWebSocketConnectionPlan plan = plan();

        BinanceWebSocketConnection connection = transport.connect(plan, listener);
        springClient.emitText("{\"e\":\"aggTrade\"}");

        assertThat(springClient.connectedUri).isEqualTo(plan.uri());
        assertThat(connection.openedAt()).isEqualTo(Instant.parse("2026-05-22T20:00:01Z"));
        assertThat(listener.openedPlans).containsExactly(plan);
        assertThat(listener.textMessages).containsExactly("{\"e\":\"aggTrade\"}");
        assertThat(listener.errors).isEmpty();

        connection.close();
        connection.close();
        springClient.emitText("{\"e\":\"ignored\"}");

        assertThat(connection.isClosed()).isTrue();
        assertThat(listener.closeCount).hasValue(1);
        assertThat(listener.textMessages).containsExactly("{\"e\":\"aggTrade\"}");
    }

    private BinanceWebSocketConnectionPlan plan() {
        Instant createdAt = Instant.parse("2026-05-22T20:00:00Z");
        return new BinanceWebSocketConnectionPlan(
                URI.create("wss://fstream.binance.com/market/stream?streams=btcusdt@aggTrade"),
                BinanceWebSocketMode.COMBINED,
                BinanceWebSocketRoute.MARKET,
                List.of("btcusdt@aggTrade"),
                createdAt,
                createdAt.plus(Duration.ofHours(24)),
                createdAt.plus(Duration.ofHours(23).plusMinutes(50)),
                Duration.ofMinutes(3),
                Duration.ofMinutes(10),
                10
        );
    }

    private static final class FakeSpringWebSocketClient implements WebSocketClient {

        private final FakeWebSocketSession session = new FakeWebSocketSession();
        private URI connectedUri;

        @Override
        public Mono<Void> execute(URI url, WebSocketHandler handler) {
            connectedUri = url;
            return handler.handle(session);
        }

        @Override
        public Mono<Void> execute(URI url, HttpHeaders headers, WebSocketHandler handler) {
            return execute(url, handler);
        }

        private void emitText(String payload) {
            session.emitText(payload);
        }
    }

    private static final class FakeWebSocketSession implements WebSocketSession {

        private final DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
        private final Sinks.Many<WebSocketMessage> inbound = Sinks.many().multicast().directBestEffort();

        @Override
        public String getId() {
            return "fake-binance-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            throw new UnsupportedOperationException("Handshake info is not used by this test");
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return dataBufferFactory;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return inbound.asFlux();
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Mono.empty();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            inbound.tryEmitComplete();
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.empty();
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return new WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    dataBufferFactory.wrap(payload.getBytes(StandardCharsets.UTF_8))
            );
        }

        @Override
        public WebSocketMessage binaryMessage(
                java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory
        ) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(dataBufferFactory));
        }

        @Override
        public WebSocketMessage pingMessage(
                java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory
        ) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(dataBufferFactory));
        }

        @Override
        public WebSocketMessage pongMessage(
                java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory
        ) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(dataBufferFactory));
        }

        private void emitText(String payload) {
            inbound.tryEmitNext(textMessage(payload));
        }
    }

    private static final class RecordingWebSocketListener implements BinanceWebSocketListener {

        private final List<BinanceWebSocketConnectionPlan> openedPlans = new ArrayList<>();
        private final List<String> textMessages = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            openedPlans.add(plan);
        }

        @Override
        public void onText(String text) {
            textMessages.add(text);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        @Override
        public void onClose() {
            closeCount.incrementAndGet();
        }
    }
}
