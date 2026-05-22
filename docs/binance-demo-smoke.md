# Binance Demo Smoke Test

The Binance demo order lifecycle test is intentionally opt-in. It creates a
passive USD-M `LIMIT GTX` order on `BTCUSDT` in the active demo environment and
then queries and cancels it by client order id.

The Binance demo user-data stream lifecycle test is also opt-in. It starts,
renews, and closes a USD-M listen-key stream in the active demo environment.

The Binance demo server-time smoke test is opt-in and credential-free. It calls
the active demo server-time endpoint and verifies that local offset calculation
works against the configured USD-M demo REST base URL.

The order command validator is intentionally stricter than a raw REST wrapper.
It rejects Binance request collisions before submission, including `priceMatch`
with `price`, `GTD` without `goodTillDate`, `goodTillDate` without `GTD`,
`reduceOnly` on hedge-mode `LONG`/`SHORT` orders, and close-position flags on
non-close-all order types. USD-M and COIN-M futures request configuration also
excludes `selfTradePreventionMode=NONE`; the demo API currently rejects that
mode even though order responses can still report `NONE`.

Run it only with demo credentials:

```bash
./gradlew :bot-exchange-binance:binanceDemoServerTimeSmokeTest
./gradlew :bot-exchange-binance:binanceDemoOrderSmokeTest
./gradlew :bot-exchange-binance:binanceDemoUserDataStreamSmokeTest
```

Required environment variables:

```text
BINANCE_DEMO_API_KEY
BINANCE_DEMO_API_SECRET
```

You can also create a local, ignored `api.env` file at the repository root:

```text
BINANCE_DEMO_API_KEY=...
BINANCE_DEMO_API_SECRET=...
```

Use `api.env.example` as the template. Never commit `api.env`.

In IntelliJ IDEA, create a Gradle run configuration for:

```text
:bot-exchange-binance:binanceDemoOrderSmokeTest
```

Then attach the two environment variables to that run configuration. The test
refuses non-demo active targets before submitting an order.
