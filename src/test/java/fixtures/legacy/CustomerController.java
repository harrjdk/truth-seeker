package fixtures.legacy;

import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Spring MVC 2.5-style controller: results built locally (ModelAndView, lists) and returned. */
public class CustomerController {

    public interface Customer {
        String getName();
    }

    public interface CustomerDao {
        Customer find(String id);

        List<Customer> findByName(String name);

        Collection<Customer> findAll();
    }

    private final CustomerDao dao;

    public CustomerController(CustomerDao dao) {
        this.dao = dao;
    }

    public ModelAndView show(String id) {
        ModelAndView mav = new ModelAndView("customer/show");
        Customer customer = dao.find(id);
        if (customer == null) {
            mav.setViewName("customer/missing");
            mav.addObject("id", id);
            return mav;
        }
        mav.addObject("customer", customer);
        mav.addObject(customer.getName());
        return mav;
    }

    public List<Customer> search(String names) {
        List<Customer> found = new ArrayList<>();
        for (String name : names.split(",")) {
            found.addAll(dao.findByName(name));
        }
        return found;
    }

    public Directory directory() {
        Directory directory = new Directory();
        directory.getCustomers().addAll(dao.findAll());
        return directory;
    }
}
