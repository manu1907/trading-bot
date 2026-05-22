package io.github.manu.config;

import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/// Loads external bot configuration.
/// Live mode merges catalog + active environment + active runtime target files.
/// Backtest mode uses only application-backtest.json.
@Component
public class ConfigLoader {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final ActiveTargetResolver activeTargetResolver;
    private final ConfigFileLayout fileLayout;

    @Autowired
    public ConfigLoader(ActiveTargetResolver activeTargetResolver) {
        this(activeTargetResolver, ExternalConfigDirectory.resolve());
    }

    ConfigLoader(ActiveTargetResolver activeTargetResolver, Path configDir) {
        this.activeTargetResolver = activeTargetResolver;
        this.fileLayout = new ConfigFileLayout(configDir);
    }

    /// Load the baseline config for the selected runtime profile.
    public TradingBotProperties loadBaseline(RuntimeProfile profile) {
        try {
            if (profile == RuntimeProfile.BACKTEST) {
                return jsonMapper.readValue(fileLayout.backtestFile().toFile(), TradingBotProperties.class);
            }

            ObjectNode catalog = readObjectNode(fileLayout.catalogFile());
            ObjectNode exchangeNode = requiredObject(catalog, "exchange");
            ActiveTargetResolver.ActiveTargetSelection selection = activeTargetResolver.resolveSelection();
            ExchangeProperties active = selection.target();

            exchangeNode.set("active", jsonMapper.valueToTree(active));

            ObjectNode environmentOverride = readObjectNode(fileLayout.environmentFile(active.environment()));
            validatePatchPaths(catalog, environmentOverride, "", false);
            rejectActiveTargetOverride(environmentOverride);
            mergeObjectNodes(catalog, environmentOverride);
            exchangeNode.set("active", jsonMapper.valueToTree(active));

            fileLayout.ensureEmptyRuntimeOverrideFile(profile, active);
            ObjectNode runtimeOverrides = selection.runtimeOverrides();
            mergeObjectNodes(runtimeOverrides, readOptionalObjectNode(fileLayout.runtimeOverrideFile(profile, active)));
            validatePatchPaths(catalog, runtimeOverrides, "", true);
            rejectActiveTargetOverride(runtimeOverrides);
            mergeObjectNodes(catalog, runtimeOverrides);
            exchangeNode.set("active", jsonMapper.valueToTree(active));

            return jsonMapper.treeToValue(catalog, TradingBotProperties.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline configuration", e);
        }
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

    private ObjectNode requiredObject(ObjectNode parent, String fieldName) throws IOException {
        JsonNode node = parent.get(fieldName);
        if (node == null || !node.isObject()) {
            throw new IOException("Expected JSON object field '" + fieldName + "'");
        }
        return (ObjectNode) node;
    }

    private void mergeObjectNodes(ObjectNode target, ObjectNode patch) {
        for (Map.Entry<String, JsonNode> property : patch.properties()) {
            JsonNode existing = target.get(property.getKey());
            JsonNode patchValue = property.getValue();
            if (existing instanceof ObjectNode existingObject && patchValue instanceof ObjectNode patchObject) {
                mergeObjectNodes(existingObject, patchObject);
            } else {
                target.set(property.getKey(), patchValue.deepCopy());
            }
        }
    }

    private void validatePatchPaths(ObjectNode baseline, ObjectNode patch, String path, boolean rejectNoop) {
        for (Map.Entry<String, JsonNode> property : patch.properties()) {
            String key = property.getKey();
            String currentPath = path.isEmpty() ? key : path + "." + key;
            JsonNode existing = baseline.get(key);
            JsonNode patchValue = property.getValue();

            if (existing == null) {
                throw new IllegalArgumentException("Runtime override path does not exist: " + currentPath);
            }

            if (existing instanceof ObjectNode existingObject && patchValue instanceof ObjectNode patchObject) {
                validatePatchPaths(existingObject, patchObject, currentPath, rejectNoop);
                continue;
            }

            if (existing.isObject() != patchValue.isObject()) {
                throw new IllegalArgumentException("Runtime override shape mismatch at path: " + currentPath);
            }

            if (rejectNoop && existing.equals(patchValue)) {
                throw new IllegalArgumentException("Runtime override is a no-op at path: " + currentPath);
            }
        }
    }

    private void rejectActiveTargetOverride(ObjectNode patch) {
        JsonNode exchange = patch.get("exchange");
        if (exchange != null && exchange.isObject() && exchange.has("active")) {
            throw new IllegalArgumentException("exchange.active cannot be overridden by config patches");
        }
    }
}
