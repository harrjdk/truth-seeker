package org.springframework.web.servlet;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test stand-in for Spring MVC's ModelAndView: a view name and a model map. */
public class ModelAndView {
    private String viewName;
    private final Map<String, Object> model = new LinkedHashMap<>();

    public ModelAndView() {
    }

    public ModelAndView(String viewName) {
        this.viewName = viewName;
    }

    public ModelAndView(String viewName, String modelName, Object modelObject) {
        this.viewName = viewName;
        model.put(modelName, modelObject);
    }

    public String getViewName() {
        return viewName;
    }

    public void setViewName(String viewName) {
        this.viewName = viewName;
    }

    public Map<String, Object> getModel() {
        return model;
    }

    public ModelAndView addObject(String name, Object value) {
        model.put(name, value);
        return this;
    }

    public ModelAndView addObject(Object value) {
        String simple = value.getClass().getSimpleName();
        model.put(Character.toLowerCase(simple.charAt(0)) + simple.substring(1), value);
        return this;
    }
}
