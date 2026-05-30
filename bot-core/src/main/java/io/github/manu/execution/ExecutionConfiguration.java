package io.github.manu.execution;

import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "trading.execution.pipeline", name = "enabled", havingValue = "true")
    OrderExecutionPipeline orderExecutionPipeline(
            OrderRiskGate riskGate,
            TradingEventBus eventBus,
            ObjectProvider<OrderExecutionGateway> gateways,
            OrderExecutionIdempotencyTracker idempotencyTracker
    ) {
        List<OrderExecutionGateway> availableGateways = gateways.orderedStream().toList();
        return new OrderExecutionPipeline(riskGate, eventBus, availableGateways, idempotencyTracker);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.execution.pipeline", name = "enabled", havingValue = "true")
    TradingEventHandlerRegistration orderExecutionPipelineHandler(OrderExecutionPipeline pipeline) {
        return TradingEventHandlerRegistration.liveOnly(TradingEventType.ORDER_COMMAND, pipeline);
    }

    @Bean
    OrderExecutionIdempotencyTracker orderExecutionIdempotencyTracker(ExecutionProperties properties) {
        return new OrderExecutionIdempotencyTracker(properties);
    }
}
