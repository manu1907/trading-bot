package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceProperties(
        @NotNull String marketType,
        @Valid @NotNull Credentials credentials,
        @Valid @NotNull Rest rest,
        @Valid @NotNull Websocket websocket,
        @Valid UserDataStream userDataStream
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credentials(
            @NotNull String apiKey,
            @NotNull String apiSecret
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rest(
            @NotNull String baseUrl
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Websocket(
            @NotNull String baseUrl,
            String publicPathPrefix,
            String marketPathPrefix,
            String privatePathPrefix,
            String rawStreamPath,
            String combinedStreamPath,
            Integer maxConnectionLifetimeHours,
            Integer serverPingIntervalSeconds,
            Integer serverPingIntervalMinutes,
            Integer pongTimeoutSeconds,
            Integer pongTimeoutMinutes,
            Integer maxIncomingMessagesPerSecond,
            Integer maxStreamsPerConnection
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserDataStream(
            String mode,
            Integer listenKeyValidityMinutes,
            Integer keepaliveIntervalMinutes
    ) {
    }
}
