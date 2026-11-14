package fixtures.shipping;

import java.util.logging.Logger;

/** Collaborators include a java.util.logging Logger, injected like Cargo Tracker's @Inject Logger. */
public class HandlingService {
    private final Logger logger;
    private final EventRepository repository;

    public HandlingService(Logger logger, EventRepository repository) {
        this.logger = logger;
        this.repository = repository;
    }

    public boolean register(HandlingEvent event) {
        if (event.happenedAt("SEGOT")) {
            logger.info("Event at Gothenburg");
            repository.store(event);
            return true;
        }
        return false;
    }
}
