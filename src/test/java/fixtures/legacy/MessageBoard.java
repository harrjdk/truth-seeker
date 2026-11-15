package fixtures.legacy;

import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/** Pre-collections and shared-state code: Enumerations, a static counter, java.util.Date and id-based equals. */
public class MessageBoard {

    public interface Browser {
        Enumeration<String> getMessages();
    }

    public interface Permissions {
        Enumeration<String> elements();
    }

    public interface Consumer {
        void receive();
    }

    /** Shared between calls, as in many legacy singletons and batch steps. */
    static int retries = 0;

    private Long id;
    private Date expires;

    public int count(Browser browser) {
        int n = 0;
        Enumeration<String> e = browser.getMessages();
        while (e.hasMoreElements()) {
            String m = e.nextElement();
            if (m.isEmpty()) {
                continue;
            }
            n++;
        }
        return n;
    }

    /** Polls without taking an element: the loop ends only when the source says so. */
    public void drain(Browser browser, Consumer consumer) {
        while (browser.getMessages().hasMoreElements()) {
            consumer.receive();
        }
    }

    public boolean anyGranted(Permissions permissions) {
        List<String> all = Collections.list(permissions.elements());
        for (String p : all) {
            if (p.startsWith("grant")) {
                return true;
            }
        }
        return false;
    }

    public String retry() {
        if (retries >= 3) {
            return "give up";
        }
        retries++;
        return "again";
    }

    public String status(Date now) {
        if (expires.before(now)) {
            return "expired";
        }
        return "valid";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MessageBoard other = (MessageBoard) o;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
