package fixtures.clinic;

import java.util.ArrayList;
import java.util.List;

/** PetClinic-style entity. */
public class Pet {
    private Integer id;
    private String name;
    private final List<String> visits = new ArrayList<>();

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isNew() {
        return this.id == null;
    }

    public List<String> getVisits() { return visits; }

    public void addVisit(String visit) { this.visits.add(visit); }
}
