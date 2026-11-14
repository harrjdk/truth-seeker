package dev.truthseeker.analysis;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Framework-aware facts about a class: stereotypes, injection points, complexity. */
public final class ClassInspector {
    private ClassInspector() {}

    // annotation simple-name → stereotype label; matched for both javax.* and jakarta.*
    private static final Map<String, String> TYPE_STEREOTYPES = new LinkedHashMap<>();

    static {
        TYPE_STEREOTYPES.put("org/springframework/boot/autoconfigure/SpringBootApplication", "Spring Boot application");
        TYPE_STEREOTYPES.put("org/springframework/web/bind/annotation/RestController", "Spring @RestController");
        TYPE_STEREOTYPES.put("org/springframework/stereotype/Controller", "Spring @Controller");
        TYPE_STEREOTYPES.put("org/springframework/web/bind/annotation/ControllerAdvice", "Spring @ControllerAdvice");
        TYPE_STEREOTYPES.put("org/springframework/web/bind/annotation/RestControllerAdvice", "Spring @RestControllerAdvice");
        TYPE_STEREOTYPES.put("org/springframework/stereotype/Service", "Spring @Service");
        TYPE_STEREOTYPES.put("org/springframework/stereotype/Repository", "Spring @Repository");
        TYPE_STEREOTYPES.put("org/springframework/stereotype/Component", "Spring @Component");
        TYPE_STEREOTYPES.put("org/springframework/context/annotation/Configuration", "Spring @Configuration");
        TYPE_STEREOTYPES.put("org/springframework/transaction/annotation/Transactional", "Spring @Transactional");
        for (String ns : List.of("javax", "jakarta")) {
            TYPE_STEREOTYPES.put(ns + "/ejb/Stateless", "EJB @Stateless");
            TYPE_STEREOTYPES.put(ns + "/ejb/Stateful", "EJB @Stateful");
            TYPE_STEREOTYPES.put(ns + "/ejb/Singleton", "EJB @Singleton");
            TYPE_STEREOTYPES.put(ns + "/ejb/MessageDriven", "EJB @MessageDriven");
            TYPE_STEREOTYPES.put(ns + "/ws/rs/Path", "JAX-RS resource");
            TYPE_STEREOTYPES.put(ns + "/ws/rs/ext/Provider", "JAX-RS @Provider");
            TYPE_STEREOTYPES.put(ns + "/jws/WebService", "JAX-WS @WebService");
            TYPE_STEREOTYPES.put(ns + "/servlet/annotation/WebServlet", "Servlet (@WebServlet)");
            TYPE_STEREOTYPES.put(ns + "/servlet/annotation/WebFilter", "Servlet filter (@WebFilter)");
            TYPE_STEREOTYPES.put(ns + "/servlet/annotation/WebListener", "Servlet listener (@WebListener)");
            TYPE_STEREOTYPES.put(ns + "/persistence/Entity", "JPA @Entity");
            TYPE_STEREOTYPES.put(ns + "/persistence/Embeddable", "JPA @Embeddable");
            TYPE_STEREOTYPES.put(ns + "/persistence/MappedSuperclass", "JPA @MappedSuperclass");
            TYPE_STEREOTYPES.put(ns + "/enterprise/context/ApplicationScoped", "CDI @ApplicationScoped");
            TYPE_STEREOTYPES.put(ns + "/enterprise/context/RequestScoped", "CDI @RequestScoped");
            TYPE_STEREOTYPES.put(ns + "/enterprise/context/SessionScoped", "CDI @SessionScoped");
            TYPE_STEREOTYPES.put(ns + "/inject/Named", "CDI @Named");
            TYPE_STEREOTYPES.put(ns + "/faces/bean/ManagedBean", "JSF @ManagedBean");
        }
    }

    /** Legacy (pre-annotation) framework base types → stereotype. */
    private static final Map<String, String> SUPERTYPE_STEREOTYPES = new LinkedHashMap<>();

    static {
        for (String ns : List.of("javax", "jakarta")) {
            SUPERTYPE_STEREOTYPES.put(ns + "/servlet/http/HttpServlet", "Servlet (extends HttpServlet)");
            SUPERTYPE_STEREOTYPES.put(ns + "/servlet/Filter", "Servlet filter");
            SUPERTYPE_STEREOTYPES.put(ns + "/jms/MessageListener", "JMS MessageListener");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/SessionBean", "EJB 2.x SessionBean");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/EntityBean", "EJB 2.x EntityBean (CMP/BMP)");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/MessageDrivenBean", "EJB 2.x MessageDrivenBean");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/EJBObject", "EJB 2.x remote interface");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/EJBLocalObject", "EJB 2.x local interface");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/EJBHome", "EJB 2.x home interface");
            SUPERTYPE_STEREOTYPES.put(ns + "/ejb/EJBLocalHome", "EJB 2.x local home interface");
            SUPERTYPE_STEREOTYPES.put(ns + "/faces/component/UIComponent", "JSF component");
        }
        SUPERTYPE_STEREOTYPES.put("org/apache/struts/action/Action", "Struts 1 Action");
        SUPERTYPE_STEREOTYPES.put("org/apache/struts/actions/DispatchAction", "Struts 1 DispatchAction");
        SUPERTYPE_STEREOTYPES.put("org/apache/struts/action/ActionForm", "Struts 1 ActionForm");
        SUPERTYPE_STEREOTYPES.put("com/opensymphony/xwork2/ActionSupport", "Struts 2 Action");
        SUPERTYPE_STEREOTYPES.put("org/springframework/web/servlet/mvc/Controller", "Spring MVC (legacy Controller interface)");
        SUPERTYPE_STEREOTYPES.put("org/springframework/web/servlet/mvc/AbstractController", "Spring MVC AbstractController");
        SUPERTYPE_STEREOTYPES.put("org/springframework/web/servlet/mvc/SimpleFormController", "Spring MVC SimpleFormController (removed in Spring 4)");
        SUPERTYPE_STEREOTYPES.put("org/springframework/web/servlet/mvc/multiaction/MultiActionController", "Spring MVC MultiActionController (removed in Spring 4)");
        SUPERTYPE_STEREOTYPES.put("org/springframework/orm/hibernate3/support/HibernateDaoSupport", "Hibernate 3 DAO (Spring HibernateDaoSupport)");
        SUPERTYPE_STEREOTYPES.put("org/springframework/jdbc/core/support/JdbcDaoSupport", "Spring JdbcDaoSupport DAO");
        SUPERTYPE_STEREOTYPES.put("org/springframework/data/repository/Repository", "Spring Data repository");
        SUPERTYPE_STEREOTYPES.put("org/springframework/data/repository/CrudRepository", "Spring Data repository");
        SUPERTYPE_STEREOTYPES.put("org/springframework/data/jpa/repository/JpaRepository", "Spring Data JPA repository");
        SUPERTYPE_STEREOTYPES.put("org/springframework/data/repository/PagingAndSortingRepository", "Spring Data repository");
        SUPERTYPE_STEREOTYPES.put("org/springframework/batch/item/ItemProcessor", "Spring Batch ItemProcessor");
        SUPERTYPE_STEREOTYPES.put("org/springframework/batch/core/step/tasklet/Tasklet", "Spring Batch Tasklet");
        SUPERTYPE_STEREOTYPES.put("org/quartz/Job", "Quartz Job");
    }

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "org/springframework/beans/factory/annotation/Autowired",
            "org/springframework/beans/factory/annotation/Value",
            "javax/inject/Inject", "jakarta/inject/Inject",
            "javax/annotation/Resource", "jakarta/annotation/Resource",
            "javax/ejb/EJB", "jakarta/ejb/EJB",
            "javax/persistence/PersistenceContext", "jakarta/persistence/PersistenceContext",
            "javax/persistence/PersistenceUnit", "jakarta/persistence/PersistenceUnit");

    public record Injection(String fieldName, String typeDesc, String how) {}

    // ---------------------------------------------------------------------------------------------

    public static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> out = new ArrayList<>();
        if (visible != null) out.addAll(visible);
        if (invisible != null) out.addAll(invisible);
        return out;
    }

    public static List<AnnotationNode> annotations(ClassNode cn) {
        return annotations(cn.visibleAnnotations, cn.invisibleAnnotations);
    }

    public static List<AnnotationNode> annotations(MethodNode mn) {
        return annotations(mn.visibleAnnotations, mn.invisibleAnnotations);
    }

    public static List<AnnotationNode> annotations(FieldNode fn) {
        return annotations(fn.visibleAnnotations, fn.invisibleAnnotations);
    }

    public static String annotationType(AnnotationNode a) {
        return Type.getType(a.desc).getInternalName();
    }

    public static Optional<AnnotationNode> find(List<AnnotationNode> anns, String... internalNames) {
        Set<String> wanted = Set.of(internalNames);
        return anns.stream().filter(a -> wanted.contains(annotationType(a))).findFirst();
    }

    public static boolean has(List<AnnotationNode> anns, String simpleName) {
        return anns.stream().anyMatch(a -> Names.simple(annotationType(a)).equals(simpleName));
    }

    /** Annotation attribute as a list of strings (handles single values, arrays and enum values). */
    public static List<String> attr(AnnotationNode a, String name) {
        List<String> out = new ArrayList<>();
        if (a == null || a.values == null) return out;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (!name.equals(a.values.get(i))) continue;
            flatten(a.values.get(i + 1), out);
        }
        return out;
    }

    private static void flatten(Object v, List<String> out) {
        if (v instanceof List<?> l) for (Object o : l) flatten(o, out);
        else if (v instanceof String[] e && e.length == 2) out.add(e[1]); // enum: [desc, value]
        else if (v instanceof Type t) out.add(Names.typeName(t));
        else if (v instanceof AnnotationNode an) out.add(renderAnnotation(an));
        else if (v != null) out.add(String.valueOf(v));
    }

    public static String renderAnnotation(AnnotationNode a) {
        StringBuilder sb = new StringBuilder("@").append(Names.simple(annotationType(a)));
        if (a.values != null && !a.values.isEmpty()) {
            sb.append('(');
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (i > 0) sb.append(", ");
                List<String> vals = new ArrayList<>();
                flatten(a.values.get(i + 1), vals);
                sb.append(a.values.get(i)).append('=').append(vals.size() == 1 ? quoteIfNeeded(vals.get(0)) : vals.toString());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String quoteIfNeeded(String s) {
        return s.matches("[A-Za-z0-9_.$]+") ? s : '"' + s + '"';
    }

    // ---------------------------------------------------------------------------------------------

    /** All supertypes (classes and interfaces) known to the repository, plus the unresolved frontier names. */
    public static Set<String> supertypes(ClassRepository repo, ClassNode cn) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        if (cn.superName != null) todo.add(cn.superName);
        todo.addAll(cn.interfaces);
        while (!todo.isEmpty()) {
            String s = todo.poll();
            if (!out.add(s)) continue;
            ClassNode sn = repo.node(s);
            if (sn != null) {
                if (sn.superName != null) todo.add(sn.superName);
                todo.addAll(sn.interfaces);
            }
        }
        return out;
    }

    public static List<String> stereotypes(ClassRepository repo, ClassNode cn) {
        List<String> out = new ArrayList<>();
        for (AnnotationNode a : annotations(cn)) {
            String s = TYPE_STEREOTYPES.get(annotationType(a));
            if (s != null) out.add(s);
        }
        for (String sup : supertypes(repo, cn)) {
            String s = SUPERTYPE_STEREOTYPES.get(sup);
            if (s != null && !out.contains(s)) out.add(s);
        }
        if ((cn.access & Opcodes.ACC_ENUM) != 0) out.add("enum");
        if ((cn.access & Opcodes.ACC_RECORD) != 0 || "java/lang/Record".equals(cn.superName)) out.add("record");
        // method-level hints
        for (MethodNode mn : cn.methods) {
            for (AnnotationNode a : annotations(mn)) {
                String t = Names.simple(annotationType(a));
                String label = switch (t) {
                    case "Scheduled" -> "has @Scheduled jobs";
                    case "Schedule" -> "has EJB @Schedule timers";
                    case "JmsListener" -> "has @JmsListener";
                    case "KafkaListener" -> "has @KafkaListener";
                    case "RabbitListener" -> "has @RabbitListener";
                    case "EventListener" -> "has @EventListener";
                    case "Bean" -> "declares @Bean factories";
                    default -> null;
                };
                if (label != null && !out.contains(label)) out.add(label);
            }
        }
        return out;
    }

    /** Fields injected by annotation, constructor or setter (the latter typical of Spring XML wiring). */
    public static List<Injection> injections(ClassNode cn) {
        Map<String, Injection> out = new LinkedHashMap<>();
        Map<String, FieldNode> fields = new HashMap<>();
        for (FieldNode fn : cn.fields) {
            fields.put(fn.name, fn);
            if ((fn.access & Opcodes.ACC_STATIC) != 0) continue;
            for (AnnotationNode a : annotations(fn)) {
                if (INJECTION_ANNOTATIONS.contains(annotationType(a))) {
                    out.put(fn.name, new Injection(fn.name, fn.desc, "@" + Names.simple(annotationType(a))));
                    break;
                }
            }
        }
        for (MethodNode mn : cn.methods) {
            boolean ctor = mn.name.equals("<init>");
            boolean setter = mn.name.startsWith("set") && Type.getArgumentTypes(mn.desc).length == 1
                    && Type.getReturnType(mn.desc).getSort() == Type.VOID;
            if (!ctor && !setter) continue;
            boolean annotated = annotations(mn).stream().anyMatch(a -> INJECTION_ANNOTATIONS.contains(annotationType(a)));
            int paramSlotsEnd = ((mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1);
            for (Type t : Type.getArgumentTypes(mn.desc)) paramSlotsEnd += t.getSize();
            // pattern: ALOAD 0, xLOAD n (n is a parameter), PUTFIELD this.f
            AbstractInsnNode[] arr = mn.instructions.toArray();
            for (int i = 2; i < arr.length; i++) {
                if (arr[i] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(cn.name)
                        && arr[i - 1] instanceof VarInsnNode v && v.var > 0 && v.var < paramSlotsEnd
                        && arr[i - 2] instanceof VarInsnNode self && self.var == 0) {
                    FieldNode fn = fields.get(f.name);
                    if (fn == null || out.containsKey(f.name)) continue;
                    if (Type.getType(f.desc).getSort() != Type.OBJECT) continue;
                    String how = ctor ? "constructor" : (annotated ? "@Autowired setter" : "setter (XML wiring?)");
                    if (setter && !annotated && isValueType(f.desc)) continue;
                    out.put(f.name, new Injection(f.name, f.desc, how));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Types that are data rather than collaborators. */
    public static boolean isValueType(String desc) {
        Type t = Type.getType(desc);
        if (t.getSort() != Type.OBJECT) return true;
        String n = t.getInternalName();
        return n.startsWith("java/lang/") || n.startsWith("java/math/")
                || (n.startsWith("java/time/") && !n.equals("java/time/Clock"))
                || (n.startsWith("java/util/") && !n.startsWith("java/util/concurrent/") && !n.startsWith("java/util/logging/"));
    }

    // ---------------------------------------------------------------------------------------------

    /** McCabe cyclomatic complexity: decision points + 1 (catch handlers count as decisions). */
    public static int complexity(MethodNode mn) {
        int c = 1;
        for (AbstractInsnNode in : mn.instructions) {
            int op = in.getOpcode();
            if (in instanceof JumpInsnNode && op != Opcodes.GOTO && op != Opcodes.JSR) c++;
            else if (in instanceof TableSwitchInsnNode ts) c += new HashSet<>(ts.labels).size();
            else if (in instanceof LookupSwitchInsnNode ls) c += new HashSet<>(ls.labels).size();
        }
        if (mn.tryCatchBlocks != null) {
            c += (int) mn.tryCatchBlocks.stream().filter(t -> t.type != null).map(t -> t.handler).distinct().count();
        }
        return c;
    }

    public static boolean isSynthetic(MethodNode mn) {
        return (mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || mn.name.startsWith("lambda$")
                || mn.name.startsWith("access$") || mn.name.startsWith("$");
    }

    public static String access(int acc) {
        List<String> m = new ArrayList<>();
        if ((acc & Opcodes.ACC_PUBLIC) != 0) m.add("public");
        if ((acc & Opcodes.ACC_PROTECTED) != 0) m.add("protected");
        if ((acc & Opcodes.ACC_PRIVATE) != 0) m.add("private");
        if ((acc & Opcodes.ACC_STATIC) != 0) m.add("static");
        if ((acc & Opcodes.ACC_FINAL) != 0) m.add("final");
        if ((acc & Opcodes.ACC_ABSTRACT) != 0) m.add("abstract");
        if ((acc & Opcodes.ACC_SYNCHRONIZED) != 0) m.add("synchronized");
        return String.join(" ", m);
    }

    public static String kind(ClassNode cn) {
        if ((cn.access & Opcodes.ACC_ANNOTATION) != 0) return "@interface";
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) return "interface";
        if ((cn.access & Opcodes.ACC_ENUM) != 0) return "enum";
        if ("java/lang/Record".equals(cn.superName)) return "record";
        return ((cn.access & Opcodes.ACC_ABSTRACT) != 0 ? "abstract " : "") + "class";
    }

    public static List<String> enumConstants(ClassNode cn) {
        List<String> out = new ArrayList<>();
        if (cn == null) return out;
        for (FieldNode fn : cn.fields) if ((fn.access & Opcodes.ACC_ENUM) != 0) out.add(fn.name);
        return out;
    }
}
