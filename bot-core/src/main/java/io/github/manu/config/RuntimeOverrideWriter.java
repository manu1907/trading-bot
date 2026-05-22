package io.github.manu.config;

import io.github.manu.config.profile.ActiveProfileProvider;
import io.github.manu.config.profile.RuntimeProfile;
import io.github.manu.config.properties.ExchangeProperties;
import io.github.manu.config.properties.TradingBotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeOverrideWriter {

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final ActiveProfileProvider profileProvider;
    private final ActiveTargetResolver activeTargetResolver;
    private final ConfigLoader configLoader;
    private final ConfigValidator configValidator;
    private final ConfigFileLayout fileLayout;

    @Autowired
    public RuntimeOverrideWriter(ActiveProfileProvider profileProvider,
                                 ActiveTargetResolver activeTargetResolver,
                                 ConfigLoader configLoader,
                                 ConfigValidator configValidator) {
        this(profileProvider, activeTargetResolver, configLoader, configValidator, ExternalConfigDirectory.resolve());
    }

    RuntimeOverrideWriter(ActiveProfileProvider profileProvider,
                          ActiveTargetResolver activeTargetResolver,
                          ConfigLoader configLoader,
                          ConfigValidator configValidator,
                          Path configDir) {
        this.profileProvider = profileProvider;
        this.activeTargetResolver = activeTargetResolver;
        this.configLoader = configLoader;
        this.configValidator = configValidator;
        this.fileLayout = new ConfigFileLayout(configDir);
    }

    public Path writeActiveRuntimeOverride(ObjectNode patch) {
        if (ConfigTreeOperations.isEmptyObject(patch)) {
            throw new IllegalArgumentException("Runtime override patch must not be empty");
        }

        RuntimeProfile profile = profileProvider.activeProfile();
        if (profile != RuntimeProfile.LIVE) {
            throw new IllegalStateException("Runtime override writes are supported only for the live profile");
        }

        try {
            ActiveTargetResolver.ActiveTargetSelection selection = activeTargetResolver.resolveSelection();
            ExchangeProperties active = selection.target();
            Path runtimeFile = fileLayout.runtimeOverrideFile(profile, active);
            ObjectNode existingFileOverrides = readOptionalObjectNode(runtimeFile);
            ObjectNode candidateFileOverrides = existingFileOverrides.deepCopy();
            ConfigTreeOperations.mergeObjectNodes(candidateFileOverrides, patch);

            if (existingFileOverrides.equals(candidateFileOverrides)) {
                throw new IllegalArgumentException("Runtime override patch does not change the active runtime file");
            }

            ObjectNode candidateRuntimeOverrides = selection.runtimeOverrides();
            ConfigTreeOperations.mergeObjectNodes(candidateRuntimeOverrides, candidateFileOverrides);
            TradingBotProperties candidateConfig;
            try {
                candidateConfig = configLoader.loadLiveBaseline(active, candidateRuntimeOverrides);
            } catch (RuntimeException e) {
                throw unwrapConfigCandidateFailure(e);
            }
            configValidator.validate(candidateConfig);

            fileLayout.writeObjectAtomically(runtimeFile, candidateFileOverrides);
            return runtimeFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write runtime override file", e);
        }
    }

    private ObjectNode readOptionalObjectNode(Path path) throws IOException {
        if (!Files.exists(path)) {
            return jsonMapper.createObjectNode();
        }

        JsonNode node = jsonMapper.readTree(path.toFile());
        if (node == null || !node.isObject()) {
            throw new IOException("Expected root JSON object in " + path);
        }
        return (ObjectNode) node;
    }

    private RuntimeException unwrapConfigCandidateFailure(RuntimeException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof IllegalArgumentException illegalArgumentException) {
            return illegalArgumentException;
        }
        return exception;
    }
}
