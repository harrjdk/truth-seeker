package fixtures.legacy;

/** PetClinic processFindForm-shaped: a normalised search term, a paged result interface, and pagination guards. */
public class CustomerSearch {

    public interface Customer {
        Integer getId();
    }

    public interface ResultPage<T> extends Iterable<T> {
        boolean isEmpty();

        int getTotalPages();

        long getTotalElements();
    }

    public interface CustomerRepository {
        ResultPage<Customer> findByName(String name, int page);
    }

    private final CustomerRepository customers;

    public CustomerSearch(CustomerRepository customers) {
        this.customers = customers;
    }

    public String find(int page, String rawName) {
        String name = rawName;
        if (name == null) {
            name = "";
        } else {
            name = name.strip();
        }
        ResultPage<Customer> results = customers.findByName(name, page);
        if (page < 1 || page > Math.max(results.getTotalPages(), 1)) {
            if (!name.isEmpty()) {
                return "redirect:/customers?name=" + name;
            }
            return "redirect:/customers";
        }
        if (results.isEmpty()) {
            return "customers/none";
        }
        if (results.getTotalElements() == 1) {
            Customer only = results.iterator().next();
            return "redirect:/customers/" + only.getId();
        }
        return "customers/list";
    }
}
