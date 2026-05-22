package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceRequestSignerTest {

    @Test
    void signs_hmac_sha256_payload_using_explicit_fixture() {
        String payload = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1"
                + "&price=0.1&recvWindow=5000&timestamp=1499827319559";
        String secret = "test-secret";

        String signature = BinanceRequestSigner.sign(payload, secret, "HMAC_SHA256");

        assertThat(signature).isEqualTo("124db24caf194fd7731e272b8cd78354823df3f4cfc33f7430bf18c06eac17d6");
    }

    @Test
    void signs_rsa_sha256_payload_with_pkcs8_private_key() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String payload = "symbol=BTCUSDT&side=SELL&type=LIMIT&timestamp=1668481559918&recvWindow=5000";

        String signature = BinanceRequestSigner.sign(payload, privateKeyPem(keyPair), "RSA_SHA256");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature))).isTrue();
    }

    @Test
    void signs_ed25519_payload_with_pkcs8_private_key() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String payload = "symbol=BTCUSDT&side=SELL&type=LIMIT&timestamp=1668481559918&recvWindow=5000";

        String signature = BinanceRequestSigner.sign(payload, privateKeyPem(keyPair), "ED25519");

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature))).isTrue();
    }

    @Test
    void rejects_unsupported_signature_algorithm() {
        assertThatThrownBy(() -> BinanceRequestSigner.sign("payload", "secret", "ECDSA_SHA256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Binance signature algorithm");
    }

    private String privateKeyPem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
    }
}
