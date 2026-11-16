package fixtures.legacy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Spring 2.5 / Struts 1 era shapes: setter injection through a base class, raw collections, hand-written checks. */
public class XmlWired {

    public interface Validator {
        void validate(Object target, Errors errors);
    }

    public interface Errors {
        void reject(String code);
    }

    public interface Facade {
        Object findOrder(int id);
    }

    public interface Invocation {
        Object proceed() throws Throwable;
    }

    /** A framework base class: collaborators wired by XML through setters. */
    public abstract static class BaseController {
        private Validator validator;

        public final Validator getValidator() {
            return validator;
        }

        public final void setValidator(Validator validator) {
            this.validator = validator;
        }
    }

    /** A Struts-style base action: its collaborator looked up when the servlet is set, no setter. */
    public abstract static class BaseAction {
        private Facade facade;

        public void setServlet(Object servlet) {
            this.facade = null; // looked up from the servlet context in the real thing
        }

        protected Facade getFacade() {
            return facade;
        }
    }

    public static class SubmitController extends BaseController {
        public String submit(Object command, Errors errors) {
            getValidator().validate(command, errors);
            return "done";
        }
    }

    public static class ViewOrderAction extends BaseAction {
        public Object execute(int id) {
            return getFacade().findOrder(id);
        }
    }

    public static class Basket {
        public static class Line {
            public void increment() {
            }
        }

        private final Map lines = new HashMap();

        public void add(String id) {
            Line line = (Line) lines.get(id);
            if (line == null) {
                line = new Line();
                lines.put(id, line);
            }
            line.increment();
        }
    }

    public static class Customer {
        public static class Pet {
            private String name;

            public String getName() {
                return name;
            }
        }

        private Set pets;

        public Set getPetsInternal() { // protected in PetClinic; the test harness loads tests in another class loader
            if (this.pets == null) {
                this.pets = new HashSet();
            }
            return this.pets;
        }

        public Pet getPet(String name) {
            name = name.toLowerCase();
            for (Iterator it = getPetsInternal().iterator(); it.hasNext();) {
                Pet pet = (Pet) it.next();
                if (pet.getName().toLowerCase().equals(name)) {
                    return pet;
                }
            }
            return null;
        }
    }

    public void checkPhone(String phone, Errors errors) {
        if (phone == null || phone.length() == 0) {
            errors.reject("required");
            return;
        }
        for (int i = 0; i < phone.length(); i++) {
            if (!Character.isDigit(phone.charAt(i))) {
                errors.reject("nonNumeric");
                break;
            }
        }
    }

    public String page(String page) {
        String where = "start";
        if ("next".equals(page)) {
            where = "forward";
        }
        if ("nextCart".equals(page)) {
            where = where + "+cart";
        }
        return where;
    }

    public Object around(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }
}
