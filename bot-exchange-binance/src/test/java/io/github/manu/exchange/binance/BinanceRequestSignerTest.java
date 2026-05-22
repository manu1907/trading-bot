package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

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
    void rejects_unsupported_signature_algorithm() {
        assertThatThrownBy(() -> BinanceRequestSigner.sign("payload", "secret", "RSA_SHA256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Binance signature algorithm");
    }
}
