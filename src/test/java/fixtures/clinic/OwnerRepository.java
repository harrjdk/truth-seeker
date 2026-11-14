package fixtures.clinic;

import java.util.Optional;

public interface OwnerRepository {
    Optional<Owner> findById(Integer id);

    Owner save(Owner owner);
}
