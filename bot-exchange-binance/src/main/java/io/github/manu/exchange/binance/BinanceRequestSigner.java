package io.github.manu.exchange.binance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

final class BinanceRequestSigner {

    private static final String HMAC_SHA256 = "HMAC_SHA256";
    private static final String RSA_SHA256 = "RSA_SHA256";
    private static final String ED25519 = "ED25519";
    private static final String JCA_HMAC_SHA256 = "HmacSHA256";
    private static final Map<String, BiFunction<String, String, String>> SIGNERS = Map.of(
            HMAC_SHA256, BinanceRequestSigner::signHmacSha256,
            RSA_SHA256, BinanceRequestSigner::signRsaSha256,
            ED25519, BinanceRequestSigner::signEd25519
    );

    private BinanceRequestSigner() {
    }

    static String sign(String payload, String privateCredential, String signatureAlgorithm) {
        BiFunction<String, String, String> signer = SIGNERS.get(signatureAlgorithm);
        if (signer == null) {
            throw new IllegalArgumentException("Unsupported Binance signature algorithm: " + signatureAlgorithm);
        }
        if (payload == null) {
            throw new IllegalArgumentException("Signed payload is required");
        }
        if (privateCredential == null || privateCredential.isBlank()) {
            throw new IllegalArgumentException("Private signing credential is required");
        }

        return signer.apply(payload, privateCredential);
    }

    private static String signHmacSha256(String payload, String apiSecret) {
        try {
            Mac mac = Mac.getInstance(JCA_HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), JCA_HMAC_SHA256);
            mac.init(secretKey);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Binance request", e);
        }
    }

    private static String signRsaSha256(String payload, String privateKeyPem) {
        return signAsymmetric(payload, privateKeyPem, "RSA", "SHA256withRSA");
    }

    private static String signEd25519(String payload, String privateKeyPem) {
        return signAsymmetric(payload, privateKeyPem, "Ed25519", "Ed25519");
    }

    private static String signAsymmetric(String payload,
                                         String privateKeyPem,
                                         String keyAlgorithm,
                                         String signatureAlgorithm) {
        try {
            PrivateKey privateKey = parsePkcs8PrivateKey(privateKeyPem, keyAlgorithm);
            Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Binance request", e);
        }
    }

    private static PrivateKey parsePkcs8PrivateKey(String privateKeyPem, String keyAlgorithm) throws Exception {
        String base64 = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        String normalizedAlgorithm = keyAlgorithm.toUpperCase(Locale.ROOT).equals("ED25519") ? "Ed25519" : keyAlgorithm;
        return KeyFactory.getInstance(normalizedAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }
}
