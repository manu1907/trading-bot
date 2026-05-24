# Binance Live Smoke Tests

The Binance live order lifecycle test is intentionally opt-in. It uses the
active Binance target. In demo it creates a passive USD-M `LIMIT GTX` order on
`BTCUSDT`, then queries and cancels it by client order id. In real it refuses
to run unless `-Dbinance.live.smoke.allowReal=true` is also provided.

The Binance live user-data stream lifecycle test is also opt-in. It starts,
renews, and closes the configured listen-key stream for the active target.

The Binance live server-time smoke test is opt-in and credential-free. It calls
the active server-time endpoint and verifies that local offset calculation works
against the configured REST base URL.

The order command validator is intentionally stricter than a raw REST wrapper.
It rejects Binance request collisions before submission, including `priceMatch`
with `price`, `GTD` without `goodTillDate`, `goodTillDate` without `GTD`,
`reduceOnly` on hedge-mode `LONG`/`SHORT` orders, and close-position flags on
non-close-all order types. USD-M and COIN-M futures request configuration also
excludes `selfTradePreventionMode=NONE`; the demo API currently rejects that
mode even though order responses can still report `NONE`.

Run against the active target:

```bash
./gradlew :bot-exchange-binance:binanceLiveServerTimeSmokeTest
./gradlew :bot-exchange-binance:binanceLiveOrderSmokeTest
./gradlew :bot-exchange-binance:binanceLiveUserDataStreamSmokeTest
./gradlew :bot-exchange-binance:binanceLiveWebSocketSmokeTest
```

Demo credential environment variables:

```text
BINANCE_DEMO_API_KEY
BINANCE_DEMO_API_SECRET
```

Real credential environment variables:

```text
BINANCE_REAL_API_KEY
BINANCE_REAL_API_SECRET
```

You can also create a local, ignored `api.env` file at the repository root:

```text
BINANCE_DEMO_API_KEY=...
BINANCE_DEMO_API_SECRET=...
```

Use `api.env.example` as the template. Never commit `api.env`.

In IntelliJ IDEA, create a Gradle run configuration for:

```text
:bot-exchange-binance:binanceLiveOrderSmokeTest
```

Then attach the two environment variables to that run configuration. The test
refuses real active targets before submitting an order unless the explicit real
guard is enabled.
