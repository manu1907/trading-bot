package io.github.manu.runtime;

import io.github.manu.intervention.InterventionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Objects;

@RestController
@RequestMapping("/internal/runtime")
@ConditionalOnBean(RuntimeStatusService.class)
@ConditionalOnProperty(prefix = "trading.intervention.operator-api", name = "enabled", havingValue = "true")
public final class RuntimeStatusController {

    static final String OPERATOR_TOKEN_HEADER = "X-Operator-Token";

    private final RuntimeStatusService runtimeStatusService;
    private final String operatorToken;

    public RuntimeStatusController(RuntimeStatusService runtimeStatusService, InterventionProperties properties) {
        this.runtimeStatusService = Objects.requireNonNull(runtimeStatusService, "runtimeStatusService");
        this.operatorToken = requireText(properties.operatorApi().operatorToken(), "operatorToken");
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<?>> status(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String token,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "account", required = false) String account,
            @RequestParam(name = "market", required = false) String market,
            @RequestParam(name = "maxMarketDataAgeMillis", required = false) Long maxMarketDataAgeMillis,
            @RequestParam(name = "minFreshMarketDataSymbols", required = false) Integer minFreshMarketDataSymbols,
            @RequestParam(name = "strategyId", required = false) String strategyId
    ) {
        if (!authorized(token)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        try {
            RuntimeStatusService.RuntimeStatus status = runtimeStatusService.status(
                    new RuntimeStatusService.RuntimeStatusRequest(
                            provider,
                            environment,
                            account,
                            market,
                            maxMarketDataAgeMillis,
                            minFreshMarketDataSymbols,
                            strategyId
                    )
            );
            return Mono.just(ResponseEntity.ok(status));
        } catch (IllegalArgumentException exception) {
            return Mono.just(error(HttpStatus.BAD_REQUEST, "bad_request", exception.getMessage()));
        }
    }

    private boolean authorized(String token) {
        return operatorToken.equals(token);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record ErrorResponse(String error, String message) {
    }
}
