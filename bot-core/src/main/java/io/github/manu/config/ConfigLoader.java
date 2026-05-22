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
            ActiveTargetResolver.ActiveTargetSelection selection = activeTargetResolver.resolveSelection();
            ExchangeProperties active = selection.target();
            ObjectNode runtimeOverrides = selection.runtimeOverrides();

            fileLayout.ensureEmptyRuntimeOverrideFile(profile, active);
            ConfigTreeOperations.mergeObjectNodes(
                    runtimeOverrides,
                    readOptionalObjectNode(fileLayout.runtimeOverrideFile(profile, active))
            );

            return loadLiveBaseline(active, runtimeOverrides);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline configuration", e);
        }
    }

    TradingBotProperties loadLiveBaseline(ExchangeProperties active, ObjectNode runtimeOverrides) {
        try {
            ObjectNode catalog = readObjectNode(fileLayout.catalogFile());
            ObjectNode exchangeNode = requiredObject(catalog, "exchange");
            exchangeNode.set("active", jsonMapper.valueToTree(active));

            ObjectNode environmentOverride = readObjectNode(fileLayout.environmentFile(active.environment()));
            ConfigTreeOperations.validatePatchPaths(catalog, environmentOverride, "", false);
            ConfigTreeOperations.rejectRootMetadataOverride(environmentOverride);
            ConfigTreeOperations.rejectActiveTargetOverride(environmentOverride);
            ConfigTreeOperations.mergeObjectNodes(catalog, environmentOverride);
            exchangeNode.set("active", jsonMapper.valueToTree(active));

            ConfigTreeOperations.validatePatchPaths(catalog, runtimeOverrides, "", true);
            ConfigTreeOperations.rejectRootMetadataOverride(runtimeOverrides);
            ConfigTreeOperations.rejectActiveTargetOverride(runtimeOverrides);
            ConfigTreeOperations.mergeObjectNodes(catalog, runtimeOverrides);
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
}
