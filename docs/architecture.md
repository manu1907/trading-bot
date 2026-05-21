# Trading Bot Architecture

The bot is organized around one application core and pluggable runtime modules.

## Modules

- `bot-core`: owns application startup, config loading, runtime identity,
  exchange/strategy contracts, validation, reload policy, and orchestration.
- `bot-exchange-binance`: owns Binance-specific config binding, REST/WebSocket
  behavior, metadata parsing, and Binance lifecycle.
- `bot-strategy-lfa`: owns the LFA strategy implementation and its strategy
  config once the strategy contract is complete.

Provider modules and strategy modules are discovered as Spring beans through
core contracts. Core should not import provider-specific config or implementation
types.

## Runtime Target

A live runtime target is identified by:

```text
provider/environment/account/market
```

Example:

```text
binance/demo/main/usdm_futures
```

Spring profiles describe application mode: `live` or `backtest`. Exchange
environment describes venue/account context: `demo` or `real`.

## Config Precedence

For live mode:

```text
runtime override > environment/provider config > default catalog config
```

For backtest mode, only the backtest config is used.

Runtime target changes are immutable for a running process unless a future
supervisor explicitly creates or stops runtime instances.

## Demo And Real

Demo and real use the same execution engine. The allowed differences are:

- active target
- credentials/secrets
- endpoint/environment config
- deployment namespace
- account limits

There should be no separate toy execution code path for real-readiness work.

## Quality Gate

Before a commit:

- Relevant unit and integration tests must pass.
- Warnings should be corrected, not hidden.
- Tests should use explicit inputs and expected outputs.
- Comments should explain non-obvious design choices only.
