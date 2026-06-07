package io.github.manu.config;

import io.github.manu.config.profile.RuntimeProfile;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds validated external JSON runtime overrides to Spring's environment before
 * conditional beans and {@code @ConfigurationProperties} are bound.
 */
public final class ExternalTradingRuntimeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "externalTradingRuntime";

    private final Path configDir;

    public ExternalTradingRuntimeEnvironmentPostProcessor() {
        this(ExternalConfigDirectory.resolve());
    }

    ExternalTradingRuntimeEnvironmentPostProcessor(Path configDir) {
        this.configDir = configDir;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isBacktest(environment)) {
            return;
        }

        Map<String, Object> properties = loadProperties(environment);
        if (properties.isEmpty()) {
            return;
        }

        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, properties);
        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.replace(PROPERTY_SOURCE_NAME, propertySource);
        } else if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }
    }

    Map<String, Object> loadProperties(ConfigurableEnvironment environment) {
        try {
            return new Loader(configDir, environment).load();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load external trading runtime Spring properties", exception);
        }
    }

    private boolean isBacktest(ConfigurableEnvironment environment) {
        for (String activeProfile : environment.getActiveProfiles()) {
            if ("backtest".equalsIgnoreCase(activeProfile)) {
                return true;
            }
        }
        String activeProfiles = environment.getProperty("spring.profiles.active");
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String activeProfile : activeProfiles.split(",")) {
            if ("backtest".equalsIgnoreCase(activeProfile.trim())) {
                return true;
            }
        }
        return false;
    }

    private static final class Loader {

        private final ObjectMapper jsonMapper = JsonMapperFactory.create();
        private final Path configDir;
        private final ConfigurableEnvironment environment;

        private Loader(Path configDir, ConfigurableEnvironment environment) {
            this.configDir = configDir;
            this.environment = environment;
        }

        private Map<String, Object> load() throws IOException {
            ConfigFileLayout fileLayout = new ConfigFileLayout(configDir);
            ActiveTargetResolver.ActiveTargetSelection selection =
                    new ActiveTargetResolver(environment, configDir).resolveSelection();

            ObjectNode catalog = readObjectNode(fileLayout.catalogFile());
            ObjectNode environmentOverride = readObjectNode(fileLayout.environmentFile(selection.target().environment()));
            ConfigTreeOperations.validatePatchPaths(catalog, environmentOverride, "", false);
            ConfigTreeOperations.rejectRootMetadataOverride(environmentOverride);
            ConfigTreeOperations.rejectActiveTargetOverride(environmentOverride);
            ConfigTreeOperations.mergeObjectNodes(catalog, environmentOverride);

            ObjectNode runtimeOverrides = selection.runtimeOverrides();
            ConfigTreeOperations.mergeObjectNodes(
                    runtimeOverrides,
                    readOptionalObjectNode(fileLayout.runtimeOverrideFile(RuntimeProfile.LIVE, selection.target()))
            );
            ConfigTreeOperations.validatePatchPaths(catalog, runtimeOverrides, "", true);
            ConfigTreeOperations.rejectRootMetadataOverride(runtimeOverrides);
            ConfigTreeOperations.rejectActiveTargetOverride(runtimeOverrides);
            ConfigTreeOperations.mergeObjectNodes(catalog, runtimeOverrides);

            JsonNode trading = catalog.get("trading");
            if (trading == null || !trading.isObject()) {
                return Map.of();
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            flatten("trading", trading, properties);
            return properties;
        }

        private ObjectNode readObjectNode(Path path) throws IOException {
            JsonNode node = jsonMapper.readTree(path.toFile());
            if (node == null || !node.isObject()) {
                throw new IOException("Expected root JSON object in " + path);
            }
            return (ObjectNode) node;
        }

        private ObjectNode readOptionalObjectNode(Path path) throws IOException {
            if (!path.toFile().exists()) {
                return jsonMapper.createObjectNode();
            }
            return readObjectNode(path);
        }

        private void flatten(String path, JsonNode node, Map<String, Object> properties) {
            if (node.isObject()) {
                node.properties().forEach(entry -> flatten(
                        path + "." + toKebabCase(entry.getKey()),
                        entry.getValue(),
                        properties
                ));
            } else if (node.isArray()) {
                flattenArray(path, node, properties);
            } else if (!node.isNull()) {
                properties.put(path, value(node));
            }
        }

        private void flattenArray(String path, JsonNode node, Map<String, Object> properties) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < node.size(); index++) {
                JsonNode item = node.get(index);
                if (item.isObject() || item.isArray()) {
                    flatten(path + "[" + index + "]", item, properties);
                } else if (!item.isNull()) {
                    Object value = value(item);
                    values.add(value);
                    properties.put(path + "[" + index + "]", value);
                }
            }
            properties.put(path, List.copyOf(values));
        }

        private Object value(JsonNode node) {
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isInt()) {
                return node.asInt();
            }
            if (node.isLong()) {
                return node.asLong();
            }
            if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
                return node.asDouble();
            }
            return node.asString();
        }

        private String toKebabCase(String key) {
            return key.replace('_', '-');
        }
    }
}
