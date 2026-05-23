package io.github.manu.messaging;

import java.time.Duration;
import java.util.List;

public interface TradingEventPoller {

    List<TradingEventDispatchResult> pollAndDispatch(Duration timeout);
}
