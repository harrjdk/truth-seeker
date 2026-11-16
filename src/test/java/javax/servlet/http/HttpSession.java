package javax.servlet.http;

/** Test stand-in for the Servlet API type of the same name. */
public interface HttpSession {
    Object getAttribute(String name);

    void setAttribute(String name, Object value);
}
