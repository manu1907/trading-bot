package io.github.manu.messaging;

public final class SchemaRegistryException extends RuntimeException {

    public SchemaRegistryException(String message) {
        super(message);
    }

    public SchemaRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
