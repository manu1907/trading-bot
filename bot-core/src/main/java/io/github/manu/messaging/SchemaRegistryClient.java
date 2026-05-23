package io.github.manu.messaging;

import org.apache.avro.Schema;

public interface SchemaRegistryClient {

    int register(String subject, Schema schema);

    Schema schemaById(int id);
}
