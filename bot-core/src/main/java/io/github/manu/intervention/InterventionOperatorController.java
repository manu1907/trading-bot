package io.github.manu.intervention;

import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/internal/interventions")
@ConditionalOnBean(InterventionAcknowledgementService.class)
@ConditionalOnProperty(prefix = "trading.intervention.operator-api", name = "enabled", havingValue = "true")
public final class InterventionOperatorController {

    static final String OPERATOR_TOKEN_HEADER = "X-Operator-Token";

    private final InterventionAcknowledgementService acknowledgementService;
    private final TradingStateProjection projection;
    private final String operatorToken;

    public InterventionOperatorController(
            InterventionAcknowledgementService acknowledgementService,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        this.acknowledgementService = Objects.requireNonNull(acknowledgementService, "acknowledgementService");
        this.projection = Objects.requireNonNull(projection, "projection");
        InterventionProperties.OperatorApi operatorApi = Objects.requireNonNull(properties, "properties").operatorApi();
        this.operatorToken = requireText(operatorApi.operatorToken(), "operatorToken");
    }

    @GetMapping("/orders")
    public Mono<ResponseEntity<?>> orderInterventions(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestParam("provider") String provider,
            @RequestParam("environment") String environment,
            @RequestParam("account") String account,
            @RequestParam("market") String market
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        try {
            List<OrderInterventionResponse> interventions = projection.externalOrderInterventionStates(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    )
                    .stream()
                    .map(OrderInterventionResponse::from)
                    .toList();
            return Mono.just(ResponseEntity.ok(new OrderInterventionsResponse(interventions.size(), interventions)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/positions")
    public Mono<ResponseEntity<?>> positionInterventions(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestParam("provider") String provider,
            @RequestParam("environment") String environment,
            @RequestParam("account") String account,
            @RequestParam("market") String market
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        try {
            List<PositionInterventionResponse> interventions = projection.externalPositionInterventionStates(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    )
                    .stream()
                    .map(PositionInterventionResponse::from)
                    .toList();
            return Mono.just(ResponseEntity.ok(new PositionInterventionsResponse(interventions.size(), interventions)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/orders/acknowledgements")
    public Mono<ResponseEntity<?>> acknowledgeOrder(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<OrderAcknowledgementHttpRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        Mono<ResponseEntity<?>> response = request
                .flatMap(this::acknowledge)
                .map(published -> accepted(published));
        return response
                .onErrorResume(IllegalArgumentException.class, this::badRequest)
                .onErrorResume(IllegalStateException.class, this::conflict);
    }

    private Mono<PublishedTradingEvent> acknowledge(OrderAcknowledgementHttpRequest request) {
        return Mono.fromFuture(acknowledgementService.acknowledgeOrder(request.toServiceRequest()));
    }

    private ResponseEntity<AcknowledgementAcceptedResponse> accepted(PublishedTradingEvent published) {
        return ResponseEntity.accepted()
                .body(new AcknowledgementAcceptedResponse(
                        "accepted",
                        published.eventType().name(),
                        published.topic(),
                        published.partition(),
                        published.offset()
                ));
    }

    private Mono<ResponseEntity<?>> badRequest(IllegalArgumentException exception) {
        return Mono.just(error(HttpStatus.BAD_REQUEST, "bad_request", exception.getMessage()));
    }

    private Mono<ResponseEntity<?>> conflict(IllegalStateException exception) {
        return Mono.just(error(HttpStatus.CONFLICT, "conflict", exception.getMessage()));
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }

    private boolean authorized(String operatorToken) {
        return this.operatorToken.equals(text(operatorToken));
    }

    private String requireText(String value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    record OrderAcknowledgementHttpRequest(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String interventionReason,
            String acknowledgedBy,
            String acknowledgementReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        OrderAcknowledgementHttpRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest toServiceRequest() {
            return new InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest(
                    provider,
                    environment,
                    account,
                    market,
                    symbol,
                    clientOrderId,
                    interventionReason,
                    acknowledgedBy,
                    acknowledgementReason,
                    attributes
            );
        }
    }

    record OrderInterventionsResponse(int count, List<OrderInterventionResponse> interventions) {
    }

    record OrderInterventionResponse(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String clientOrderId,
            String exchangeOrderId,
            String status,
            String exchangeStatus,
            String updateSource,
            String executionType,
            boolean managedByBot,
            String interventionReason,
            Instant updatedAt,
            String eventId
    ) {

        static OrderInterventionResponse from(TradingStateProjection.OrderState order) {
            return new OrderInterventionResponse(
                    order.provider(),
                    order.environment(),
                    order.account(),
                    order.market(),
                    order.symbol(),
                    order.clientOrderId(),
                    order.exchangeOrderId(),
                    order.status(),
                    order.exchangeStatus(),
                    order.updateSource(),
                    order.executionType(),
                    order.managedByBot(),
                    order.interventionReason(),
                    order.updatedAt(),
                    order.eventId()
            );
        }
    }

    record PositionInterventionsResponse(int count, List<PositionInterventionResponse> interventions) {
    }

    record PositionInterventionResponse(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String positionSide,
            String positionAmount,
            String entryPrice,
            String markPrice,
            String unrealizedPnl,
            String updateSource,
            String interventionReason,
            Instant updatedAt,
            String eventId
    ) {

        static PositionInterventionResponse from(TradingStateProjection.PositionState position) {
            return new PositionInterventionResponse(
                    position.provider(),
                    position.environment(),
                    position.account(),
                    position.market(),
                    position.symbol(),
                    position.positionSide(),
                    position.positionAmount(),
                    position.entryPrice(),
                    position.markPrice(),
                    position.unrealizedPnl(),
                    position.updateSource(),
                    position.interventionReason(),
                    position.updatedAt(),
                    position.eventId()
            );
        }
    }

    record AcknowledgementAcceptedResponse(
            String status,
            String eventType,
            String topic,
            int partition,
            long offset
    ) {
    }

    record ErrorResponse(String error, String message) {
    }
}
