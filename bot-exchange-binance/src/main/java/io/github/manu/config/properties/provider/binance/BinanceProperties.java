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
        @Valid MarginAccount marginAccount,
        @Valid FuturesAccount futuresAccount,
        @Valid MarketDataStream marketData,
        @Valid Reconciliation reconciliation,
        @Valid OptionsAccount optionsAccount
) {
    public BinanceProperties(
            String marketType,
            Credentials credentials,
            Rest rest,
            Websocket websocket,
            Trading trading,
            UserDataStream userDataStream,
            MarginAccount marginAccount,
            FuturesAccount futuresAccount
    ) {
        this(
                marketType,
                credentials,
                rest,
                websocket,
                trading,
                userDataStream,
                marginAccount,
                futuresAccount,
                MarketDataStream.disabled(),
                Reconciliation.disabled(),
                null
        );
    }

    public BinanceProperties(
            String marketType,
            Credentials credentials,
            Rest rest,
            Websocket websocket,
            Trading trading,
            UserDataStream userDataStream,
            MarginAccount marginAccount,
            FuturesAccount futuresAccount,
            MarketDataStream marketData
    ) {
        this(
                marketType,
                credentials,
                rest,
                websocket,
                trading,
                userDataStream,
                marginAccount,
                futuresAccount,
                marketData,
                Reconciliation.disabled(),
                null
        );
    }

    public BinanceProperties(
            String marketType,
            Credentials credentials,
            Rest rest,
            Websocket websocket,
            Trading trading,
            UserDataStream userDataStream,
            MarginAccount marginAccount,
            FuturesAccount futuresAccount,
            MarketDataStream marketData,
            Reconciliation reconciliation
    ) {
        this(
                marketType,
                credentials,
                rest,
                websocket,
                trading,
                userDataStream,
                marginAccount,
                futuresAccount,
                marketData,
                reconciliation,
                null
        );
    }

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
            @NotNull String timestampUnit,
            String apiBaseUrl,
            String apiPath
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
            String cancelReplacePath,
            String sorOrderPath,
            String sorTestOrderPath,
            String orderListOcoPath,
            String orderListOtoPath,
            String orderListOtocoPath,
            String orderListOpoPath,
            String orderListOpocoPath,
            String batchOrdersPath,
            String modifyOrderPath,
            String modifyMultipleOrdersPath,
            String modifyOrderHistoryPath,
            String cancelMultipleOrdersPath,
            String cancelAllOpenOrdersPath,
            String countdownCancelAllPath,
            String exerciseRecordPath,
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
            boolean supportsMarketMakerProtection,
            boolean supportsPostOnly
    ) {
        public Trading(
                String newOrderPath,
                String testOrderPath,
                String queryOrderPath,
                String cancelOrderPath,
                String openOrdersPath,
                String allOrdersPath,
                String accountTradesPath,
                String commissionRatesPath,
                String preventedMatchesPath,
                String amendKeepPriorityPath,
                String cancelReplacePath,
                String sorOrderPath,
                String sorTestOrderPath,
                String orderListOcoPath,
                String orderListOtoPath,
                String orderListOtocoPath,
                String orderListOpoPath,
                String orderListOpocoPath,
                String batchOrdersPath,
                String modifyOrderPath,
                String modifyMultipleOrdersPath,
                String modifyOrderHistoryPath,
                String cancelMultipleOrdersPath,
                String cancelAllOpenOrdersPath,
                String countdownCancelAllPath,
                String exerciseRecordPath,
                List<String> supportedSides,
                List<String> supportedOrderTypes,
                List<String> supportedTimeInForce,
                List<String> supportedOrderResponseTypes,
                List<String> supportedSelfTradePreventionModes,
                List<String> supportedPositionSides,
                List<String> supportedPriceMatchOrderTypes,
                List<String> supportedWorkingTypeOrderTypes,
                List<String> supportedWorkingTypes,
                List<String> supportedPriceProtectOrderTypes,
                List<String> supportedPeggedOrderTypes,
                List<String> supportedPegPriceTypes,
                List<String> supportedPegOffsetTypes,
                List<String> supportedMarginSideEffectTypes,
                List<String> autoRepayAtCancelSideEffectTypes,
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
            this(
                    newOrderPath,
                    testOrderPath,
                    queryOrderPath,
                    cancelOrderPath,
                    openOrdersPath,
                    allOrdersPath,
                    accountTradesPath,
                    commissionRatesPath,
                    preventedMatchesPath,
                    amendKeepPriorityPath,
                    cancelReplacePath,
                    sorOrderPath,
                    sorTestOrderPath,
                    orderListOcoPath,
                    orderListOtoPath,
                    orderListOtocoPath,
                    orderListOpoPath,
                    orderListOpocoPath,
                    batchOrdersPath,
                    modifyOrderPath,
                    modifyMultipleOrdersPath,
                    modifyOrderHistoryPath,
                    cancelMultipleOrdersPath,
                    cancelAllOpenOrdersPath,
                    countdownCancelAllPath,
                    exerciseRecordPath,
                    supportedSides,
                    supportedOrderTypes,
                    supportedTimeInForce,
                    supportedOrderResponseTypes,
                    supportedSelfTradePreventionModes,
                    supportedPositionSides,
                    supportedPriceMatchOrderTypes,
                    supportedWorkingTypeOrderTypes,
                    supportedWorkingTypes,
                    supportedPriceProtectOrderTypes,
                    supportedPeggedOrderTypes,
                    supportedPegPriceTypes,
                    supportedPegOffsetTypes,
                    supportedMarginSideEffectTypes,
                    autoRepayAtCancelSideEffectTypes,
                    maxPegOffsetValue,
                    supportsQuoteOrderQty,
                    supportsReduceOnly,
                    supportsClosePosition,
                    supportsPriceMatch,
                    supportsWorkingType,
                    supportsPriceProtect,
                    supportsPeggedOrders,
                    supportsIcebergQty,
                    supportsTrailingDelta,
                    supportsMarginSideEffectControls,
                    supportsIsolatedMarginFlag,
                    supportsMarketMakerProtection,
                    false
            );
        }

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
            Boolean runtimeEnabled,
            String startPath,
            String keepalivePath,
            String closePath,
            Integer validityMinutes,
            Integer renewalIntervalMinutes,
            Integer requestWeight
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketDataStream(
            Boolean runtimeEnabled,
            String connectionMode,
            String route,
            @NotNull List<String> streams
    ) {
        public MarketDataStream {
            streams = streams == null ? List.of() : List.copyOf(streams);
        }

        static MarketDataStream disabled() {
            return new MarketDataStream(false, "combined", "default", List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reconciliation(
            Boolean runtimeEnabled,
            Integer intervalSeconds,
            Integer dedupeWindowEventIds,
            Boolean projectionComparisonEnabled,
            Boolean failOnProjectionMismatch,
            Boolean openOrdersEnabled,
            @NotNull List<String> openOrderSymbols,
            Boolean futuresBalancesEnabled,
            Boolean futuresAccountEnabled,
            Boolean futuresPositionsEnabled,
            Boolean crossMarginAccountEnabled,
            Boolean isolatedMarginAccountEnabled,
            @NotNull List<String> isolatedMarginSymbols,
            Boolean optionsAccountEnabled,
            Boolean optionsPositionsEnabled,
            @NotNull List<String> optionsPositionSymbols
    ) {
        public Reconciliation {
            openOrderSymbols = openOrderSymbols == null ? List.of() : List.copyOf(openOrderSymbols);
            isolatedMarginSymbols = isolatedMarginSymbols == null ? List.of() : List.copyOf(isolatedMarginSymbols);
            optionsPositionSymbols = optionsPositionSymbols == null ? List.of() : List.copyOf(optionsPositionSymbols);
        }

        public Reconciliation(
                Boolean runtimeEnabled,
                Integer intervalSeconds,
                Integer dedupeWindowEventIds,
                Boolean openOrdersEnabled,
                List<String> openOrderSymbols,
                Boolean futuresBalancesEnabled,
                Boolean futuresAccountEnabled,
                Boolean futuresPositionsEnabled,
                Boolean crossMarginAccountEnabled,
                Boolean isolatedMarginAccountEnabled,
                List<String> isolatedMarginSymbols
        ) {
            this(
                    runtimeEnabled,
                    intervalSeconds,
                    dedupeWindowEventIds,
                    true,
                    false,
                    openOrdersEnabled,
                    openOrderSymbols,
                    futuresBalancesEnabled,
                    futuresAccountEnabled,
                    futuresPositionsEnabled,
                    crossMarginAccountEnabled,
                    isolatedMarginAccountEnabled,
                    isolatedMarginSymbols,
                    false,
                    false,
                    List.of()
            );
        }

        public Reconciliation(
                Boolean runtimeEnabled,
                Integer intervalSeconds,
                Boolean openOrdersEnabled,
                List<String> openOrderSymbols,
                Boolean futuresBalancesEnabled,
                Boolean futuresAccountEnabled,
                Boolean futuresPositionsEnabled,
                Boolean crossMarginAccountEnabled,
                Boolean isolatedMarginAccountEnabled,
                List<String> isolatedMarginSymbols
        ) {
            this(
                    runtimeEnabled,
                    intervalSeconds,
                    10_000,
                    true,
                    false,
                    openOrdersEnabled,
                    openOrderSymbols,
                    futuresBalancesEnabled,
                    futuresAccountEnabled,
                    futuresPositionsEnabled,
                    crossMarginAccountEnabled,
                    isolatedMarginAccountEnabled,
                    isolatedMarginSymbols,
                    false,
                    false,
                    List.of()
            );
        }

        static Reconciliation disabled() {
            return new Reconciliation(
                    false,
                    60,
                    10_000,
                    true,
                    false,
                    false,
                    List.of(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    false,
                    false,
                    List.of()
            );
        }
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
    public record MarginAccount(
            @NotNull String borrowRepayPath,
            @NotNull String transferHistoryPath,
            @NotNull String maxTransferablePath,
            @NotNull String crossAccountPath,
            @NotNull String isolatedAccountPath,
            @NotNull String isolatedAccountLimitPath,
            @NotNull String tradeCoeffPath,
            @NotNull String specialKeyListPath,
            @NotNull String specialKeyPath,
            @NotNull String specialKeyIpPath,
            @NotNull String specialKeyExitModePath,
            @NotNull List<String> supportedBorrowRepayTypes,
            @NotNull List<String> supportedTransferHistoryTypes,
            @NotNull List<String> supportedSpecialKeyPermissionModes,
            Integer maxTransferHistoryDays,
            Integer maxTransferHistorySize,
            Integer maxIsolatedAccountSymbols,
            Integer maxSpecialKeyIps,
            boolean specialKeyMutationsEnabled,
            boolean specialKeyExitEnabled
    ) {
        public MarginAccount {
            supportedBorrowRepayTypes = List.copyOf(supportedBorrowRepayTypes);
            supportedTransferHistoryTypes = List.copyOf(supportedTransferHistoryTypes);
            supportedSpecialKeyPermissionModes = List.copyOf(supportedSpecialKeyPermissionModes);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionsAccount(
            @NotNull String marginAccountPath,
            @NotNull String positionPath,
            @NotNull String marketMakerProtectionPath,
            @NotNull String marketMakerProtectionSetPath,
            @NotNull String marketMakerProtectionResetPath,
            @NotNull String autoCancelAllOpenOrdersPath,
            @NotNull String autoCancelAllOpenOrdersHeartbeatPath,
            Integer maxMarketMakerProtectionWindowMillis,
            Integer minAutoCancelAllOpenOrdersCountdownMillis,
            boolean marketMakerProtectionMutationsEnabled,
            boolean autoCancelAllOpenOrdersMutationsEnabled
    ) {
    }
}
