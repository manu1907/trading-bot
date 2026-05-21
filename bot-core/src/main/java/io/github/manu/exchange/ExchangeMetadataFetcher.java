package io.github.manu.exchange;

import java.util.Optional;

public interface ExchangeMetadataFetcher {
    String provider();

    Optional<? extends ExchangeMetadata> current();

    Optional<? extends ExchangeMetadata> refresh(ResolvedExchangeConfig config);
}
