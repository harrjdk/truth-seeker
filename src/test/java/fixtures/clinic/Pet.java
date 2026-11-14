package fixtures.clinic;

/** PetClinic-style entity. */
public class Pet {
    private Integer id;
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isNew() {
        return this.id == null;
    }
}
