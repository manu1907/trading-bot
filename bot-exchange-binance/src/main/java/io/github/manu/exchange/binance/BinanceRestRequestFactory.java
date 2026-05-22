package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class BinanceRestRequestFactory {

    private static final String MILLISECONDS = "MILLISECONDS";
    private static final String MICROSECONDS = "MICROSECONDS";

    private final BinanceProperties.Rest rest;
    private final Clock clock;
    private final long serverTimeOffsetMillis;

    BinanceRestRequestFactory(BinanceProperties.Rest rest, Clock clock, long serverTimeOffsetMillis) {
        this.rest = Objects.requireNonNull(rest, "rest");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverTimeOffsetMillis = serverTimeOffsetMillis;
    }

    URI publicUri(String path, List<BinanceRequestParameter> parameters) {
        return buildUri(path, encode(parameters));
    }

    BinanceSignedRequest signedUri(String path,
                                   List<BinanceRequestParameter> parameters,
                                   String apiSecret) {
        List<BinanceRequestParameter> signedParameters = new ArrayList<>(parameters);
        signedParameters.add(BinanceRequestParameter.of("timestamp", timestamp()));
        signedParameters.add(BinanceRequestParameter.of("recvWindow", rest.recvWindowMillis()));

        String payload = encode(signedParameters);
        String signature = BinanceRequestSigner.sign(payload, apiSecret, rest.signatureAlgorithm());
        String signedQuery = payload + "&signature=" + signature;
        return new BinanceSignedRequest(buildUri(path, signedQuery), payload, signature);
    }

    private long timestamp() {
        Instant instant = clock.instant().plusMillis(serverTimeOffsetMillis);
        if (MILLISECONDS.equals(rest.timestampUnit())) {
            return instant.toEpochMilli();
        }
        if (MICROSECONDS.equals(rest.timestampUnit())) {
            return Math.addExact(
                    Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                    instant.getNano() / 1_000L
            );
        }
        throw new IllegalArgumentException("Unsupported Binance timestamp unit: " + rest.timestampUnit());
    }

    private URI buildUri(String path, String query) {
        String baseUrl = rest.baseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String suffix = query.isBlank() ? "" : "?" + query;
        return URI.create(normalizedBase + normalizedPath + suffix);
    }

    private String encode(List<BinanceRequestParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        return parameters.stream()
                .map(parameter -> encode(parameter.name()) + "=" + encode(parameter.value()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }
}
