package io.github.manu.exchange.binance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class BinanceRequestSigner {

    private static final String HMAC_SHA256 = "HMAC_SHA256";
    private static final String JCA_HMAC_SHA256 = "HmacSHA256";

    private BinanceRequestSigner() {
    }

    static String sign(String payload, String apiSecret, String signatureAlgorithm) {
        if (!HMAC_SHA256.equals(signatureAlgorithm)) {
            throw new IllegalArgumentException("Unsupported Binance signature algorithm: " + signatureAlgorithm);
        }
        if (payload == null) {
            throw new IllegalArgumentException("Signed payload is required");
        }
        if (apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalArgumentException("API secret is required");
        }

        try {
            Mac mac = Mac.getInstance(JCA_HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), JCA_HMAC_SHA256);
            mac.init(secretKey);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Binance request", e);
        }
    }
}
