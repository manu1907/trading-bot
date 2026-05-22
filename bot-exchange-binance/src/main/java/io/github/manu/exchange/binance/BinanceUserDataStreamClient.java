package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class BinanceUserDataStreamClient {

    private static final String LISTEN_KEY = "listen_key";
    private static final String LISTEN_TOKEN = "listen_token";

    private final BinanceProperties binance;
    private final String apiKey;
    private final BinanceRestRequestFactory requestFactory;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final Clock clock;

    BinanceUserDataStreamClient(BinanceProperties binance, String apiKey, Clock clock) {
        this(
                binance,
                apiKey,
                clock,
                new BinanceJdkHttpTransport(
                        Duration.ofMillis(binance.rest().connectTimeoutMillis()),
                        Duration.ofMillis(binance.rest().responseTimeoutMillis())
                ),
                JsonMapperFactory.create()
        );
    }

    BinanceUserDataStreamClient(BinanceProperties binance,
                                String apiKey,
                                Clock clock,
                                BinanceHttpTransport transport,
                                ObjectMapper jsonMapper) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.apiKey = requireText(apiKey, "apiKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestFactory = new BinanceRestRequestFactory(binance.rest(), clock, 0);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    BinanceUserDataStreamSession start() {
        BinanceProperties.UserDataStream userData = requireUserData();
        BinanceHttpResponse response = send(userData.startPath(), "POST");
        String streamId = streamId(readJson(response), userData.mode());
        Instant startedAt = clock.instant();
        return new BinanceUserDataStreamSession(
                userData.mode(),
                streamId,
                startedAt,
                startedAt.plus(validity(userData)),
                startedAt.plus(renewalInterval(userData))
        );
    }

    BinanceUserDataStreamSession keepAlive(String streamId) {
        BinanceProperties.UserDataStream userData = requireUserData();
        requireText(streamId, "streamId");
        BinanceHttpResponse response = send(userData.keepalivePath(), "PUT");
        String renewedStreamId = streamId(readJson(response), userData.mode());
        Instant renewedAt = clock.instant();
        return new BinanceUserDataStreamSession(
                userData.mode(),
                renewedStreamId,
                renewedAt,
                renewedAt.plus(validity(userData)),
                renewedAt.plus(renewalInterval(userData))
        );
    }

    void close(String streamId) {
        BinanceProperties.UserDataStream userData = requireUserData();
        requireText(streamId, "streamId");
        send(userData.closePath(), "DELETE");
    }

    private BinanceHttpResponse send(String path, String method) {
        requireText(path, "userDataStream path");
        BinanceSignedRequest request = unsignedRequest(path);
        try {
            BinanceHttpResponse response = transport.send(request, method, apiKey, binance.rest().apiKeyHeader());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw toApiException(response);
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to communicate with Binance user data stream API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while communicating with Binance user data stream API", e);
        }
    }

    private BinanceSignedRequest unsignedRequest(String path) {
        return new BinanceSignedRequest(requestFactory.publicUri(path, List.of()), "", "");
    }

    private BinanceApiException toApiException(BinanceHttpResponse response) {
        JsonNode root = readJson(response);
        Integer exchangeCode = root.hasNonNull("code") ? root.required("code").asInt() : null;
        String message = root.hasNonNull("msg") ? root.required("msg").asString() : "HTTP " + response.statusCode();
        return new BinanceApiException(response.statusCode(), exchangeCode, message);
    }

    private JsonNode readJson(BinanceHttpResponse response) {
        return jsonMapper.readTree(response.body());
    }

    private String streamId(JsonNode node, String mode) {
        String field = streamIdField(mode);
        if (!node.hasNonNull(field)) {
            throw new IllegalStateException("Binance user data stream response missing " + field);
        }
        return requireText(node.required(field).asString(), field);
    }

    private String streamIdField(String mode) {
        return switch (mode) {
            case LISTEN_KEY -> "listenKey";
            case LISTEN_TOKEN -> "listenToken";
            default -> throw new IllegalArgumentException("Unsupported Binance user data stream mode: " + mode);
        };
    }

    private Duration validity(BinanceProperties.UserDataStream userData) {
        return Duration.ofMinutes(requirePositive(userData.validityMinutes(), "userDataStream validityMinutes"));
    }

    private Duration renewalInterval(BinanceProperties.UserDataStream userData) {
        return Duration.ofMinutes(requirePositive(userData.renewalIntervalMinutes(), "userDataStream renewalIntervalMinutes"));
    }

    private BinanceProperties.UserDataStream requireUserData() {
        BinanceProperties.UserDataStream userData = binance.userDataStream();
        if (userData == null) {
            throw new IllegalStateException("Binance user data stream config is required");
        }
        requireText(userData.mode(), "userDataStream mode");
        return userData;
    }

    private int requirePositive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
