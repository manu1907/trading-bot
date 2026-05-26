package io.github.manu.projection;

import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProjectionConfiguration {

    @Bean
    TradingEventHandlerRegistration balanceProjectionHandler(TradingStateProjection projection) {
        return new TradingEventHandlerRegistration(TradingEventType.BALANCE_UPDATE, projection);
    }

    @Bean
    TradingEventHandlerRegistration positionProjectionHandler(TradingStateProjection projection) {
        return new TradingEventHandlerRegistration(TradingEventType.POSITION_UPDATE, projection);
    }

    @Bean
    TradingEventHandlerRegistration orderResultProjectionHandler(TradingStateProjection projection) {
        return new TradingEventHandlerRegistration(TradingEventType.ORDER_RESULT, projection);
    }

    @Bean
    TradingEventHandlerRegistration executionReportProjectionHandler(TradingStateProjection projection) {
        return new TradingEventHandlerRegistration(TradingEventType.EXECUTION_REPORT, projection);
    }

    @Bean
    TradingEventHandlerRegistration riskUpdateProjectionHandler(TradingStateProjection projection) {
        return new TradingEventHandlerRegistration(TradingEventType.RISK_UPDATE, projection);
    }
}
