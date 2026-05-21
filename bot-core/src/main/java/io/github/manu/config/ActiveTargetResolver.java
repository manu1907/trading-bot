package io.github.manu.config;

import io.github.manu.config.properties.ExchangeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Path;

@Component
public final class ActiveTargetResolver {

    private static final String PROVIDER_ENV = "BOT_PROVIDER";
    private static final String ENVIRONMENT_ENV = "BOT_ENVIRONMENT";
    private static final String ACCOUNT_ENV = "BOT_ACCOUNT";
    private static final String MARKET_ENV = "BOT_MARKET";

    private final Environment environment;
    private final ObjectMapper jsonMapper = JsonMapperFactory.create();
    private final Path configDir;

    @Autowired
    public ActiveTargetResolver(Environment environment) {
        this(environment, ExternalConfigDirectory.resolve());
    }

    ActiveTargetResolver(Environment environment, Path configDir) {
        this.environment = environment;
        this.configDir = configDir;
    }

    public ExchangeProperties resolve() {
        return resolveSelection().target();
    }

    public String source() {
        return resolveSelection().source();
    }

    public ActiveTargetSelection resolveSelection() {
        String provider = environment.getProperty(PROVIDER_ENV);
        String targetEnvironment = environment.getProperty(ENVIRONMENT_ENV);
        String account = environment.getProperty(ACCOUNT_ENV);
        String market = environment.getProperty(MARKET_ENV);

        if (provider != null || targetEnvironment != null || account != null || market != null) {
            if (provider == null || targetEnvironment == null || account == null || market == null) {
                throw new IllegalStateException(
                        "Active target env vars must be set together: BOT_PROVIDER, BOT_ENVIRONMENT, BOT_ACCOUNT, BOT_MARKET"
                );
            }
            return new ActiveTargetSelection(
                    new ExchangeProperties(provider, targetEnvironment, account, market),
                    jsonMapper.createObjectNode(),
                    "env"
            );
        }

        try {
            ObjectNode activeFile = readActiveFile();
            JsonNode activeNode = activeFile.get("active");
            if (activeNode != null && activeNode.isObject()) {
                JsonNode runtimeNode = activeFile.get("runtime");
                return new ActiveTargetSelection(
                        jsonMapper.treeToValue(activeNode, ExchangeProperties.class),
                        runtimeNode != null && runtimeNode.isObject()
                                ? (ObjectNode) runtimeNode
                                : jsonMapper.createObjectNode(),
                        "file"
                );
            }

            return new ActiveTargetSelection(
                    jsonMapper.treeToValue(activeFile, ExchangeProperties.class),
                    jsonMapper.createObjectNode(),
                    "file"
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Unable to resolve active target from env vars or config/active.json",
                    e
            );
        }
    }

    private ObjectNode readActiveFile() {
        JsonNode node = jsonMapper.readTree(new File(configDir.toFile(), "active.json"));
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Expected root JSON object in " + configDir.resolve("active.json"));
        }
        return (ObjectNode) node;
    }

    public record ActiveTargetSelection(ExchangeProperties target, ObjectNode runtimeOverrides, String source) {
        public ActiveTargetSelection {
            runtimeOverrides = runtimeOverrides.deepCopy();
        }

        @Override
        public ObjectNode runtimeOverrides() {
            return runtimeOverrides.deepCopy();
        }
    }
}
