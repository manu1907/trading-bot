package io.github.manu.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/// Loads external bot configuration.
/// Live mode uses catalog.json + active target resolution.
/// Backtest mode uses only application-backtest.json.
@Component
public class ConfigLoader {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final ActiveTargetResolver activeTargetResolver;
    private final Path configDir;

    @Autowired
    public ConfigLoader(ActiveTargetResolver activeTargetResolver) {
        this(activeTargetResolver, ExternalConfigDirectory.resolve());
    }

    ConfigLoader(ActiveTargetResolver activeTargetResolver, Path configDir) {
        this.activeTargetResolver = activeTargetResolver;
        this.configDir = configDir;
    }

    /// Load the baseline config for the selected runtime profile.
    public TradingBotProperties loadBaseline(RuntimeProfile profile) {
        try {
            if (profile == RuntimeProfile.BACKTEST) {
                return jsonMapper.readValue(new File(configDir.toFile(), "application-backtest.json"), TradingBotProperties.class);
            }

            ObjectNode catalog = readObjectNode(new File(configDir.toFile(), "catalog.json"));
            ObjectNode exchangeNode = requiredObject(catalog, "exchange");
            ActiveTargetResolver.ActiveTargetSelection selection = activeTargetResolver.resolveSelection();
            ExchangeProperties active = selection.target();
            exchangeNode.set("active", jsonMapper.valueToTree(active));
            validateRuntimeOverrides(catalog, selection.runtimeOverrides(), "");
            mergeObjectNodes(catalog, selection.runtimeOverrides());
            exchangeNode.set("active", jsonMapper.valueToTree(active));
            return jsonMapper.treeToValue(catalog, TradingBotProperties.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline configuration", e);
        }
    }

    private ObjectNode readObjectNode(File file) throws IOException {
        JsonNode node = jsonMapper.readTree(file);
        if (node == null || !node.isObject()) {
            throw new IOException("Expected root JSON object in " + file.getPath());
        }
        return (ObjectNode) node;
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

    private void validateRuntimeOverrides(ObjectNode baseline, ObjectNode patch, String path) {
        for (Map.Entry<String, JsonNode> property : patch.properties()) {
            String key = property.getKey();
            String currentPath = path.isEmpty() ? key : path + "." + key;
            JsonNode existing = baseline.get(key);
            JsonNode patchValue = property.getValue();

            if (existing == null) {
                throw new IllegalArgumentException("Runtime override path does not exist: " + currentPath);
            }

            if (existing instanceof ObjectNode existingObject && patchValue instanceof ObjectNode patchObject) {
                validateRuntimeOverrides(existingObject, patchObject, currentPath);
                continue;
            }

            if (existing.isObject() != patchValue.isObject()) {
                throw new IllegalArgumentException("Runtime override shape mismatch at path: " + currentPath);
            }

            if (existing.equals(patchValue)) {
                throw new IllegalArgumentException("Runtime override is a no-op at path: " + currentPath);
            }
        }
    }
}
