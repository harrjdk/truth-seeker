package fixtures.legacy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** HandlingHistory-shaped: a list derived from a field through a stream pipeline, then checked for emptiness. */
public class History {

    public static class Entry {
        private final LocalDateTime time;

        public Entry(LocalDateTime time) {
            this.time = time;
        }

        public LocalDateTime getTime() {
            return time;
        }
    }

    private static final Comparator<Entry> BY_TIME = Comparator.comparing(Entry::getTime);

    private final List<Entry> entries;

    public History(Collection<Entry> entries) {
        this.entries = List.copyOf(new ArrayList<>(entries));
    }

    public List<Entry> ordered() {
        List<Entry> sorted = entries.stream().distinct().sorted(BY_TIME).collect(Collectors.toList());
        return List.copyOf(sorted);
    }

    public Entry mostRecent() {
        List<Entry> ordered = ordered();
        if (ordered.isEmpty()) {
            return null;
        }
        return ordered.get(ordered.size() - 1);
    }
}
