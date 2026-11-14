package fixtures.door;

/** Rich domain object with its state machine inside (Java 17 switch expressions). */
public class Door {

    public enum State { OPEN, CLOSED, LOCKED }

    private State state;
    private int openCount;

    public Door() {
        this.state = State.CLOSED;
    }

    public void open() {
        if (state == State.LOCKED) {
            throw new IllegalStateException("locked");
        }
        if (state == State.CLOSED) {
            state = State.OPEN;
            openCount++;
        }
    }

    public void close() {
        if (state == State.OPEN) {
            state = State.CLOSED;
        }
    }

    public boolean lock(String key) {
        return switch (state) {
            case CLOSED -> {
                if (key == null || key.isEmpty()) {
                    yield false;
                }
                state = State.LOCKED;
                yield true;
            }
            case OPEN, LOCKED -> false;
        };
    }

    public void unlock() {
        if (state == State.LOCKED) {
            state = State.CLOSED;
        }
    }

    public State getState() {
        return state;
    }
}
