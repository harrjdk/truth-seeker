package dev.truthseeker;

import dev.truthseeker.analysis.*;
import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import dev.truthseeker.symbolic.PathExplorer;
import dev.truthseeker.testgen.SuiteReport;
import dev.truthseeker.testgen.TestGenerator;
import dev.truthseeker.verify.TestVerifier;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

/** The tool catalogue: names, JSON schemas, descriptions and handlers. Transport-agnostic. */
public final class Tools {
    private Tools() {}

    /** A handler returns either a String (Markdown) or an Output (Markdown plus a JSON object for MCP structuredContent). */
    public record ToolDef(String name, String title, String description, String schema, Function<Map<String, Object>, Object> handler) {}

    /** Tool result: Markdown for the model, plus machine-readable data sent as MCP structuredContent. */
    public record Output(String text, Map<String, Object> structured) {}

    /** The Markdown text of a handler result. */
    public static String text(Object result) {
        return result instanceof Output o ? o.text() : String.valueOf(result);
    }

    private static final String PATH_PROP = """
            "path": {"type": "string", "description": "Absolute path to a .jar, .war, .ear, Spring Boot fat jar, or a directory of .class files (e.g. target/classes). Join several with the OS path separator."}""";
    private static final String CLASS_PROP = """
            "className": {"type": "string", "description": "Class name: fully qualified (com.acme.OrderService), internal (com/acme/OrderService), nested (Outer$Inner) or a unique simple name."}""";
    private static final String METHOD_PROP = """
            "method": {"type": "string", "description": "Method name. Disambiguate overloads with a parameter hint like 'place(String,int)' or the 'descriptor' argument."},
            "descriptor": {"type": "string", "description": "Optional JVM descriptor, e.g. (Ljava/lang/String;I)Z, or simple parameter list like (String,int)."}""";
    private static final String PKG_PROP = """
            "packagePrefix": {"type": "string", "description": "Only consider classes whose fully qualified name starts with this prefix, e.g. com.acme.billing"}""";

    public static List<ToolDef> all() {
        List<ToolDef> t = new ArrayList<>();

        t.add(new ToolDef("scan_archive", "Scan archive",
                "Inventory a Java application archive (JAR/WAR/EAR/Spring Boot jar/classes dir) WITHOUT loading or running it. "
                        + "Reports bytecode levels, bundled libraries, deployment descriptors, Spring/Jakarta EE/legacy J2EE stereotypes "
                        + "(controllers, services, EJBs incl. 2.x, Struts actions, servlets, JPA entities, MDBs, schedulers) and the most "
                        + "complex methods. Start here.",
                schema(PATH_PROP + "," + PKG_PROP + """
                        ,"includeLibraries": {"type": "boolean", "description": "Include classes from WEB-INF/lib, BOOT-INF/lib, EAR lib/ (default false)."},
                        "limit": {"type": "integer", "description": "Max component rows (default 300)."}""", "path"),
                a -> Reports.scan(repo(a), str(a, "packagePrefix"), bool(a, "includeLibraries", false), integer(a, "limit", 300))));

        t.add(new ToolDef("list_endpoints", "List entry points",
                "List the application's entry points: Spring MVC and JAX-RS HTTP routes, servlets/filters (annotations and web.xml), "
                        + "Struts 1/2 actions (struts-config.xml), JMS/Kafka/Rabbit listeners, message-driven beans, @Scheduled jobs, EJB timers and SOAP services.",
                schema(PATH_PROP + "," + PKG_PROP, "path"),
                a -> Reports.endpoints(repo(a), str(a, "packagePrefix"))));

        t.add(new ToolDef("describe_class", "Describe class",
                "Describe one class: kind, bytecode level, framework stereotypes, annotations, injected collaborators "
                        + "(field/constructor/setter injection, incl. XML-wired setters), fields, and every method with cyclomatic complexity.",
                schema(PATH_PROP + "," + CLASS_PROP, "path", "className"),
                a -> {
                    ClassRepository repo = repo(a);
                    return Reports.describe(repo, repo.require(str(a, "className")));
                }));

        t.add(new ToolDef("disassemble", "Disassemble bytecode",
                "Show the raw JVM bytecode (javap-like, via ASM Textifier) for a class or a single method. "
                        + "Use when you need ground truth beyond the summarized analyses.",
                schema(PATH_PROP + "," + CLASS_PROP + """
                        ,"method": {"type": "string", "description": "Optional method name; omit for the whole class."},
                        "descriptor": {"type": "string", "description": "Optional descriptor to pick one overload."},
                        "maxChars": {"type": "integer", "description": "Output cap (default 60000)."}""", "path", "className"),
                a -> {
                    ClassRepository repo = repo(a);
                    ClassNode cn = repo.require(str(a, "className"));
                    String m = str(a, "method");
                    List<MethodNode> ms = m == null ? null : ClassRepository.findMethods(cn, m, str(a, "descriptor"));
                    return Reports.disassemble(repo, cn, ms, integer(a, "maxChars", 60_000));
                }));

        t.add(new ToolDef("call_graph", "Call graph",
                "Static call graph from (direction=out) or to (direction=in) a class or method, as a Mermaid flowchart plus an edge list. "
                        + "Interface calls are expanded to implementations found in the archive (typical Spring service wiring); lambdas and "
                        + "method references are attributed to their creating method. JDK/framework calls are hidden unless includeJdk=true.",
                schema(PATH_PROP + "," + CLASS_PROP + """
                        ,"method": {"type": "string", "description": "Optional method name; omit to start from every method of the class."},
                        "direction": {"type": "string", "enum": ["out", "in"], "description": "out = callees (default), in = callers."},
                        "depth": {"type": "integer", "description": "Levels to follow (default 2, max 6)."},
                        "includeJdk": {"type": "boolean", "description": "Also show JDK / javax / jakarta / bundled library calls (default false)."}""", "path", "className"),
                a -> {
                    ClassRepository repo = repo(a);
                    return Reports.callGraph(repo, repo.require(str(a, "className")), str(a, "method"),
                            Math.min(6, integer(a, "depth", 2)), Optional.ofNullable(str(a, "direction")).orElse("out"), bool(a, "includeJdk", false));
                }));

        t.add(new ToolDef("truth_table", "Truth table",
                "Symbolically execute a method's bytecode and produce its truth table: every decision as a readable condition "
                        + "(e.g. `amount > 1000`, `this.repo.findById(id).isPresent()`, `this.status == Status.PAID`), one row per feasible path "
                        + "with its outcome (return value / exception) and side effects, an optional fully-expanded 2^n table, and the "
                        + "recovered boolean logic per outcome. Ideal for documenting legacy business rules and spotting dead or missing branches.",
                schema(PATH_PROP + "," + CLASS_PROP + "," + METHOD_PROP + """
                        ,"expand": {"type": "boolean", "description": "Also emit the fully expanded table over all condition combinations (≤10 conditions)."},
                        "maxPaths": {"type": "integer", "description": "Path exploration cap (default 256)."},
                        "loopBound": {"type": "integer", "description": "Max loop iterations explored per path (default 1)."},
                        "detail": {"type": "string", "enum": ["summary", "full"], "description": "summary (default): one row per outcome with path counts, side effects listed once, and the recovered logic. full: one decision-table row per path."},
                        "includeLogging": {"type": "boolean", "description": "Keep log.isDebugEnabled()-style guards as decisions (default false: assumed false and hidden)."},
                        "inlineSelfCalls": {"type": "boolean", "description": "Follow calls on the class's own methods (this.helper(..), private/static helpers, getters) up to 2 levels so their decisions appear in the table (default true). false: keep them as symbolic calls."},
                        "format": {"type": "string", "enum": ["markdown", "json"], "description": "Output format (default markdown)."}""", "path", "className", "method"),
                a -> {
                    ClassRepository repo = repo(a);
                    ClassNode cn = repo.require(str(a, "className"));
                    List<MethodNode> ms = ClassRepository.findMethods(cn, str(a, "method"), str(a, "descriptor"));
                    PathExplorer.Options o = new PathExplorer.Options(integer(a, "maxPaths", 256), integer(a, "loopBound", 1), 20_000, 140,
                            !bool(a, "includeLogging", false), bool(a, "inlineSelfCalls", true));
                    boolean json = "json".equalsIgnoreCase(str(a, "format"));
                    StringBuilder sb = new StringBuilder();
                    List<Object> structured = new ArrayList<>();
                    for (MethodNode mn : ms) {
                        PathExplorer.Result r = PathExplorer.explore(repo, cn, mn, o);
                        if (json) structured.add(TruthTables.structured(r));
                        else sb.append(TruthTables.markdown(r, bool(a, "expand", false), "full".equalsIgnoreCase(str(a, "detail")))).append("\n");
                    }
                    if (!json) return sb.toString();
                    Map<String, Object> obj = structured.size() == 1 ? asMap(structured.get(0)) : Map.of("methods", structured);
                    return new Output(toJson(obj), obj);
                }));

        t.add(new ToolDef("state_machine", "Infer state machine",
                "Infer state machines from enum-typed state (or a named String/int status field) and render them as Mermaid "
                        + "stateDiagram-v2 and/or PlantUML, with a transition table. Works for rich domain objects (this.status = SHIPPED), "
                        + "anemic entities driven by services (if (order.getStatus() == PAID) order.setStatus(SHIPPED)), switch statements "
                        + "(javac and Eclipse-compiled), and enum `next()` methods. Flags unguarded transitions, terminal and never-entered states, "
                        + "and operations rejected by state guards. Omit className to analyze the whole archive (use packagePrefix to focus).",
                schema(PATH_PROP + """
                        ,"className": {"type": "string", "description": "Optional: analyze only this class. Omit to scan all application classes."},
                        "enumType": {"type": "string", "description": "Optional: only this state enum (simple or fully qualified name)."},
                        "stateField": {"type": "string", "description": "Optional: property name of a String/int status field (e.g. 'status') to treat its constants as states."},
                        "format": {"type": "string", "enum": ["mermaid", "plantuml", "both", "json"], "description": "Diagram format (default mermaid)."},
                        "maxPathsPerMethod": {"type": "integer", "description": "Path cap per method (default 64)."},""" + PKG_PROP, "path"),
                a -> {
                    ClassRepository repo = repo(a);
                    List<ClassNode> classes = new ArrayList<>();
                    String cls = str(a, "className");
                    if (cls != null) classes.add(repo.require(cls));
                    else {
                        String prefix = str(a, "packagePrefix");
                        for (ClassRepository.ClassEntry e : repo.entries()) {
                            if (!e.library() && (prefix == null || Names.dotted(e.internalName()).startsWith(prefix))) classes.add(repo.node(e.internalName()));
                        }
                    }
                    var machines = StateMachines.analyze(repo, classes,
                            new StateMachines.Options(str(a, "enumType"), str(a, "stateField"), str(a, "packagePrefix"), integer(a, "maxPathsPerMethod", 64)));
                    if ("json".equalsIgnoreCase(str(a, "format"))) {
                        Map<String, Object> obj = Map.of("machines", StateMachines.structured(machines));
                        return new Output(toJson(obj), obj);
                    }
                    return "# Inferred state machines (" + classes.size() + " classes analyzed)\n\n"
                            + StateMachines.render(machines, Optional.ofNullable(str(a, "format")).orElse("mermaid"));
                }));

        t.add(new ToolDef("generate_unit_tests", "Generate unit tests",
                "Generate JUnit 5 + Mockito test classes (one per class): one test per symbolic execution path, "
                        + "with parameter values solved from branch conditions, collaborator stubs (incl. Optional/Spring Data and BigDecimal "
                        + "compareTo idioms), assertions on return values / exceptions / state-field writes, and verify() of collaborator side "
                        + "effects. Tests whose preconditions cannot be arranged automatically are emitted @Disabled with precise TODOs. "
                        + "With outputDir, files are written and only a compact summary is returned (disabled tests grouped by cause); "
                        + "then call verify_tests on the same outputDir. Tests target Java 17+, JUnit Jupiter and Mockito 5.",
                schema(PATH_PROP + "," + CLASS_PROP + """
                        ,"classNames": {"type": "array", "items": {"type": "string"}, "description": "Several classes in one call (instead of className)."},
                        "methods": {"type": "array", "items": {"type": "string"}, "description": "Optional method names to cover; default all non-private methods."},
                        "maxPathsPerMethod": {"type": "integer", "description": "Path exploration cap per method (default 64)."},
                        "maxTestsPerMethod": {"type": "integer", "description": "Max generated tests per method (default 24)."},
                        "includeTruncated": {"type": "boolean", "description": "Also emit tests for paths whose analysis was cut short (default false)."},
                        "outputDir": {"type": "string", "description": "Test source root to write files into (package folders are created). Recommended: keeps the response small."},
                        "overwrite": {"type": "boolean", "description": "Replace existing generated files in outputDir (default false)."},
                        "includeSource": {"type": "boolean", "description": "Also return the generated source (default: only when outputDir is not given)."}""",
                        "path"),
                a -> {
                    ClassRepository repo = repo(a);
                    List<String> names = new ArrayList<>(list(a, "classNames"));
                    if (str(a, "className") != null) names.add(0, str(a, "className"));
                    if (names.isEmpty()) throw new IllegalArgumentException("'className' or 'classNames' is required");
                    String out = str(a, "outputDir");
                    boolean includeSource = bool(a, "includeSource", out == null);
                    TestGenerator.Options opts = new TestGenerator.Options(list(a, "methods"),
                            integer(a, "maxPathsPerMethod", 64), integer(a, "maxTestsPerMethod", 24), bool(a, "includeTruncated", false));
                    List<TestGenerator.Suite> suites = new ArrayList<>();
                    List<String> written = new ArrayList<>();
                    List<String> files = new ArrayList<>();
                    for (String name : names) {
                        ClassNode cn = repo.require(name);
                        TestGenerator.Suite suite = TestGenerator.generateSuite(repo, cn, opts);
                        suites.add(suite);
                        if (out == null) continue;
                        java.nio.file.Path dir = java.nio.file.Path.of(out).resolve(Names.packageOf(cn.name).replace('.', '/'));
                        java.nio.file.Path file = dir.resolve(Names.simple(cn.name) + "GeneratedTest.java");
                        try {
                            Files.createDirectories(dir);
                            if (Files.exists(file) && !bool(a, "overwrite", false)) {
                                written.add("Not written (exists; pass overwrite=true): `" + file + "`");
                            } else {
                                Files.writeString(file, suite.code());
                                files.add(file.toString());
                            }
                        } catch (java.io.IOException ex) {
                            written.add("Could not write " + file + ": " + ex.getMessage());
                        }
                    }
                    if (!files.isEmpty()) {
                        written.add(0, "Wrote " + files.size() + " file(s) under `" + out + "`: "
                                + files.stream().map(f -> java.nio.file.Path.of(f).getFileName().toString()).collect(java.util.stream.Collectors.joining(", ")));
                    }
                    StringBuilder text = new StringBuilder(SuiteReport.markdown(suites, written));
                    if (includeSource) {
                        for (TestGenerator.Suite s : suites) text.append("\n```java\n").append(s.code()).append("```\n");
                        text.append("\nRequired test dependencies: org.junit.jupiter:junit-jupiter, org.mockito:mockito-junit-jupiter (Mockito 5+).\n");
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("classes", suites.stream().map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("testClass", s.className());
                        m.put("tests", s.tests());
                        m.put("disabled", s.disabled().size());
                        m.put("paths", s.paths());
                        return m;
                    }).toList());
                    structured.put("files", files);
                    return new Output(text.toString(), structured);
                }));
        t.add(new ToolDef("verify_tests", "Verify tests",
                "Compile test sources (e.g. the outputDir of generate_unit_tests) against an application archive and run them on "
                        + "JUnit 5 in a separate JVM; report passed / failed / disabled with failures grouped by cause and disabled tests "
                        + "grouped by unresolved precondition. The classpath is derived from the archive (Spring Boot BOOT-INF, WAR "
                        + "WEB-INF, EAR modules); JUnit and Mockito are bundled. Files that do not compile are reported and the rest still "
                        + "run. Use extraClasspath for `provided` APIs (jakarta.*/javax.*) missing from the archive. "
                        + "NOTE: this executes the application's code in a child JVM.",
                schema(PATH_PROP + """
                        ,"testDir": {"type": "string", "description": "Directory containing the test .java sources (searched recursively)."},
                        "extraClasspath": {"type": "string", "description": "Additional classpath entries joined with the OS path separator, e.g. provided container APIs."},
                        "timeoutSeconds": {"type": "integer", "description": "Kill the test JVM after this many seconds (default 300)."}""",
                        "path", "testDir"),
                a -> {
                    TestVerifier.Options o = new TestVerifier.Options(str(a, "path"), str(a, "testDir"), str(a, "extraClasspath"),
                            integer(a, "timeoutSeconds", 300));
                    try {
                        TestVerifier.Result r = TestVerifier.verify(o);
                        return new Output(TestVerifier.markdown(o, r), TestVerifier.structured(r));
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException("verify_tests failed: " + e.getMessage(), e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }));
        return t;
    }

    public static String instructions() {
        return """
                truth-seeker analyzes compiled Java applications (Java 1.x through 25 bytecode; best for Spring, Jakarta/Java EE and legacy J2EE)
                purely from bytecode — nothing from the target app is loaded or executed, and source code is not required.
                Suggested workflow: scan_archive → list_endpoints → describe_class on interesting components → truth_table / state_machine
                on business logic → generate_unit_tests (with outputDir) to pin behaviour, then verify_tests on that outputDir to
                compile and run them and see what still needs arranging. Use call_graph to follow a request
                through layers and disassemble when you need ground truth. Every tool takes `path` (jar/war/ear/classes dir); indexes are
                cached per path, so repeated calls are fast. Analyses are static approximations: only typed catch blocks are explored, loops are
                bounded, calls on the class's own methods are inlined 2 levels deep and other classes' code is not followed — state these limits when presenting results.
                """;
    }

    // ---- argument helpers ------------------------------------------------------------------------

    private static String schema(String props, String... required) {
        return "{\"type\":\"object\",\"properties\":{" + props + "},\"required\":["
                + String.join(",", Arrays.stream(required).map(r -> "\"" + r + "\"").toList()) + "]}";
    }

    static ClassRepository repo(Map<String, Object> a) {
        try {
            return ClassRepository.open(str(a, "path"));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read " + str(a, "path") + ": " + e.getMessage(), e);
        }
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static boolean bool(Map<String, Object> a, String k, boolean def) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(String.valueOf(v));
        return def;
    }

    static int integer(Map<String, Object> a, String k, int def) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof Number n) return Math.max(1, n.intValue());
        if (v != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(v).trim()));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    static List<String> list(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return Arrays.stream(String.valueOf(v).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    static String toJson(Object o) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(o);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
