package io.github.manu.intervention;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.intervention")
public record InterventionProperties(
        OperatorApi operatorApi,
        RemediationOrchestrator remediationOrchestrator,
        AutomatedPolicy automatedPolicy,
        AutomatedDecisionService automatedDecisionService
) {

    public InterventionProperties {
        operatorApi = operatorApi == null ? OperatorApi.disabled() : operatorApi;
        remediationOrchestrator = remediationOrchestrator == null
                ? RemediationOrchestrator.disabled()
                : remediationOrchestrator;
        automatedPolicy = automatedPolicy == null ? AutomatedPolicy.defaults() : automatedPolicy;
        automatedDecisionService = automatedDecisionService == null
                ? AutomatedDecisionService.disabled()
                : automatedDecisionService;
    }

    public record OperatorApi(
            Boolean enabled,
            String operatorToken
    ) {

        public OperatorApi {
            enabled = Boolean.TRUE.equals(enabled);
            operatorToken = text(operatorToken);
        }

        static OperatorApi disabled() {
            return new OperatorApi(false, null);
        }

        private static String text(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }

    public record RemediationOrchestrator(
            Boolean enabled,
            Boolean operatorReviewAcknowledgementEnabled,
            Integer maxTrackedDecisionIds
    ) {

        public RemediationOrchestrator {
            enabled = Boolean.TRUE.equals(enabled);
            operatorReviewAcknowledgementEnabled = operatorReviewAcknowledgementEnabled == null
                    || Boolean.TRUE.equals(operatorReviewAcknowledgementEnabled);
            int normalizedMaxTrackedDecisionIds = maxTrackedDecisionIds == null ? 100_000 : maxTrackedDecisionIds;
            if (normalizedMaxTrackedDecisionIds <= 0) {
                throw new IllegalArgumentException("maxTrackedDecisionIds must be positive");
            }
            maxTrackedDecisionIds = normalizedMaxTrackedDecisionIds;
        }

        static RemediationOrchestrator disabled() {
            return new RemediationOrchestrator(false, true, 100_000);
        }
    }

    public record AutomatedPolicy(
            RemediationAction externalOrderAction,
            RemediationAction managedOrderChangeAction,
            RemediationAction flatPositionAction,
            RemediationAction openPositionAction,
            RemediationAction unknownPositionAction
    ) {

        public AutomatedPolicy {
            externalOrderAction = externalOrderAction == null
                    ? RemediationAction.OPERATOR_REVIEW
                    : externalOrderAction;
            managedOrderChangeAction = managedOrderChangeAction == null
                    ? RemediationAction.REPLAN_FROM_PROJECTION
                    : managedOrderChangeAction;
            flatPositionAction = flatPositionAction == null
                    ? RemediationAction.REPLAN_FROM_PROJECTION
                    : flatPositionAction;
            openPositionAction = openPositionAction == null
                    ? RemediationAction.HEDGE_OR_REPLAN
                    : openPositionAction;
            unknownPositionAction = unknownPositionAction == null
                    ? RemediationAction.OPERATOR_REVIEW
                    : unknownPositionAction;
        }

        static AutomatedPolicy defaults() {
            return new AutomatedPolicy(null, null, null, null, null);
        }
    }

    public record AutomatedDecisionService(
            Boolean enabled,
            Boolean includeOperatorReviewActions,
            Integer maxDecisionsPerRun,
            String decidedBy,
            String decisionReason
    ) {

        public AutomatedDecisionService {
            enabled = Boolean.TRUE.equals(enabled);
            includeOperatorReviewActions = Boolean.TRUE.equals(includeOperatorReviewActions);
            int normalizedMaxDecisionsPerRun = maxDecisionsPerRun == null ? 100 : maxDecisionsPerRun;
            if (normalizedMaxDecisionsPerRun <= 0) {
                throw new IllegalArgumentException("maxDecisionsPerRun must be positive");
            }
            maxDecisionsPerRun = normalizedMaxDecisionsPerRun;
            decidedBy = decidedBy == null || decidedBy.isBlank()
                    ? "automated_remediation_policy"
                    : decidedBy.trim();
            decisionReason = decisionReason == null || decisionReason.isBlank()
                    ? "automated policy selected remediation action"
                    : decisionReason.trim();
        }

        static AutomatedDecisionService disabled() {
            return new AutomatedDecisionService(false, false, 100, null, null);
        }
    }

    public enum RemediationAction {
        OPERATOR_REVIEW,
        REPLAN_FROM_PROJECTION,
        HEDGE_OR_REPLAN,
        ADOPT,
        AMEND,
        REDUCE,
        CLOSE,
        HEDGE,
        PAUSE_SYMBOL,
        PAUSE_ACCOUNT,
        IGNORE
    }
}
