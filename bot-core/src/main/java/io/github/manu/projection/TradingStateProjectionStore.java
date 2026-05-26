package io.github.manu.projection;

import java.util.Optional;

public interface TradingStateProjectionStore {

    Optional<TradingStateSnapshot> load();

    void save(TradingStateSnapshot snapshot);
}
