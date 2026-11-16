package fixtures.legacy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** PetClinic-style lookups: a static helper of another class, entities found by id and type, a refilled cache. */
public class Lookups {

    public static class Entity {
        private Integer id;

        public Integer getId() {
            return id;
        }
    }

    public static class Kind extends Entity {
    }

    public static class Animal {
        private int kindId;
        private Kind kind;

        public int getKindId() {
            return kindId;
        }

        public void setKind(Kind kind) {
            this.kind = kind;
        }
    }

    public static class Keeper {
        private int id;

        public int getId() {
            return id;
        }

        public void addKind(Kind kind) {
        }
    }

    /** The application's own utility class, as PetClinic's EntityUtils. */
    public static final class Entities {
        private Entities() {
        }

        public static <T extends Entity> T byId(Collection<T> entities, Class<T> type, int id) {
            for (T entity : entities) {
                if (entity.getId().intValue() == id && type.isInstance(entity)) {
                    return entity;
                }
            }
            throw new IllegalArgumentException("no " + type.getSimpleName() + " " + id);
        }
    }

    public interface Template {
        List query(String sql, Object... args);
    }

    private Template template;
    private final List<Keeper> keepers = new ArrayList<>();

    public void setTemplate(Template template) {
        this.template = template;
    }

    public Collection<Kind> getKinds() {
        return template.query("SELECT id FROM kinds");
    }

    /** The helper's loop runs over the answer of a query with no varargs. */
    public Animal load(Animal animal) {
        animal.setKind(Entities.byId(getKinds(), Kind.class, animal.getKindId()));
        return animal;
    }

    /** A cache cleared and refilled, then walked; an inner loop over ids queried per keeper. */
    public void refresh() {
        keepers.clear();
        keepers.addAll(template.query("SELECT id FROM keepers"));
        List<Kind> kinds = template.query("SELECT id FROM kinds");
        for (Keeper keeper : keepers) {
            List<Integer> ids = template.query("SELECT kind_id FROM keeper_kinds WHERE keeper_id=?", keeper.getId());
            for (int id : ids) {
                keeper.addKind(Entities.byId(kinds, Kind.class, id));
            }
        }
    }
}
