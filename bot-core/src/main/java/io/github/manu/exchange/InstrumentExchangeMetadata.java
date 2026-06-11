package io.github.manu.exchange;

import java.util.List;

public interface InstrumentExchangeMetadata extends ExchangeMetadata {

    List<Instrument> instruments();

    record Instrument(
            String symbol,
            String status,
            String baseAsset,
            String quoteAsset,
            String contractType,
            List<String> orderTypes
    ) {
        public Instrument {
            orderTypes = orderTypes == null ? List.of() : List.copyOf(orderTypes);
        }
    }
}
