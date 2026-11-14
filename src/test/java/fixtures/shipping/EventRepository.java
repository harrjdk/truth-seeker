package fixtures.shipping;

public interface EventRepository {
    void store(HandlingEvent event);
}
