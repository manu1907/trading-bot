package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExecutionPipelineTest {

    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usd_m_futures";
    private static final String SYMBOL = "BTCUSDT";
    private static final Instant NOW = Instant.parse("2026-05-26T13:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final ReconciliationConfidenceTracker reconciliationTracker = new ReconciliationConfidenceTracker(clock);

    @Test
    void publishes_rejected_risk_decision_and_does_not_submit_when_risk_gate_rejects() {
        FakeGateway gateway = new FakeGateway(eventBus);

        pipeline(List.of(gateway)).handleOrderCommand(command()).join();

        assertThat(gateway.submissions).isZero();
        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.RISK_DECISION);
            RiskDecisionEvent decision = (RiskDecisionEvent) envelope.value();
            assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
            assertThat(decision.getReasons()).containsExactly("reconciliation:no_observations");
        });
    }

    @Test
    void publishes_risk_decision_before_submitting_and_then_publishes_order_result() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        FakeGateway gateway = new FakeGateway(eventBus);

        pipeline(List.of(gateway)).handleOrderCommand(command()).join();

        assertThat(gateway.submissions).isEqualTo(1);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        RiskDecisionEvent decision = (RiskDecisionEvent) eventBus.envelopes.getFirst().value();
        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        OrderResultEvent result = (OrderResultEvent) eventBus.envelopes.get(1).value();
        assertThat(result.getStatus()).isEqualTo(OrderResultStatus.ACCEPTED);
        assertThat(result.getCommandId()).isEqualTo("cmd-001");
    }

    @Test
    void rejects_approved_command_when_no_gateway_supports_target() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        pipeline(List.of()).handleOrderCommand(command()).join();

        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.RISK_DECISION);
            RiskDecisionEvent decision = (RiskDecisionEvent) envelope.value();
            assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
            assertThat(decision.getReasons()).containsExactly("execution:no_gateway");
            assertThat(decision.getAttributes()).containsEntry("execution_provider", PROVIDER);
        });
    }

    @Test
    void rejects_duplicate_command_id_before_submitting_again() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        FakeGateway gateway = new FakeGateway(eventBus);
        OrderExecutionPipeline pipeline = pipeline(List.of(gateway));

        pipeline.handleOrderCommand(command()).join();
        pipeline.handleOrderCommand(command("cmd-001", "idem-002")).join();

        assertThat(gateway.submissions).isEqualTo(1);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(
                        TradingEventType.RISK_DECISION,
                        TradingEventType.ORDER_RESULT,
                        TradingEventType.RISK_DECISION
                );
        RiskDecisionEvent duplicateDecision = (RiskDecisionEvent) eventBus.envelopes.get(2).value();
        assertThat(duplicateDecision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(duplicateDecision.getReasons()).containsExactly("execution:duplicate_command_id");
        assertThat(duplicateDecision.getAttributes()).containsEntry("duplicate_command_id", "cmd-001");
    }

    @Test
    void rejects_duplicate_idempotency_key_before_submitting_again() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        FakeGateway gateway = new FakeGateway(eventBus);
        OrderExecutionPipeline pipeline = pipeline(List.of(gateway));

        pipeline.handleOrderCommand(command()).join();
        pipeline.handleOrderCommand(command("cmd-002", "idem-001")).join();

        assertThat(gateway.submissions).isEqualTo(1);
        RiskDecisionEvent duplicateDecision = (RiskDecisionEvent) eventBus.envelopes.get(2).value();
        assertThat(duplicateDecision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(duplicateDecision.getReasons()).containsExactly("execution:duplicate_idempotency_key");
        assertThat(duplicateDecision.getAttributes()).containsEntry("duplicate_idempotency_key", "idem-001");
    }

    @Test
    void publishes_unknown_order_result_when_gateway_submit_throws() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        pipeline(List.of(new ThrowingGateway())).handleOrderCommand(command()).join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        assertUnknownGatewayFailure(eventBus.envelopes.get(1), "IllegalStateException", "socket closed before write");
    }

    @Test
    void publishes_target_identity_on_gateway_failure_for_target_order_commands() {
        ExecutionProperties properties = targetCommandProperties();

        pipeline(properties, List.of(new ThrowingGateway())).handleOrderCommand(cancelCommand()).join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        OrderResultEvent result = (OrderResultEvent) eventBus.envelopes.get(1).value();
        assertThat(result.getEventId()).isEqualTo("order-result:cmd-cancel-001:tb-lfa-open:gateway_failure");
        assertThat(result.getClientOrderId()).isEqualTo("tb-lfa-open");
        assertThat(result.getExchangeOrderId()).isEqualTo("123456");
        assertThat(result.getStatus()).isEqualTo(OrderResultStatus.UNKNOWN);
        assertThat(eventBus.envelopes.get(1).key().getEntityId()).isEqualTo("tb-lfa-open");
        assertThat(result.getAttributes())
                .containsEntry("command_client_order_id", "tb-cancel-001")
                .containsEntry("target_client_order_id", "tb-lfa-open")
                .containsEntry("target_exchange_order_id", "123456")
                .containsEntry("gateway_failure", "true");
    }

    @Test
    void publishes_unknown_order_result_when_gateway_future_fails() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        pipeline(List.of(new FutureFailingGateway())).handleOrderCommand(command()).join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        assertUnknownGatewayFailure(eventBus.envelopes.get(1), "IllegalArgumentException", "bad request envelope");
    }

    @Test
    void publishes_unknown_order_result_when_gateway_returns_no_result() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        pipeline(List.of(new NullResultGateway())).handleOrderCommand(command()).join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        assertUnknownGatewayFailure(
                eventBus.envelopes.get(1),
                "IllegalStateException",
                "Order execution gateway returned no result envelope"
        );
    }

    @Test
    void publishes_unknown_order_result_when_gateway_returns_mismatched_new_order_identity() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        pipeline(List.of(new MismatchedClientOrderGateway())).handleOrderCommand(command()).join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        assertUnknownGatewayFailure(
                eventBus.envelopes.get(1),
                "IllegalStateException",
                "Order execution gateway returned mismatched result identity: clientOrderId expected tb-lfa-cmd-001 but was tb-other"
        );
    }

    @Test
    void publishes_unknown_order_result_when_gateway_returns_mismatched_target_order_identity() {
        ExecutionProperties properties = targetCommandProperties();

        pipeline(properties, List.of(new MismatchedTargetExchangeOrderGateway()))
                .handleOrderCommand(cancelCommand())
                .join();

        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsExactly(TradingEventType.RISK_DECISION, TradingEventType.ORDER_RESULT);
        OrderResultEvent result = (OrderResultEvent) eventBus.envelopes.get(1).value();
        assertThat(result.getClientOrderId()).isEqualTo("tb-lfa-open");
        assertThat(result.getExchangeOrderId()).isEqualTo("123456");
        assertThat(result.getRejectMessage())
                .isEqualTo("Order execution gateway returned mismatched result identity: exchangeOrderId expected 123456 but was 999999");
        assertThat(result.getAttributes()).containsEntry("gateway_failure", "true");
    }

    private OrderExecutionPipeline pipeline(List<OrderExecutionGateway> gateways) {
        return pipeline(new ExecutionProperties(null), gateways);
    }

    private OrderExecutionPipeline pipeline(ExecutionProperties properties, List<OrderExecutionGateway> gateways) {
        OrderRiskGate riskGate = new OrderRiskGate(properties, reconciliationTracker, clock);
        return new OrderExecutionPipeline(
                riskGate,
                eventBus,
                gateways,
                new OrderExecutionIdempotencyTracker(properties),
                clock
        );
    }

    private ExecutionProperties targetCommandProperties() {
        return new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                null,
                null,
                new ExecutionProperties.TargetOrder(
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        true
                )
        ));
    }

    private void recordReconciliation(ReconciliationConfidenceStatus status) {
        reconciliationTracker.record(new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.BALANCE_UPDATE,
                PROVIDER + "|" + ENVIRONMENT + "|" + ACCOUNT + "|" + MARKET + "|USDT",
                status,
                List.of()
        ));
    }

    private OrderCommandEvent command() {
        return command("cmd-001", "idem-001");
    }

    private OrderCommandEvent command(String commandId, String idempotencyKey) {
        return OrderCommandEvent.newBuilder()
                .setEventId("evt-" + commandId)
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.00")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-" + commandId)
                .setIdempotencyKey(idempotencyKey)
                .setRequestedAtMicros(NOW)
                .setAttributes(Map.of("signal_id", "sig-001"))
                .build();
    }

    private OrderCommandEvent cancelCommand() {
        return OrderCommandEvent.newBuilder(command("cmd-cancel-001", "idem-cancel-001"))
                .setAction(OrderCommandAction.CANCEL)
                .setClientOrderId("tb-cancel-001")
                .setTargetClientOrderId("tb-lfa-open")
                .setTargetExchangeOrderId("123456")
                .build();
    }

    private OrderResultEvent acceptedResult(OrderCommandEvent command) {
        return OrderResultEvent.newBuilder()
                .setEventId("evt-result-001")
                .setSchemaVersion(1)
                .setCommandId(command.getCommandId())
                .setProvider(command.getProvider())
                .setEnvironment(command.getEnvironment())
                .setAccount(command.getAccount())
                .setMarket(command.getMarket())
                .setSymbol(command.getSymbol())
                .setClientOrderId(command.getClientOrderId())
                .setExchangeOrderId("123456")
                .setStatus(OrderResultStatus.ACCEPTED)
                .setExchangeStatus("NEW")
                .setPrice(command.getPrice())
                .setOriginalQuantity(command.getQuantity())
                .setExecutedQuantity("0")
                .setAveragePrice(null)
                .setCumulativeQuote("0")
                .setExchangeTransactTimeMicros(NOW)
                .setObservedAtMicros(NOW)
                .setRejectCode(null)
                .setRejectMessage(null)
                .setAttributes(Map.of())
                .build();
    }

    private TradingEventEnvelope<OrderResultEvent> resultEnvelope(OrderCommandEvent command) {
        OrderResultEvent result = acceptedResult(command);
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(
                        TradingEventType.ORDER_RESULT,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        result.getClientOrderId().toString()
                ),
                result
        );
    }

    private TradingEventEnvelope<OrderResultEvent> resultEnvelope(OrderResultEvent result) {
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(
                        TradingEventType.ORDER_RESULT,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        result.getClientOrderId().toString()
                ),
                result
        );
    }

    private void assertUnknownGatewayFailure(
            TradingEventEnvelope<? extends SpecificRecord> envelope,
            String failureType,
            String failureMessage
    ) {
        OrderResultEvent result = (OrderResultEvent) envelope.value();
        assertThat(result.getStatus()).isEqualTo(OrderResultStatus.UNKNOWN);
        assertThat(result.getEventId()).isEqualTo("order-result:cmd-001:tb-lfa-cmd-001:gateway_failure");
        assertThat(result.getRejectCode()).isEqualTo("GATEWAY_FAILURE");
        assertThat(result.getRejectMessage()).isEqualTo(failureMessage);
        assertThat(result.getObservedAtMicros()).isEqualTo(NOW);
        assertThat(result.getAttributes())
                .containsEntry("source", "order_execution_pipeline")
                .containsEntry("gateway_failure", "true")
                .containsEntry("gateway_failure_type", failureType);
    }

    private final class FakeGateway implements OrderExecutionGateway {

        private final CapturingTradingEventBus bus;
        private int submissions;

        private FakeGateway(CapturingTradingEventBus bus) {
            this.bus = bus;
        }

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return PROVIDER.equals(provider)
                    && ENVIRONMENT.equals(environment)
                    && ACCOUNT.equals(account)
                    && MARKET.equals(market);
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            submissions++;
            assertThat(bus.envelopes).singleElement()
                    .extracting(TradingEventEnvelope::eventType)
                    .isEqualTo(TradingEventType.RISK_DECISION);
            return CompletableFuture.completedFuture(resultEnvelope(command));
        }
    }

    private final class ThrowingGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            throw new IllegalStateException("socket closed before write");
        }
    }

    private final class FutureFailingGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("bad request envelope"));
        }
    }

    private final class NullResultGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class MismatchedClientOrderGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            OrderResultEvent result = OrderResultEvent.newBuilder(acceptedResult(command))
                    .setClientOrderId("tb-other")
                    .build();
            return CompletableFuture.completedFuture(resultEnvelope(result));
        }
    }

    private final class MismatchedTargetExchangeOrderGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            OrderResultEvent result = OrderResultEvent.newBuilder(acceptedResult(command))
                    .setClientOrderId(command.getTargetClientOrderId())
                    .setExchangeOrderId("999999")
                    .build();
            return CompletableFuture.completedFuture(resultEnvelope(result));
        }
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
