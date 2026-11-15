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

        boolean isActive();

        String getHome();

        int getVisits();
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

    public boolean hasMember(String name) {
        return members.stream().anyMatch(m -> m.getName().equals(name));
    }

    public long countActive() {
        return members.stream().filter(Member::isActive).count();
    }

    public BigDecimal totalWithTax(BigDecimal rate) {
        return members.stream().map(m -> m.getFee().multiply(rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Cargo Tracker Itinerary.isExpected-shaped: two comparisons joined with && inside the lambda. */
    public boolean covers(String name, String home) {
        return members.stream().anyMatch(m -> m.getName().equals(name) && m.getHome().equals(home));
    }

    /** JPetStore Cart.getSubTotal-shaped: a constructor inside the mapping lambda. */
    public BigDecimal visitFees() {
        return members.stream().map(m -> m.getFee().multiply(new BigDecimal(m.getVisits()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String openedStamp() {
        return opened.format(STAMP);
    }
}
