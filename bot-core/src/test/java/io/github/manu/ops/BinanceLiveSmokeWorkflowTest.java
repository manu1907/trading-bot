package io.github.manu.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceLiveSmokeWorkflowTest {

    private static final String WORKFLOW = ".github/workflows/smoke-binance-live.yml";

    @Test
    void workflow_is_manual_environment_gated_and_verifies_source_ci() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .doesNotContain("\n  push:\n")
                .doesNotContain("\n  pull_request:\n")
                .contains("environment: ${{ inputs.environment }}")
                .contains("contents: read")
                .contains("actions: read")
                .contains("commit_sha must be a full 40-character lowercase git SHA")
                .contains("Verify source CI passed")
                .contains("Security workflow has not passed for ${{ steps.target.outputs.sha }}");
    }

    @Test
    void workflow_maps_demo_and_real_targets_without_changing_test_code() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("environment=\"demo\"")
                .contains("contract_path=\"ops/google-cloud/demo-usdm-futures-deployment.yml\"")
                .contains("environment=\"real\"")
                .contains("contract_path=\"ops/google-cloud/real-usdm-futures-deployment.yml\"")
                .contains("cat > config/active.json <<JSON")
                .contains("\"provider\": \"${{ steps.target.outputs.provider }}\"")
                .contains("\"environment\": \"${{ steps.target.outputs.environment }}\"")
                .contains("\"account\": \"${{ steps.target.outputs.account }}\"")
                .contains("\"market\": \"${{ steps.target.outputs.market }}\"");
    }

    @Test
    void workflow_runs_all_existing_binance_live_smoke_tasks_and_uploads_evidence() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains(":bot-exchange-binance:binanceLiveServerTimeSmokeTest")
                .contains(":bot-exchange-binance:binanceLiveOrderSmokeTest")
                .contains(":bot-exchange-binance:binanceLiveUserDataStreamSmokeTest")
                .contains(":bot-exchange-binance:binanceLiveWebSocketSmokeTest")
                .contains("server_time_smoke")
                .contains("order_endpoint_smoke")
                .contains("user_data_stream_smoke")
                .contains("market_data_websocket_smoke")
                .contains("actions/upload-artifact@v5")
                .contains("trading-bot-binance-live-smoke-${{ inputs.environment }}-${{ steps.target.outputs.sha }}");
    }

    @Test
    void workflow_requires_explicit_real_confirmation_and_uses_environment_scoped_secrets() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .contains("confirm_real_smoke")
                .contains("RUN_REAL_BINANCE_SMOKE")
                .contains("Real Binance live smoke requires confirm_real_smoke=RUN_REAL_BINANCE_SMOKE")
                .contains("-Dbinance.live.smoke.allowReal=true")
                .contains("BINANCE_DEMO_API_KEY: ${{ secrets.BINANCE_DEMO_API_KEY }}")
                .contains("BINANCE_DEMO_API_SECRET: ${{ secrets.BINANCE_DEMO_API_SECRET }}")
                .contains("BINANCE_REAL_API_KEY: ${{ secrets.BINANCE_REAL_API_KEY }}")
                .contains("BINANCE_REAL_API_SECRET: ${{ secrets.BINANCE_REAL_API_SECRET }}");
    }

    @Test
    void workflow_does_not_inline_secret_values() throws IOException {
        String workflow = Files.readString(resolve(WORKFLOW));

        assertThat(workflow)
                .doesNotContain("-----BEGIN")
                .doesNotContain("jdbc:postgresql://")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("xoxb-")
                .doesNotContain("ghp_")
                .doesNotContain("AIza");
    }

    private Path resolve(String path) {
        Path cwd = Path.of(path);
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path parent = Path.of("..").resolve(path).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }
}
