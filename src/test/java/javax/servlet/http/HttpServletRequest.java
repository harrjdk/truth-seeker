package javax.servlet.http;

/** Test stand-in for the Servlet API type of the same name. */
public interface HttpServletRequest {
    HttpSession getSession();

    HttpSession getSession(boolean create);

    String getParameter(String name);
}
