package io.github.manu.strategy.lfa;

import io.github.manu.intervention.InterventionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Objects;

@RestController
@RequestMapping("/internal/strategy/lfa/lifecycle")
@ConditionalOnBean(LfaSignalRunner.class)
@ConditionalOnProperty(prefix = "trading.intervention.operator-api", name = "enabled", havingValue = "true")
public final class LfaLifecycleOperatorController {

    static final String OPERATOR_TOKEN_HEADER = "X-Operator-Token";

    private final LfaSignalRunner runner;
    private final String operatorToken;

    public LfaLifecycleOperatorController(
            LfaSignalRunner runner,
            InterventionProperties interventionProperties
    ) {
        this.runner = Objects.requireNonNull(runner, "runner");
        InterventionProperties.OperatorApi operatorApi =
                Objects.requireNonNull(interventionProperties, "interventionProperties").operatorApi();
        this.operatorToken = requireText(operatorApi.operatorToken(), "operatorToken");
    }

    @GetMapping
    public Mono<ResponseEntity<?>> lifecycle(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        return Mono.just(ResponseEntity.ok(runner.lifecycleStatus()));
    }

    @PostMapping
    public Mono<ResponseEntity<?>> transitionLifecycle(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestBody Mono<LifecycleTransitionRequest> request
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        return request
                .flatMap(this::transition)
                .onErrorResume(IllegalArgumentException.class, exception ->
                        Mono.just(error(HttpStatus.BAD_REQUEST, "bad_request", exception.getMessage())));
    }

    private Mono<ResponseEntity<?>> transition(LifecycleTransitionRequest request) {
        LifecycleTransitionRequest required = Objects.requireNonNull(request, "request");
        return Mono.fromFuture(runner.transitionLifecycle(
                requireText(required.lifecycleState(), "lifecycleState"),
                required.changedBy(),
                required.reason()
        )).map(ResponseEntity::ok);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }

    private boolean authorized(String operatorToken) {
        return this.operatorToken.equals(text(operatorToken));
    }

    private static String requireText(String value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record LifecycleTransitionRequest(
            String lifecycleState,
            String changedBy,
            String reason
    ) {
    }

    public record ErrorResponse(
            String error,
            String message
    ) {
    }
}
