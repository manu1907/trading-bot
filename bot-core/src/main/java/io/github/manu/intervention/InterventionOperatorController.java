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
    private final InterventionRemediationDecisionService remediationDecisionService;
    private final PauseGovernanceControlService pauseGovernanceControlService;
    private final InterventionAutomatedDecisionService automatedDecisionService;
    private final InterventionRemediationAdvisor remediationAdvisor;
    private final InterventionRemediationExecutorService remediationExecutorService;
    private final TradingStateProjection projection;
    private final String operatorToken;

    public InterventionOperatorController(
            InterventionAcknowledgementService acknowledgementService,
            InterventionRemediationDecisionService remediationDecisionService,
            PauseGovernanceControlService pauseGovernanceControlService,
            InterventionAutomatedDecisionService automatedDecisionService,
            InterventionRemediationAdvisor remediationAdvisor,
            InterventionRemediationExecutorService remediationExecutorService,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        this.acknowledgementService = Objects.requireNonNull(acknowledgementService, "acknowledgementService");
        this.remediationDecisionService = Objects.requireNonNull(remediationDecisionService, "remediationDecisionService");
        this.pauseGovernanceControlService =
                Objects.requireNonNull(pauseGovernanceControlService, "pauseGovernanceControlService");
        this.automatedDecisionService = Objects.requireNonNull(automatedDecisionService, "automatedDecisionService");
        this.remediationAdvisor = Objects.requireNonNull(remediationAdvisor, "remediationAdvisor");
        this.remediationExecutorService = Objects.requireNonNull(remediationExecutorService, "remediationExecutorService");
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

    @GetMapping("/manual-reviews")
    public Mono<ResponseEntity<?>> manualReviews(
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
            List<ManualReviewDecisionResponse> decisions = projection.manualReviewDecisionStates(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    )
                    .stream()
                    .map(ManualReviewDecisionResponse::from)
                    .toList();
            return Mono.just(ResponseEntity.ok(new ManualReviewDecisionsResponse(decisions.size(), decisions)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/pauses")
    public Mono<ResponseEntity<?>> pauseGovernance(
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
            List<PauseGovernanceResponse> pauses = projection.pauseGovernanceStates(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    )
                    .stream()
                    .map(PauseGovernanceResponse::from)
                    .toList();
            return Mono.just(ResponseEntity.ok(new PauseGovernanceListResponse(pauses.size(), pauses)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/remediation")
    public Mono<ResponseEntity<?>> remediationRecommendations(
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
            List<InterventionRemediationAdvisor.RemediationRecommendation> recommendations =
                    remediationAdvisor.recommendations(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    );
            return Mono.just(ResponseEntity.ok(new RemediationRecommendationsResponse(
                    recommendations.size(),
                    recommendations
            )));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/remediation/decisions")
    public Mono<ResponseEntity<?>> remediationDecisions(
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
            List<RemediationDecisionResponse> decisions = projection.remediationDecisionStates(
                            requireText(provider, "provider"),
                            requireText(environment, "environment"),
                            requireText(account, "account"),
                            requireText(market, "market")
                    )
                    .stream()
                    .map(RemediationDecisionResponse::from)
                    .toList();
            return Mono.just(ResponseEntity.ok(new RemediationDecisionsResponse(decisions.size(), decisions)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/remediation/plans")
    public Mono<ResponseEntity<?>> remediationPlans(
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
            List<InterventionRemediationCommandPlanner.RemediationCommandPlan> plans =
                    remediationExecutorService.plans(provider, environment, account, market);
            return Mono.just(ResponseEntity.ok(new RemediationCommandPlansResponse(plans.size(), plans)));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/remediation/executor/preview")
    public Mono<ResponseEntity<?>> remediationExecutorPreview(
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
            InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                    remediationExecutorService.preview(provider, environment, account, market);
            return Mono.just(ResponseEntity.ok(batch));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/remediation/executor/execute")
    public Mono<ResponseEntity<?>> remediationExecutorExecute(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<ExecutorHttpRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        Mono<ResponseEntity<?>> response = request
                .map(this::executeRemediation)
                .map(batch -> (ResponseEntity<?>) batch);
        return response.onErrorResume(IllegalArgumentException.class, this::badRequest);
    }

    @PostMapping("/remediation/decisions")
    public Mono<ResponseEntity<?>> decideRemediation(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<RemediationDecisionHttpRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        Mono<ResponseEntity<?>> response = request
                .flatMap(this::decide)
                .map(published -> accepted(published));
        return response
                .onErrorResume(IllegalArgumentException.class, this::badRequest)
                .onErrorResume(IllegalStateException.class, this::conflict);
    }

    @PostMapping("/remediation/automated-decisions")
    public Mono<ResponseEntity<?>> decideAutomatedRemediation(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<AutomatedDecisionHttpRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        Mono<ResponseEntity<?>> response = request
                .flatMap(this::decideAutomated)
                .map(batch -> ResponseEntity.accepted().body(AutomatedDecisionResponse.from(batch)));
        return response
                .onErrorResume(IllegalArgumentException.class, this::badRequest)
                .onErrorResume(IllegalStateException.class, this::conflict);
    }

    @PostMapping("/pauses/releases")
    public Mono<ResponseEntity<?>> releasePause(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<PauseReleaseHttpRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        Mono<ResponseEntity<?>> response = request
                .flatMap(this::releasePause)
                .map(published -> accepted(published));
        return response
                .onErrorResume(IllegalArgumentException.class, this::badRequest)
                .onErrorResume(IllegalStateException.class, this::conflict);
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

    @PostMapping("/positions/acknowledgements")
    public Mono<ResponseEntity<?>> acknowledgePosition(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<PositionAcknowledgementHttpRequest> request
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

    private Mono<PublishedTradingEvent> acknowledge(PositionAcknowledgementHttpRequest request) {
        return Mono.fromFuture(acknowledgementService.acknowledgePosition(request.toServiceRequest()));
    }

    private Mono<PublishedTradingEvent> decide(RemediationDecisionHttpRequest request) {
        return Mono.fromFuture(remediationDecisionService.decide(request.toServiceRequest()));
    }

    private Mono<PublishedTradingEvent> releasePause(PauseReleaseHttpRequest request) {
        return Mono.fromFuture(pauseGovernanceControlService.release(request.toServiceRequest()));
    }

    private Mono<InterventionAutomatedDecisionService.AutomatedDecisionBatch> decideAutomated(
            AutomatedDecisionHttpRequest request
    ) {
        return Mono.fromFuture(automatedDecisionService.decide(
                request.provider(),
                request.environment(),
                request.account(),
                request.market()
        ));
    }

    private ResponseEntity<InterventionRemediationExecutorService.RemediationExecutionBatch> executeRemediation(
            ExecutorHttpRequest request
    ) {
        return ResponseEntity.accepted().body(remediationExecutorService.execute(
                requireText(request.provider(), "provider"),
                requireText(request.environment(), "environment"),
                requireText(request.account(), "account"),
                requireText(request.market(), "market")
        ));
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

    record ManualReviewDecisionsResponse(int count, List<ManualReviewDecisionResponse> decisions) {
    }

    record PauseGovernanceListResponse(int count, List<PauseGovernanceResponse> pauses) {
    }

    record RemediationRecommendationsResponse(
            int count,
            List<InterventionRemediationAdvisor.RemediationRecommendation> recommendations
    ) {
    }

    record RemediationDecisionsResponse(int count, List<RemediationDecisionResponse> decisions) {
    }

    record RemediationCommandPlansResponse(
            int count,
            List<InterventionRemediationCommandPlanner.RemediationCommandPlan> plans
    ) {
    }

    record AutomatedDecisionResponse(
            boolean enabled,
            long publishedCount,
            long skippedCount,
            List<InterventionAutomatedDecisionService.AutomatedDecisionOutcome> outcomes
    ) {

        static AutomatedDecisionResponse from(InterventionAutomatedDecisionService.AutomatedDecisionBatch batch) {
            return new AutomatedDecisionResponse(
                    batch.enabled(),
                    batch.publishedCount(),
                    batch.skippedCount(),
                    batch.outcomes()
            );
        }
    }

    record RemediationDecisionHttpRequest(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String scope,
            String action,
            String clientOrderId,
            String positionSide,
            String decidedBy,
            String decisionReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        RemediationDecisionHttpRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        InterventionRemediationDecisionService.RemediationDecisionRequest toServiceRequest() {
            return new InterventionRemediationDecisionService.RemediationDecisionRequest(
                    provider,
                    environment,
                    account,
                    market,
                    symbol,
                    scope,
                    action,
                    clientOrderId,
                    positionSide,
                    decidedBy,
                    decisionReason,
                    attributes
            );
        }
    }

    record AutomatedDecisionHttpRequest(
            String provider,
            String environment,
            String account,
            String market
    ) {
    }

    record PauseReleaseHttpRequest(
            String provider,
            String environment,
            String account,
            String market,
            String pauseScope,
            String pauseTarget,
            String releasedBy,
            String releaseReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        PauseReleaseHttpRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        PauseGovernanceControlService.PauseReleaseRequest toServiceRequest() {
            return new PauseGovernanceControlService.PauseReleaseRequest(
                    provider,
                    environment,
                    account,
                    market,
                    pauseScope,
                    pauseTarget,
                    releasedBy,
                    releaseReason,
                    attributes
            );
        }
    }

    record ExecutorHttpRequest(
            String provider,
            String environment,
            String account,
            String market
    ) {
    }

    record PositionAcknowledgementHttpRequest(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String positionSide,
            String interventionReason,
            String acknowledgedBy,
            String acknowledgementReason,
            Map<CharSequence, CharSequence> attributes
    ) {

        PositionAcknowledgementHttpRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        InterventionAcknowledgementService.PositionInterventionAcknowledgementRequest toServiceRequest() {
            return new InterventionAcknowledgementService.PositionInterventionAcknowledgementRequest(
                    provider,
                    environment,
                    account,
                    market,
                    symbol,
                    positionSide,
                    interventionReason,
                    acknowledgedBy,
                    acknowledgementReason,
                    attributes
            );
        }
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

    record ManualReviewDecisionResponse(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String commandId,
            String signalId,
            String strategyId,
            String decisionId,
            List<String> reasons,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) {

        static ManualReviewDecisionResponse from(TradingStateProjection.ManualReviewDecisionState state) {
            return new ManualReviewDecisionResponse(
                    state.provider(),
                    state.environment(),
                    state.account(),
                    state.market(),
                    state.symbol(),
                    state.commandId(),
                    state.signalId(),
                    state.strategyId(),
                    state.decisionId(),
                    state.reasons(),
                    state.attributes(),
                    state.updatedAt(),
                    state.eventId()
            );
        }
    }

    record PauseGovernanceResponse(
            String provider,
            String environment,
            String account,
            String market,
            String pauseScope,
            String pauseTarget,
            String symbol,
            String remediationId,
            String sourceScope,
            String action,
            String interventionReason,
            List<String> reasons,
            String decidedBy,
            String decisionReason,
            Map<String, String> attributes,
            boolean active,
            Instant expiresAt,
            boolean expired,
            boolean effectiveActive,
            Instant updatedAt,
            String eventId
    ) {

        static PauseGovernanceResponse from(TradingStateProjection.PauseGovernanceState state) {
            Instant now = Instant.now();
            return new PauseGovernanceResponse(
                    state.provider(),
                    state.environment(),
                    state.account(),
                    state.market(),
                    state.pauseScope(),
                    state.pauseTarget(),
                    state.symbol(),
                    state.remediationId(),
                    state.sourceScope(),
                    state.action(),
                    state.interventionReason(),
                    state.reasons(),
                    state.decidedBy(),
                    state.decisionReason(),
                    state.attributes(),
                    state.active(),
                    state.expiresAt(),
                    state.expired(now),
                    state.effectiveActive(now),
                    state.updatedAt(),
                    state.eventId()
            );
        }
    }

    record PositionInterventionsResponse(int count, List<PositionInterventionResponse> interventions) {
    }

    record RemediationDecisionResponse(
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String remediationId,
            String scope,
            String action,
            String clientOrderId,
            String positionSide,
            String interventionReason,
            List<String> reasons,
            String decidedBy,
            String decisionReason,
            Map<String, String> attributes,
            Instant updatedAt,
            String eventId
    ) {

        static RemediationDecisionResponse from(TradingStateProjection.RemediationDecisionState state) {
            return new RemediationDecisionResponse(
                    state.provider(),
                    state.environment(),
                    state.account(),
                    state.market(),
                    state.symbol(),
                    state.remediationId(),
                    state.scope(),
                    state.action(),
                    state.clientOrderId(),
                    state.positionSide(),
                    state.interventionReason(),
                    state.reasons(),
                    state.decidedBy(),
                    state.decisionReason(),
                    state.attributes(),
                    state.updatedAt(),
                    state.eventId()
            );
        }
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
