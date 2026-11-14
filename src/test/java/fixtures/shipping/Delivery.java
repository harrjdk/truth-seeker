package fixtures.shipping;

/**
 * Cargo Tracker-style derived state: the status is computed from the last event, never assigned
 * as a constant, and the exhaustive switch makes UNKNOWN unreachable.
 */
public class Delivery {
    private final HandlingEvent lastEvent;
    private final TransportStatus transportStatus;

    public Delivery(HandlingEvent lastEvent) {
        this.lastEvent = lastEvent;
        this.transportStatus = calculateTransportStatus();
    }

    public TransportStatus getTransportStatus() {
        return transportStatus;
    }

    private TransportStatus calculateTransportStatus() {
        if (lastEvent == null) {
            return TransportStatus.NOT_RECEIVED;
        }
        switch (lastEvent.getType()) {
            case LOAD:
                return TransportStatus.ONBOARD_CARRIER;
            case UNLOAD:
            case RECEIVE:
                return TransportStatus.IN_PORT;
            case CLAIM:
                return TransportStatus.CLAIMED;
            default:
                return TransportStatus.UNKNOWN;
        }
    }
}
