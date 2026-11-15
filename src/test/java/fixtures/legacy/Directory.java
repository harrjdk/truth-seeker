package fixtures.legacy;

import java.util.ArrayList;
import java.util.List;

/** A wrapper for JSON/XML views whose list is created on first access (PetClinic's Vets). */
public class Directory {
    private List<CustomerController.Customer> customers;

    public List<CustomerController.Customer> getCustomers() {
        if (customers == null) {
            customers = new ArrayList<>();
        }
        return customers;
    }
}
