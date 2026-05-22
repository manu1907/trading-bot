# Binance Demo Smoke Test

The Binance demo order lifecycle test is intentionally opt-in. It creates a
passive USD-M `LIMIT GTX` order on `BTCUSDT` in the active demo environment and
cancels it by client order id.

Run it only with demo credentials:

```bash
./gradlew :bot-exchange-binance:binanceDemoOrderSmokeTest
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
