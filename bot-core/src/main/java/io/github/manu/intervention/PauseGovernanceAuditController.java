package io.github.manu.intervention;

import io.github.manu.audit.PauseGovernanceAuditTrail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/internal/interventions/pauses/audit-events")
@ConditionalOnProperty(prefix = "trading.intervention.operator-api", name = "enabled", havingValue = "true")
public final class PauseGovernanceAuditController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final PauseGovernanceAuditTrail auditTrail;
    private final String operatorToken;

    public PauseGovernanceAuditController(
            PauseGovernanceAuditTrail auditTrail,
            InterventionProperties properties
    ) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        InterventionProperties.OperatorApi operatorApi = Objects.requireNonNull(properties, "properties").operatorApi();
        this.operatorToken = requireText(operatorApi.operatorToken(), "operatorToken");
    }

    @GetMapping
    public Mono<ResponseEntity<?>> auditEvents(
            @RequestHeader(name = InterventionOperatorController.OPERATOR_TOKEN_HEADER, required = false) String operatorToken,
            @RequestParam("provider") String provider,
            @RequestParam("environment") String environment,
            @RequestParam("account") String account,
            @RequestParam("market") String market,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        if (!authorized(operatorToken)) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid operator token"));
        }
        try {
            int normalizedLimit = limit(limit);
            List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> events = auditTrail.recent(
                    requireText(provider, "provider"),
                    requireText(environment, "environment"),
                    requireText(account, "account"),
                    requireText(market, "market"),
                    normalizedLimit
            );
            return Mono.just(ResponseEntity.ok(new PauseGovernanceAuditEventsResponse(events.size(), events)));
        } catch (IllegalArgumentException exception) {
            return Mono.just(error(HttpStatus.BAD_REQUEST, "bad_request", exception.getMessage()));
        }
    }

    private int limit(Integer limit) {
        int normalizedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (normalizedLimit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(normalizedLimit, MAX_LIMIT);
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

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }

    record PauseGovernanceAuditEventsResponse(
            int count,
            List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> events
    ) {
    }

    record ErrorResponse(String error, String message) {
    }
}
