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

    @Override
    public String toString() {
        return "Deadline " + date + " (" + date.getDayOfWeek() + ")";
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(date, "deadline");
    }

    public boolean isMetBy(Schedule schedule) {
        return schedule != null && getDate().isAfter(schedule.finalArrival().toLocalDate());
    }
}
