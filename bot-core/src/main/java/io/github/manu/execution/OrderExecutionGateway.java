package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;

import java.util.concurrent.CompletableFuture;

public interface OrderExecutionGateway {

    boolean supports(String provider, String environment, String account, String market);

    CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command);
}
