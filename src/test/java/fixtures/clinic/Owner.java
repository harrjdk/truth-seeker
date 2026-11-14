package fixtures.clinic;

import java.util.ArrayList;
import java.util.List;

/** Mirrors PetClinic's Owner: overloaded getPet(..) lookups implemented with for-each loops. */
public class Owner {
    private final List<Pet> pets = new ArrayList<>();

    public List<Pet> getPets() {
        return this.pets;
    }

    public void addPet(Pet pet) {
        this.pets.add(pet);
    }

    public Pet getPet(String name) {
        for (Pet pet : getPets()) {
            String compName = pet.getName();
            if (compName != null && compName.equalsIgnoreCase(name)) {
                return pet;
            }
        }
        return null;
    }

    public Pet getPet(Integer id) {
        for (Pet pet : getPets()) {
            if (!pet.isNew() && pet.getId().equals(id)) {
                return pet;
            }
        }
        return null;
    }
}
