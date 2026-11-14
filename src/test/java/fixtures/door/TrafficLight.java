package fixtures.door;

/** Enum-encoded state machine: transitions are return values of next(). */
public enum TrafficLight {
    RED, GREEN, YELLOW, FLASHING;

    public TrafficLight next() {
        switch (this) {
            case RED:
                return GREEN;
            case GREEN:
                return YELLOW;
            case YELLOW:
                return RED;
            default:
                return FLASHING;
        }
    }
}
