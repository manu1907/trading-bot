package io.github.manu.projection;

import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProjectionConfiguration.class);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void keeps_snapshot_store_disabled_by_default() {
        contextRunner
                .withBean(TradingStateProjection.class, TradingStateProjection::new)
                .run(context -> assertThat(context)
                        .hasSingleBean(ProjectionProperties.class)
                        .doesNotHaveBean(TradingStateProjectionStore.class));
    }

    @Test
    void registers_projection_handlers_for_replayable_state_events() {
        contextRunner
                .withBean(TradingStateProjection.class, TradingStateProjection::new)
                .run(context -> {
                    List<TradingEventType> eventTypes = context.getBeansOfType(TradingEventHandlerRegistration.class)
                            .values()
                            .stream()
                            .map(TradingEventHandlerRegistration::eventType)
                            .toList();

                    assertThat(eventTypes).contains(
                            TradingEventType.ORDER_COMMAND,
                            TradingEventType.BALANCE_UPDATE,
                            TradingEventType.POSITION_UPDATE,
                            TradingEventType.ORDER_RESULT,
                            TradingEventType.EXECUTION_REPORT,
                            TradingEventType.RISK_UPDATE,
                            TradingEventType.RISK_DECISION,
                            TradingEventType.STRATEGY_SIGNAL,
                            TradingEventType.INTERVENTION_ACKNOWLEDGEMENT,
                            TradingEventType.REMEDIATION_DECISION
                    );
                    assertThat(context.getBean("orderCommandProjectionHandler", TradingEventHandlerRegistration.class).live())
                            .isFalse();
                    assertThat(context.getBean("orderCommandProjectionHandler", TradingEventHandlerRegistration.class).replay())
                            .isTrue();
                    assertThat(context.getBean("remediationDecisionProjectionHandler", TradingEventHandlerRegistration.class).live())
                            .isTrue();
                    assertThat(context.getBean("remediationDecisionProjectionHandler", TradingEventHandlerRegistration.class).replay())
                            .isTrue();
                });
    }

    @Test
    void creates_file_snapshot_store_when_enabled() {
        Path snapshotPath = temporaryDirectory.resolve("projection.json");

        contextRunner
                .withBean(TradingStateProjection.class, TradingStateProjection::new)
                .withPropertyValues(
                        "trading.projection.snapshot-store.enabled=true",
                        "trading.projection.snapshot-store.path=" + snapshotPath
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TradingStateProjectionStore.class);
                    assertThat(context).hasSingleBean(FileTradingStateProjectionStore.class);
                    assertThat(context).hasSingleBean(SmartLifecycle.class);

                    ProjectionProperties properties = context.getBean(ProjectionProperties.class);
                    assertThat(properties.snapshotStore().enabled()).isTrue();
                    assertThat(properties.snapshotStore().path()).isEqualTo(snapshotPath);
                });
    }

    @Test
    void creates_jdbc_snapshot_store_when_enabled() {
        contextRunner
                .withBean(TradingStateProjection.class, TradingStateProjection::new)
                .withPropertyValues(
                        "trading.projection.jdbc-store.enabled=true",
                        "trading.projection.jdbc-store.url=jdbc:h2:mem:projection-config;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                        "trading.projection.jdbc-store.table-prefix=trading_projection_",
                        "trading.projection.jdbc-store.initialize-schema=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TradingStateProjectionStore.class);
                    assertThat(context).hasSingleBean(JdbcTradingStateProjectionStore.class);
                    assertThat(context).hasSingleBean(SmartLifecycle.class);

                    ProjectionProperties properties = context.getBean(ProjectionProperties.class);
                    assertThat(properties.jdbcStore().enabled()).isTrue();
                    assertThat(properties.jdbcStore().tablePrefix()).isEqualTo("trading_projection_");
                });
    }
}
