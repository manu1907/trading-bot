package io.github.manu.exchange;

import java.time.Instant;

public interface ExchangeMetadata {
    String provider();
    Instant fetchedAt();
}
