package io.github.manu.position;

import org.springframework.stereotype.Component;

@Component
public class PositionManager {
    // In production this would track open positions via WebSocket or REST.
    public boolean hasOpenPositions() {
        // Placeholder: eventually query real positions
        return false;
    }
}
