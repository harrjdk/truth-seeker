package fixtures.clinic;

/** Mirrors PetClinic's PetController: constructor injection, Optional.orElseThrow, overloaded collaborator calls. */
public class PetLookupService {

    private final OwnerRepository owners;

    public PetLookupService(OwnerRepository owners) {
        this.owners = owners;
    }

    public Owner findOwner(Integer ownerId) {
        return this.owners.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));
    }

    public Pet findPet(Integer ownerId, Integer petId) {
        if (petId == null) {
            return new Pet();
        }
        Owner owner = this.owners.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));
        return owner.getPet(petId);
    }
}
