package io.github.manu.exchange;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExchangeMetadataService {

    private final Map<String, ExchangeMetadataFetcher> fetchersByProvider;

    public ExchangeMetadataService(List<ExchangeMetadataFetcher> fetchers) {
        this.fetchersByProvider = fetchers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        fetcher -> fetcher.provider().toLowerCase(),
                        Function.identity()
                ));
    }

    public Optional<? extends ExchangeMetadata> current(String provider) {
        ExchangeMetadataFetcher fetcher = fetchersByProvider.get(provider.toLowerCase());
        if (fetcher == null) {
            return Optional.empty();
        }
        return fetcher.current();
    }

    public void refresh(ResolvedExchangeConfig config) {
        ExchangeMetadataFetcher fetcher = fetchersByProvider.get(config.provider().toLowerCase());
        if (fetcher == null) {
            return;
        }
        fetcher.refresh(config);
    }
}
