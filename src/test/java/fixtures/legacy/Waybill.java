package fixtures.legacy;

import java.util.ArrayList;
import java.util.List;

/** A JavaBean-style entity: no-arg constructor, setters, one field initialised where it is declared. */
public class Waybill {
    private Long id;
    private String reference;
    private int priority;
    private boolean urgent;
    private final List<String> stops = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public List<String> getStops() {
        return stops;
    }
}
