package io.github.manu.exchange.binance;

import java.time.Duration;

interface BinanceWebSocketReconnectScheduler {

    BinanceScheduledTask schedule(Duration delay, Runnable task);
}
