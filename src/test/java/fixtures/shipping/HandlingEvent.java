package fixtures.shipping;

import java.time.LocalDateTime;
import java.util.Objects;

/** Cargo Tracker-style value object: no no-arg constructor, validated constructor arguments. */
public class HandlingEvent {
    private final EventType type;
    private final LocalDateTime completionTime;
    private final String location;

    public HandlingEvent(EventType type, LocalDateTime completionTime, String location) {
        this.type = Objects.requireNonNull(type, "Handling event type is required.");
        this.completionTime = Objects.requireNonNull(completionTime, "Completion time is required");
        this.location = Objects.requireNonNull(location, "Location is required");
    }

    public EventType getType() {
        return type;
    }

    public LocalDateTime getCompletionTime() {
        return completionTime;
    }

    public boolean happenedAt(String place) {
        return location.equals(place);
    }

    /** Typical hand-written value-object equals: identity, null/type checks, then fields. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HandlingEvent other = (HandlingEvent) o;
        return type == other.type && location.equals(other.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, location);
    }
}
