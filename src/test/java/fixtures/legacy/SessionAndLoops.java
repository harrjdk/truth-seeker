package fixtures.legacy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.util.WebUtils;

/** Spring 2.5 era shapes: command-line and advice arrays, hand-written iterator loops, session lookups, sizes. */
public class SessionAndLoops {

    public interface Directory {
        List findIds(String group);

        List findOwners(String name);

        Map getServices();
    }

    public interface Service {
        void ping();
    }

    public static class UserSession {
        private String name;

        public String getName() {
            return name;
        }
    }

    public static class Cart {
        private int items;

        public int getItems() {
            return items;
        }
    }

    public static class Purchase {
        private String username;

        public String getUsername() {
            return username;
        }
    }

    private Directory directory;

    public void setDirectory(Directory directory) {
        this.directory = directory;
    }

    /** main(String[] args) style: the array as long as the path needs, its elements chosen. */
    public static String command(String[] args) {
        if (args.length == 0 || "".equals(args[0])) {
            return "usage";
        }
        if (args.length > 1 && "-v".equals(args[1])) {
            return "verbose " + args[0];
        }
        return "run " + args[0];
    }

    /** AOP advice: the element cast to what the code takes it to be. */
    public String afterReturning(Object returnValue, Object[] args) {
        Purchase purchase = (Purchase) args[0];
        return purchase.getUsername();
    }

    /** An inner loop over a list queried with the outer element. */
    public int countIds(List<String> groups) {
        int n = 0;
        for (Iterator it = groups.iterator(); it.hasNext();) {
            String group = (String) it.next();
            for (Iterator ids = directory.findIds(group).iterator(); ids.hasNext();) {
                ids.next();
                n++;
            }
        }
        return n;
    }

    /** The size of a collaborator's answer. */
    public String find(String name) {
        List found = directory.findOwners(name);
        if (found.size() < 1) {
            return "none";
        }
        if (found.size() > 1) {
            return "many";
        }
        return "one";
    }

    /** Struts-style: errors added to the list it is given, then counted. */
    public boolean validate(String code, List errors) {
        if (code == null || code.length() == 0) {
            errors.add("code is required");
        }
        if (errors.size() > 0) {
            return false;
        }
        return true;
    }

    /** A loop over a map's keys, looking each one up. */
    public int pingAll() {
        Map services = directory.getServices();
        int n = 0;
        for (Iterator it = services.keySet().iterator(); it.hasNext();) {
            String name = (String) it.next();
            Service service = (Service) services.get(name);
            service.ping();
            n++;
        }
        return n;
    }

    /** A StringTokenizer over a parameter. */
    public List<String> keywords(String text) {
        List<String> out = new ArrayList<>();
        StringTokenizer splitter = new StringTokenizer(text, " ");
        while (splitter.hasMoreTokens()) {
            out.add("%" + splitter.nextToken() + "%");
        }
        return out;
    }

    /** Spring's WebUtils session lookups. */
    public String greet(HttpServletRequest request) {
        UserSession userSession = (UserSession) WebUtils.getSessionAttribute(request, "userSession");
        if (userSession == null) {
            return "anonymous";
        }
        return userSession.getName();
    }

    public int cartSize(HttpServletRequest request) {
        Cart cart = (Cart) WebUtils.getOrCreateSessionAttribute(request.getSession(), "cart", Cart.class);
        return cart.getItems();
    }
}
