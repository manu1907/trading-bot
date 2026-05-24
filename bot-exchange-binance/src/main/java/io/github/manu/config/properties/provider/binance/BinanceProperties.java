package io.github.manu.config.properties.provider.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceProperties(
        @NotNull String marketType,
        @Valid @NotNull Credentials credentials,
        @Valid @NotNull Rest rest,
        @Valid @NotNull Websocket websocket,
        @Valid @NotNull Trading trading,
        @Valid UserDataStream userDataStream,
        @Valid FuturesAccount futuresAccount
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credentials(
            @NotNull String reference,
            @NotNull String apiKey,
            @NotNull String apiSecret,
            @NotNull String keyType,
            @NotNull List<String> requiredPermissions
    ) {
        public Credentials {
            requiredPermissions = List.copyOf(requiredPermissions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rest(
            @NotNull String baseUrl,
            @NotNull String exchangeInfoPath,
            @NotNull String serverTimePath,
            @NotNull String apiKeyHeader,
            @NotNull String signatureAlgorithm,
            @NotNull String timestampUnit,
            Integer recvWindowMillis,
            Integer connectTimeoutMillis,
            Integer responseTimeoutMillis,
            Integer maxRetries,
            Integer retryBackoffMillis,
            @NotNull List<Integer> retryOnStatusCodes,
            @NotNull List<String> weightHeaders,
            @NotNull List<String> orderCountHeaders,
            @NotNull String orderResponseTypeDefault,
            @Valid UnknownExecutionStatus unknownExecutionStatus
    ) {
        public Rest {
            retryOnStatusCodes = List.copyOf(retryOnStatusCodes);
            weightHeaders = List.copyOf(weightHeaders);
            orderCountHeaders = List.copyOf(orderCountHeaders);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Websocket(
            @NotNull String baseUrl,
            String publicPathPrefix,
            String marketPathPrefix,
            String privatePathPrefix,
            String rawStreamPath,
            String combinedStreamPath,
            Integer maxConnectionLifetimeHours,
            Integer reconnectBeforeExpiryMinutes,
            Integer serverPingIntervalSeconds,
            Integer serverPingIntervalMinutes,
            Integer pongTimeoutSeconds,
            Integer pongTimeoutMinutes,
            Integer maxIncomingMessagesPerSecond,
            Integer maxStreamsPerConnection,
            Integer maxConnectionAttemptsPerFiveMinutes,
            @NotNull String timestampUnit
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trading(
            @NotNull String newOrderPath,
            String testOrderPath,
            @NotNull String queryOrderPath,
            @NotNull String cancelOrderPath,
            @NotNull String openOrdersPath,
            String allOrdersPath,
            String accountTradesPath,
            String commissionRatesPath,
            String preventedMatchesPath,
            String amendKeepPriorityPath,
            String batchOrdersPath,
            String modifyOrderPath,
            String modifyMultipleOrdersPath,
            String modifyOrderHistoryPath,
            String cancelMultipleOrdersPath,
            String cancelAllOpenOrdersPath,
            String countdownCancelAllPath,
            @NotNull List<String> supportedSides,
            @NotNull List<String> supportedOrderTypes,
            @NotNull List<String> supportedTimeInForce,
            @NotNull List<String> supportedOrderResponseTypes,
            @NotNull List<String> supportedSelfTradePreventionModes,
            @NotNull List<String> supportedPositionSides,
            @NotNull List<String> supportedPriceMatchOrderTypes,
            @NotNull List<String> supportedWorkingTypeOrderTypes,
            @NotNull List<String> supportedWorkingTypes,
            @NotNull List<String> supportedPriceProtectOrderTypes,
            @NotNull List<String> supportedPeggedOrderTypes,
            @NotNull List<String> supportedPegPriceTypes,
            @NotNull List<String> supportedPegOffsetTypes,
            @NotNull List<String> supportedMarginSideEffectTypes,
            @NotNull List<String> autoRepayAtCancelSideEffectTypes,
            Integer maxPegOffsetValue,
            boolean supportsQuoteOrderQty,
            boolean supportsReduceOnly,
            boolean supportsClosePosition,
            boolean supportsPriceMatch,
            boolean supportsWorkingType,
            boolean supportsPriceProtect,
            boolean supportsPeggedOrders,
            boolean supportsIcebergQty,
            boolean supportsTrailingDelta,
            boolean supportsMarginSideEffectControls,
            boolean supportsIsolatedMarginFlag,
            boolean supportsMarketMakerProtection
    ) {
        public Trading {
            supportedSides = List.copyOf(supportedSides);
            supportedOrderTypes = List.copyOf(supportedOrderTypes);
            supportedTimeInForce = List.copyOf(supportedTimeInForce);
            supportedOrderResponseTypes = List.copyOf(supportedOrderResponseTypes);
            supportedSelfTradePreventionModes = List.copyOf(supportedSelfTradePreventionModes);
            supportedPositionSides = List.copyOf(supportedPositionSides);
            supportedPriceMatchOrderTypes = List.copyOf(supportedPriceMatchOrderTypes);
            supportedWorkingTypeOrderTypes = List.copyOf(supportedWorkingTypeOrderTypes);
            supportedWorkingTypes = List.copyOf(supportedWorkingTypes);
            supportedPriceProtectOrderTypes = List.copyOf(supportedPriceProtectOrderTypes);
            supportedPeggedOrderTypes = List.copyOf(supportedPeggedOrderTypes);
            supportedPegPriceTypes = List.copyOf(supportedPegPriceTypes);
            supportedPegOffsetTypes = List.copyOf(supportedPegOffsetTypes);
            supportedMarginSideEffectTypes = List.copyOf(supportedMarginSideEffectTypes);
            autoRepayAtCancelSideEffectTypes = List.copyOf(autoRepayAtCancelSideEffectTypes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserDataStream(
            String mode,
            String startPath,
            String keepalivePath,
            String closePath,
            Integer validityMinutes,
            Integer renewalIntervalMinutes,
            Integer requestWeight
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnknownExecutionStatus(
            @NotNull List<String> retryableMessages,
            @NotNull List<Integer> reconcileBeforeRetryStatusCodes
    ) {
        public UnknownExecutionStatus {
            retryableMessages = List.copyOf(retryableMessages);
            reconcileBeforeRetryStatusCodes = List.copyOf(reconcileBeforeRetryStatusCodes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FuturesAccount(
            @NotNull String positionMode,
            @NotNull List<String> supportedPositionModes,
            @NotNull String positionModePath,
            @NotNull String marginTypePath,
            @NotNull String leveragePath,
            @NotNull String balancePath,
            @NotNull String accountInfoPath,
            @NotNull String positionRiskPath,
            @NotNull String adlQuantilePath,
            @NotNull String forceOrdersPath,
            @NotNull String incomePath,
            @NotNull String fundingRatePath,
            String multiAssetsModePath,
            Integer minInitialLeverage,
            Integer maxInitialLeverage,
            @NotNull List<String> supportedMarginTypes,
            boolean multiAssetsModeExpected,
            boolean portfolioMarginExpected
    ) {
        public FuturesAccount {
            supportedPositionModes = List.copyOf(supportedPositionModes);
            supportedMarginTypes = List.copyOf(supportedMarginTypes);
        }
    }
}
