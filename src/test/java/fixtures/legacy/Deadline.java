package fixtures.legacy;

import java.time.LocalDate;

/** RouteSpecification-shaped: a date field compared with a date read from a collaborator. */
public class Deadline {
    private final LocalDate date;

    public Deadline(LocalDate date) {
        this.date = date;
    }

    public LocalDate getDate() {
        return date;
    }

    public boolean isMetBy(Schedule schedule) {
        return schedule != null && getDate().isAfter(schedule.finalArrival().toLocalDate());
    }
}
