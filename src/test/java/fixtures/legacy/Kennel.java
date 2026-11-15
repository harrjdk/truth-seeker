package fixtures.legacy;

import java.util.ArrayList;
import java.util.List;

/** An aggregate that keeps its own list: adds what is new, hands work to the member it finds. */
public class Kennel {

    public static class KennelException extends RuntimeException {
        public KennelException(String message) {
            super(message);
        }
    }

    public interface Dog {
        String getName();

        void feed(int grams);
    }

    private final List<Dog> dogs = new ArrayList<>();

    public void admit(Dog dog) {
        if (dog == null) {
            throw new KennelException("No dog to admit");
        }
        if (dogs.contains(dog)) {
            return;
        }
        dogs.add(dog);
    }

    public void feed(String name, int grams) {
        if (grams <= 0) {
            throw new IllegalArgumentException("Portion must be positive");
        }
        for (Dog dog : dogs) {
            if (name.equals(dog.getName())) {
                dog.feed(grams);
                return;
            }
        }
        throw new KennelException("No dog named " + name);
    }

    public int size() {
        return dogs.size();
    }
}
