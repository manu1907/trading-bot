package io.github.manu.messaging;

import org.apache.avro.Schema;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class InMemorySchemaRegistryClient implements SchemaRegistryClient {

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, Integer> idsBySubject = new ConcurrentHashMap<>();
    private final Map<Integer, Schema> schemasById = new ConcurrentHashMap<>();

    @Override
    public int register(String subject, Schema schema) {
        return idsBySubject.computeIfAbsent(subject, ignored -> {
            int id = nextId.getAndIncrement();
            schemasById.put(id, schema);
            return id;
        });
    }

    @Override
    public Schema schemaById(int id) {
        Schema schema = schemasById.get(id);
        if (schema == null) {
            throw new SchemaRegistryException("Unknown schema id " + id);
        }
        return schema;
    }
}
