package io.github.manu.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

final class ConfigTreeOperations {

    private ConfigTreeOperations() {
    }

    static void mergeObjectNodes(ObjectNode target, ObjectNode patch) {
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

    static void validatePatchPaths(ObjectNode baseline, ObjectNode patch, String path, boolean rejectNoop) {
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

    static void rejectActiveTargetOverride(ObjectNode patch) {
        JsonNode exchange = patch.get("exchange");
        if (exchange != null && exchange.isObject() && exchange.has("active")) {
            throw new IllegalArgumentException("exchange.active cannot be overridden by config patches");
        }
    }

    static boolean isEmptyObject(ObjectNode node) {
        return !node.properties().iterator().hasNext();
    }
}
