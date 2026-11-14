package org.springframework.data.repository;

import java.util.Optional;

/** Minimal stand-in for Spring Data's CrudRepository. */
public interface CrudRepository<T, ID> {
    Optional<T> findById(ID id);

    <S extends T> S save(S entity);
}
