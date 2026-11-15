package fixtures.legacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Results computed from the object's own collections and constants (JPetStore Cart, PetClinic Vet). */
public class Roster {

    public interface Member {
        String getName();

        BigDecimal getFee();
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final List<Member> members = new java.util.ArrayList<>();
    private Set<Member> guests;
    private LocalDate opened;

    public Iterator<Member> iterator() {
        return members.iterator();
    }

    public BigDecimal totalFees() {
        return members.stream().map(Member::getFee).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Set<Member> getGuestsInternal() {
        if (guests == null) {
            guests = new HashSet<>();
        }
        return guests;
    }

    public List<Member> getGuests() {
        return getGuestsInternal().stream().sorted(Comparator.comparing(Member::getName)).collect(Collectors.toList());
    }

    public String openedStamp() {
        return opened.format(STAMP);
    }
}
