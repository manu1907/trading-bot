package io.github.manu.messaging;

import io.github.manu.config.JsonMapperFactory;
import org.apache.avro.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class RedpandaSchemaRegistryClient implements SchemaRegistryClient {

    private static final String SCHEMA_REGISTRY_CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper jsonMapper;

    public RedpandaSchemaRegistryClient(String baseUrl) {
        this(URI.create(requireText(baseUrl, "baseUrl")), HttpClient.newHttpClient(), JsonMapperFactory.create());
    }

    RedpandaSchemaRegistryClient(URI baseUri, HttpClient httpClient, ObjectMapper jsonMapper) {
        this.baseUri = stripTrailingSlash(Objects.requireNonNull(baseUri, "baseUri"));
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public int register(String subject, Schema schema) {
        Objects.requireNonNull(schema, "schema");
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("schemaType", "AVRO");
        body.put("schema", schema.toString());

        HttpRequest request = HttpRequest.newBuilder(registryUri("/subjects/" + encode(subject) + "/versions"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", SCHEMA_REGISTRY_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();

        JsonNode response = sendJson(request, "register schema subject " + subject);
        return response.required("id").asInt();
    }

    @Override
    public Schema schemaById(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("schema id must be positive");
        }
        HttpRequest request = HttpRequest.newBuilder(registryUri("/schemas/ids/" + id))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        JsonNode response = sendJson(request, "load schema id " + id);
        return new Schema.Parser().parse(response.required("schema").asString());
    }

    private JsonNode sendJson(HttpRequest request, String action) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new SchemaRegistryException("Failed to " + action + ": HTTP " + status);
            }
            return jsonMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new SchemaRegistryException("Failed to " + action, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SchemaRegistryException("Interrupted while attempting to " + action, ex);
        }
    }

    private URI registryUri(String path) {
        return baseUri.resolve(path);
    }

    private static URI stripTrailingSlash(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/")) {
            return URI.create(value.substring(0, value.length() - 1));
        }
        return uri;
    }

    private static String encode(String value) {
        return URLEncoder.encode(requireText(value, "value"), StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
