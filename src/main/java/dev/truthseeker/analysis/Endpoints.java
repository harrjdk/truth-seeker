package dev.truthseeker.analysis;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry points into an application: HTTP, messaging, scheduling — from annotations and XML descriptors. */
public final class Endpoints {
    private Endpoints() {}

    public record Endpoint(String kind, String verb, String path, String handler, String source) {}

    private static final Map<String, String> SPRING_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH", "RequestMapping", "*");
    private static final Set<String> JAXRS_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    public static List<Endpoint> find(ClassRepository repo, String packagePrefix) {
        List<Endpoint> out = new ArrayList<>();
        String appPath = jaxrsApplicationPath(repo);
        for (ClassRepository.ClassEntry e : repo.entries()) {
            if (e.library() || !matches(e.internalName(), packagePrefix)) continue;
            ClassNode cn = repo.node(e.internalName());
            scanClass(repo, cn, out, appPath);
        }
        scanDescriptors(repo, out);
        out.sort(Comparator.comparing(Endpoint::kind).thenComparing(Endpoint::path).thenComparing(Endpoint::verb));
        return out;
    }

    static boolean matches(String internalName, String prefix) {
        if (prefix == null || prefix.isBlank()) return true;
        return internalName.replace('/', '.').startsWith(prefix.trim());
    }

    /** Base path from a JAX-RS {@code @ApplicationPath} on an {@code Application} subclass (empty if none). */
    private static String jaxrsApplicationPath(ClassRepository repo) {
        for (ClassRepository.ClassEntry e : repo.entries()) {
            if (e.library()) continue;
            ClassNode cn = repo.node(e.internalName());
            Optional<AnnotationNode> ap = ClassInspector.find(ClassInspector.annotations(cn),
                    "javax/ws/rs/ApplicationPath", "jakarta/ws/rs/ApplicationPath");
            if (ap.isPresent()) {
                List<String> v = ClassInspector.attr(ap.get(), "value");
                if (!v.isEmpty()) return v.get(0);
            }
        }
        return "";
    }

    private static void scanClass(ClassRepository repo, ClassNode cn, List<Endpoint> out, String appPath) {
        List<AnnotationNode> classAnns = ClassInspector.annotations(cn);
        String cls = Names.dotted(cn.name);

        // Spring MVC
        List<String> springBase = ClassInspector.find(classAnns, "org/springframework/web/bind/annotation/RequestMapping")
                .map(a -> paths(a)).orElse(List.of(""));
        // JAX-RS
        List<String> jaxrsBase = ClassInspector.find(classAnns, "javax/ws/rs/Path", "jakarta/ws/rs/Path")
                .map(a -> ClassInspector.attr(a, "value")).orElse(null);

        for (MethodNode mn : cn.methods) {
            List<AnnotationNode> anns = ClassInspector.annotations(mn);
            String handler = Names.simple(cn.name) + "." + mn.name + "()";
            for (AnnotationNode a : anns) {
                String type = ClassInspector.annotationType(a);
                String simple = Names.simple(type);
                if (type.startsWith("org/springframework/web/bind/annotation/") && SPRING_VERBS.containsKey(simple)) {
                    String verb = SPRING_VERBS.get(simple);
                    if (verb.equals("*")) {
                        List<String> ms = ClassInspector.attr(a, "method");
                        verb = ms.isEmpty() ? "ANY" : String.join("|", ms);
                    }
                    List<String> ps = paths(a);
                    if (ps.isEmpty()) ps = List.of("");
                    for (String base : springBase) for (String p : ps) {
                        out.add(new Endpoint("HTTP (Spring MVC)", verb, join(base, p), handler, cls));
                    }
                }
                if ((type.startsWith("javax/ws/rs/") || type.startsWith("jakarta/ws/rs/")) && JAXRS_VERBS.contains(simple)) {
                    List<String> sub = ClassInspector.find(anns, "javax/ws/rs/Path", "jakarta/ws/rs/Path")
                            .map(x -> ClassInspector.attr(x, "value")).orElse(List.of(""));
                    String base = jaxrsBase == null || jaxrsBase.isEmpty() ? "" : jaxrsBase.get(0);
                    for (String p : sub) out.add(new Endpoint("HTTP (JAX-RS)", simple, join(join(appPath, base), p), handler, cls));
                }
                switch (simple) {
                    case "JmsListener" -> out.add(new Endpoint("JMS listener", "consume",
                            String.join(",", ClassInspector.attr(a, "destination")), handler, cls));
                    case "KafkaListener" -> out.add(new Endpoint("Kafka listener", "consume",
                            String.join(",", concat(ClassInspector.attr(a, "topics"), ClassInspector.attr(a, "topicPattern"))), handler, cls));
                    case "RabbitListener" -> out.add(new Endpoint("RabbitMQ listener", "consume",
                            String.join(",", ClassInspector.attr(a, "queues")), handler, cls));
                    case "Scheduled" -> out.add(new Endpoint("Scheduled job", "schedule", scheduleText(a), handler, cls));
                    case "Schedule" -> out.add(new Endpoint("EJB timer", "schedule", ClassInspector.renderAnnotation(a), handler, cls));
                    case "EventListener" -> out.add(new Endpoint("Spring event listener", "event",
                            String.join(",", concat(ClassInspector.attr(a, "value"), ClassInspector.attr(a, "classes"))), handler, cls));
                    case "WebMethod" -> out.add(new Endpoint("SOAP (JAX-WS)", "operation", mn.name, handler, cls));
                    default -> {}
                }
            }
        }
        // class-level registrations
        for (AnnotationNode a : classAnns) {
            String simple = Names.simple(ClassInspector.annotationType(a));
            if (simple.equals("WebServlet") || simple.equals("WebFilter")) {
                List<String> ps = concat(ClassInspector.attr(a, "value"), ClassInspector.attr(a, "urlPatterns"));
                for (String p : ps) out.add(new Endpoint(simple.equals("WebServlet") ? "HTTP (Servlet)" : "Servlet filter", "*", p, Names.simple(cn.name), cls));
            }
            if (simple.equals("MessageDriven")) {
                out.add(new Endpoint("JMS (EJB MDB)", "consume", mdbDestination(a), Names.simple(cn.name) + ".onMessage()", cls));
            }
            if (simple.equals("WebService")) {
                out.add(new Endpoint("SOAP (JAX-WS)", "service", String.join(",", ClassInspector.attr(a, "serviceName")), Names.simple(cn.name), cls));
            }
        }
        // legacy javax.jms.MessageListener without annotations
        if (ClassInspector.supertypes(repo, cn).stream().anyMatch(s -> s.endsWith("/jms/MessageListener"))
                && classAnns.stream().noneMatch(a -> Names.simple(ClassInspector.annotationType(a)).equals("MessageDriven"))
                && (cn.access & Opcodes.ACC_ABSTRACT) == 0) {
            out.add(new Endpoint("JMS listener (XML-wired?)", "consume", "?", Names.simple(cn.name) + ".onMessage()", cls));
        }
    }

    /** Destination of a message-driven bean from activationConfig (destinationLookup / destination) or legacy mappedName. */
    static String mdbDestination(AnnotationNode a) {
        Map<String, String> props = new LinkedHashMap<>();
        if (a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (!"activationConfig".equals(a.values.get(i)) || !(a.values.get(i + 1) instanceof List<?> list)) continue;
                for (Object o : list) {
                    if (o instanceof AnnotationNode p) {
                        List<String> name = ClassInspector.attr(p, "propertyName");
                        List<String> value = ClassInspector.attr(p, "propertyValue");
                        if (!name.isEmpty() && !value.isEmpty()) props.put(name.get(0), value.get(0));
                    }
                }
            }
        }
        for (String k : List.of("destinationLookup", "destination", "destinationName")) {
            if (props.containsKey(k)) {
                String type = props.getOrDefault("destinationType", "");
                return props.get(k) + (type.isEmpty() ? "" : " (" + Names.simple(type.replace('.', '/')) + ")");
            }
        }
        List<String> mapped = ClassInspector.attr(a, "mappedName");
        if (!mapped.isEmpty()) return mapped.get(0);
        return props.isEmpty() ? "?" : props.toString();
    }

    private static String scheduleText(AnnotationNode a) {
        List<String> parts = new ArrayList<>();
        for (String k : List.of("cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString", "initialDelay")) {
            List<String> v = ClassInspector.attr(a, k);
            if (!v.isEmpty()) parts.add(k + "=" + v.get(0));
        }
        return String.join(" ", parts);
    }

    private static List<String> paths(AnnotationNode a) {
        return concat(ClassInspector.attr(a, "value"), ClassInspector.attr(a, "path"));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String join(String base, String p) {
        String s = ("/" + base + "/" + p).replaceAll("/+", "/");
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- XML descriptors (web.xml, struts-config.xml) --------------------------------------------

    private static final Pattern SERVLET = Pattern.compile(
            "<servlet>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>.*?<servlet-class>\\s*(.*?)\\s*</servlet-class>.*?</servlet>", Pattern.DOTALL);
    private static final Pattern SERVLET_MAPPING = Pattern.compile(
            "<servlet-mapping>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>(.*?)</servlet-mapping>", Pattern.DOTALL);
    private static final Pattern URL_PATTERN = Pattern.compile("<url-pattern>\\s*(.*?)\\s*</url-pattern>", Pattern.DOTALL);
    private static final Pattern FILTER = Pattern.compile(
            "<filter>.*?<filter-name>\\s*(.*?)\\s*</filter-name>.*?<filter-class>\\s*(.*?)\\s*</filter-class>.*?</filter>", Pattern.DOTALL);
    private static final Pattern FILTER_MAPPING = Pattern.compile(
            "<filter-mapping>.*?<filter-name>\\s*(.*?)\\s*</filter-name>(.*?)</filter-mapping>", Pattern.DOTALL);
    private static final Pattern STRUTS_ACTION = Pattern.compile("<action\\s([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)\\s*=\\s*\"([^\"]*)\"");

    private static void scanDescriptors(ClassRepository repo, List<Endpoint> out) {
        for (var d : repo.descriptors().entrySet()) {
            String file = d.getKey();
            String xml = d.getValue().replaceAll("(?s)<!--.*?-->", "");
            if (file.endsWith("web.xml")) {
                Map<String, String> servlets = new HashMap<>();
                Matcher m = SERVLET.matcher(xml);
                while (m.find()) servlets.put(m.group(1), m.group(2));
                m = SERVLET_MAPPING.matcher(xml);
                while (m.find()) {
                    Matcher u = URL_PATTERN.matcher(m.group(2));
                    while (u.find()) {
                        String cls = servlets.getOrDefault(m.group(1), "?");
                        out.add(new Endpoint("HTTP (web.xml servlet)", "*", u.group(1), m.group(1) + " → " + simpleOf(cls), file));
                    }
                }
                Map<String, String> filters = new HashMap<>();
                m = FILTER.matcher(xml);
                while (m.find()) filters.put(m.group(1), m.group(2));
                m = FILTER_MAPPING.matcher(xml);
                while (m.find()) {
                    Matcher u = URL_PATTERN.matcher(m.group(2));
                    while (u.find()) {
                        out.add(new Endpoint("Servlet filter (web.xml)", "*", u.group(1),
                                m.group(1) + " → " + simpleOf(filters.getOrDefault(m.group(1), "?")), file));
                    }
                }
            } else if (file.contains("struts") && xml.contains("<action")) {
                Matcher m = STRUTS_ACTION.matcher(xml);
                while (m.find()) {
                    Map<String, String> attrs = new HashMap<>();
                    Matcher a = ATTR.matcher(m.group(1));
                    while (a.find()) attrs.put(a.group(1), a.group(2));
                    String path = attrs.getOrDefault("path", attrs.get("name"));
                    String type = attrs.getOrDefault("type", attrs.getOrDefault("class", "?"));
                    if (path != null) {
                        String verb = attrs.containsKey("method") ? "method=" + attrs.get("method") : "*";
                        out.add(new Endpoint("HTTP (Struts action)", verb, path, simpleOf(type), file));
                    }
                }
            }
        }
    }

    private static String simpleOf(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }
}
