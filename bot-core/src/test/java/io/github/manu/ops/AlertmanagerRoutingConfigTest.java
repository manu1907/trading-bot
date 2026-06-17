package io.github.manu.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AlertmanagerRoutingConfigTest {

    private static final String RENDERER = "ops/alertmanager/render-google-cloud-alertmanager.sh";

    @Test
    void pause_governance_alertmanager_routes_all_prometheus_routing_hints() throws IOException {
        Map<String, Object> alertmanager = yaml("ops/alertmanager/pause-governance-alertmanager.yml");
        Map<String, Object> prometheusRules = yaml("ops/prometheus/pause-governance-alerts.yml");

        Set<String> receivers = receiverNames(alertmanager);
        assertThat(receivers).contains(
                "trading-bot-fallback",
                "trading-bot-platform-critical",
                "trading-bot-operator-critical",
                "trading-bot-operator-warning",
                "trading-bot-operator-info"
        );

        List<Map<String, Object>> routes = routeList(alertmanager);
        assertRoute(routes, "platform", "critical", "trading-bot-platform-critical");
        assertRoute(routes, "operator", "critical", "trading-bot-operator-critical");
        assertRoute(routes, "operator", "warning", "trading-bot-operator-warning");
        assertRoute(routes, "operator", "info", "trading-bot-operator-info");

        Set<String> prometheusHints = prometheusRoutingHints(prometheusRules);
        assertThat(prometheusHints).containsExactlyInAnyOrder("operator", "platform");
    }

    @Test
    void google_cloud_renderer_maps_all_required_substitutions_without_printing_secrets() throws IOException {
        String script = Files.readString(resolve(RENDERER));

        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("demo | real")
                .contains("--validate-placeholders-only")
                .contains("secret_latest_value()")
                .contains("gcloud secrets versions access latest")
                .contains("trading-bot-%s-alert-operator-pagerduty-routing-key")
                .contains("trading-bot-%s-alert-platform-pagerduty-routing-key")
                .contains("trading-bot-%s-alert-operator-slack-webhook")
                .contains("trading-bot-%s-alert-operator-slack-channel")
                .contains("trading-bot-%s-alert-platform-slack-webhook")
                .contains("trading-bot-%s-alert-platform-slack-channel")
                .contains("trading-bot-%s-alert-fallback-slack-webhook")
                .contains("trading-bot-%s-alert-fallback-slack-channel")
                .contains("chmod 0600 \"$output\"")
                .doesNotContain("hooks.slack.com")
                .doesNotContain("routing_key:");
    }

    @Test
    void alertmanager_template_uses_exactly_supported_secret_placeholders() throws IOException {
        String template = Files.readString(resolve("ops/alertmanager/pause-governance-alertmanager.yml"));

        assertThat(template)
                .contains("${ALERTMANAGER_TRADING_BOT_OPERATOR_PAGERDUTY_ROUTING_KEY}")
                .contains("${ALERTMANAGER_TRADING_BOT_PLATFORM_PAGERDUTY_ROUTING_KEY}")
                .contains("${ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_WEBHOOK}")
                .contains("${ALERTMANAGER_TRADING_BOT_OPERATOR_SLACK_CHANNEL}")
                .contains("${ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_WEBHOOK}")
                .contains("${ALERTMANAGER_TRADING_BOT_PLATFORM_SLACK_CHANNEL}")
                .contains("${ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_WEBHOOK}")
                .contains("${ALERTMANAGER_TRADING_BOT_FALLBACK_SLACK_CHANNEL}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String path) throws IOException {
        try (var reader = Files.newBufferedReader(resolve(path))) {
            return new Yaml().loadAs(reader, Map.class);
        }
    }

    private Path resolve(String path) {
        Path cwd = Path.of(path);
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path parent = Path.of("..").resolve(path).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate " + path);
    }

    @SuppressWarnings("unchecked")
    private Set<String> receiverNames(Map<String, Object> alertmanager) {
        return ((List<Map<String, Object>>) alertmanager.get("receivers"))
                .stream()
                .map(receiver -> (String) receiver.get("name"))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routeList(Map<String, Object> alertmanager) {
        Map<String, Object> route = (Map<String, Object>) alertmanager.get("route");
        return (List<Map<String, Object>>) route.get("routes");
    }

    @SuppressWarnings("unchecked")
    private Set<String> prometheusRoutingHints(Map<String, Object> prometheusRules) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) prometheusRules.get("groups");
        return groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream())
                .map(rule -> (Map<String, Object>) rule.get("labels"))
                .map(labels -> (String) labels.get("routing_hint"))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private void assertRoute(
            List<Map<String, Object>> routes,
            String routingHint,
            String severity,
            String receiver
    ) {
        assertThat(routes)
                .anySatisfy(route -> {
                    assertThat(route.get("receiver")).isEqualTo(receiver);
                    List<String> matchers = (List<String>) route.get("matchers");
                    assertThat(matchers)
                            .contains(
                                    "service=\"trading-bot\"",
                                    "routing_hint=\"" + routingHint + "\"",
                                    "severity=\"" + severity + "\""
                            );
                });
    }
}
