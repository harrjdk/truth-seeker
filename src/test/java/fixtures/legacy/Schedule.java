package fixtures.legacy;

import java.time.LocalDateTime;
import java.util.List;

/** Itinerary-shaped: a typed list field read only through a lambda, and a "last element" helper that may yield null. */
public class Schedule {

    public static class Port {
        private final String code;

        public Port(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static class Stop {
        private final Port port;
        private final LocalDateTime arrival;

        public Stop(Port port, LocalDateTime arrival) {
            this.port = port;
            this.arrival = arrival;
        }

        public Port getPort() {
            return port;
        }

        public LocalDateTime getArrival() {
            return arrival;
        }
    }

    private List<Stop> stops = List.of();

    public boolean visits(String code) {
        if (stops.isEmpty()) {
            return false;
        }
        return stops.stream().anyMatch(s -> s.getPort().getCode().equals(code));
    }

    /** Owner.addPet-shaped: the candidate's getters are re-read in every iteration but cannot change. */
    public boolean accepts(Stop candidate) {
        for (Stop s : stops) {
            if (candidate.getArrival() == null && s.getPort() == candidate.getPort()) {
                return false;
            }
        }
        return true;
    }

    public LocalDateTime finalArrival() {
        Stop last = lastStop();
        if (last == null) {
            return LocalDateTime.MAX;
        }
        return last.getArrival();
    }

    public Stop lastStop() { // public: the test harness loads generated tests in a child class loader
        if (stops.isEmpty()) {
            return null;
        }
        return stops.get(stops.size() - 1);
    }
}
