package fixtures.clinic;

import java.util.List;

/** Mirrors PetClinic's PetValidator: the subject arrives as Object and is cast. */
public class PetValidator {

    public void validate(Object obj, List<String> errors) {
        Pet pet = (Pet) obj;
        String name = pet.getName();
        if (name == null || name.isBlank()) {
            errors.add("name: required");
        }
        if (pet.isNew()) {
            errors.add("type: required");
        }
    }
}
