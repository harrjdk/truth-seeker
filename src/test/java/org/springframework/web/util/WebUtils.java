package org.springframework.web.util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** Test stand-in for Spring's WebUtils: the session lookups, with the same behaviour. */
public abstract class WebUtils {

    public static Object getSessionAttribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        return session != null ? session.getAttribute(name) : null;
    }

    public static Object getRequiredSessionAttribute(HttpServletRequest request, String name) {
        Object attr = getSessionAttribute(request, name);
        if (attr == null) throw new IllegalStateException("No session attribute '" + name + "' found");
        return attr;
    }

    public static Object getOrCreateSessionAttribute(HttpSession session, String name, Class<?> clazz) {
        if (session == null) throw new IllegalArgumentException("Session must not be null");
        Object attr = session.getAttribute(name);
        if (attr == null) {
            try {
                attr = clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException(ex);
            }
            session.setAttribute(name, attr);
        }
        return attr;
    }
}
