package dev.truthseeker.testgen;

import dev.truthseeker.analysis.ClassInspector;
import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import dev.truthseeker.symbolic.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates a JUnit 5 + Mockito test class with one test per explored path. Parameter values are
 * solved from the path conditions, collaborator calls that conditions depend on are stubbed,
 * and outcomes / state writes / collaborator interactions are asserted. Anything that cannot be
 * derived mechanically is left as a {@code // TODO} with the exact condition to satisfy.
 */
public final class TestGenerator {

    public record Options(List<String> methods, int maxPathsPerMethod, int maxTestsPerMethod, boolean includeTruncated) {}

    private static final Pattern MUTATOR = Pattern.compile("(save|persist|merge|delete|remove|update|insert|send|publish|post|put|notify|emit|execute|enqueue|create|write|store|flush|register|schedule|cancel|charge|refund|ship|log).*", Pattern.CASE_INSENSITIVE);

    private final ClassRepository repo;
    private final ClassNode cn;
    private final Options opt;
    private final Imports imports = new Imports();
    private final Map<String, FieldNode> deps = new LinkedHashMap<>();
    private final List<String> classNotes = new ArrayList<>();
    private boolean needsSetField, needsGetField, needsEnumSafe;

    private static final String ENUM_SAFE_ANSWER = """
                /** Generated mocks answer enum getters with the first constant and String getters with "" instead of null. */
                private static final Answer<Object> ENUM_SAFE = invocation -> {
                    Class<?> type = invocation.getMethod().getReturnType();
                    if (type.isEnum() && type.getEnumConstants().length > 0) return type.getEnumConstants()[0];
                    if (type == String.class) return ""; // unstubbed String getters: empty rather than null
                    if (type == java.util.Iterator.class) return java.util.Collections.emptyIterator();
                    return RETURNS_DEFAULTS.answer(invocation);
                };

            """;

    private TestGenerator(ClassRepository repo, ClassNode cn, Options opt) {
        this.repo = repo;
        this.cn = cn;
        this.opt = opt;
    }

    /** A generated test class plus what a reviewer (or an LLM) needs to know about it. */
    public record Suite(String className, String code, int tests, int paths, List<Issue> disabled, List<Issue> notes, List<String> skipped) {}

    /** A test with unresolved preconditions (todos) or a note about a dependency that cannot be stubbed. */
    public record Issue(String test, List<String> details) {}

    public static String generate(ClassRepository repo, ClassNode cn, Options opt) {
        return generateSuite(repo, cn, opt).code();
    }

    public static Suite generateSuite(ClassRepository repo, ClassNode cn, Options opt) {
        TestGenerator g = new TestGenerator(repo, cn, opt);
        String code = g.run();
        return new Suite(Names.dotted(cn.name) + "GeneratedTest", code, g.testsMade, g.pathsSeen,
                List.copyOf(g.disabledTests), List.copyOf(g.noteTests), List.copyOf(g.skippedMethods));
    }

    private int testsMade, pathsSeen;
    private final List<Issue> disabledTests = new ArrayList<>();
    private final List<Issue> noteTests = new ArrayList<>();
    private final List<String> skippedMethods = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------

    private String run() {
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) {
            throw new IllegalArgumentException(Names.dotted(cn.name) + " is an interface; generate tests for an implementation instead.");
        }
        boolean isAbstract = (cn.access & Opcodes.ACC_ABSTRACT) != 0;
        collectDependencies();

        String subjectType = imports.ref(cn.name);
        List<String> testMethods = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        int pathsTotal = 0, testsTotal = 0, todosTotal = 0;
        List<String> skipped = new ArrayList<>();

        for (MethodNode mn : cn.methods) {
            if (!selected(mn)) continue;
            if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
                skipped.add(mn.name + " (private — covered through its callers)");
                continue;
            }
            boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
            if (isAbstract && !isStatic) {
                skipped.add(mn.name + " (abstract class — instance methods need a concrete subclass)");
                continue;
            }
            PathExplorer.Result r = PathExplorer.explore(repo, cn, mn, PathExplorer.Options.defaults().withMaxPaths(opt.maxPathsPerMethod()));
            pathsTotal += r.paths().size();
            int made = 0;
            for (Path p : r.paths()) {
                if (made >= opt.maxTestsPerMethod()) {
                    testMethods.add("    // … " + (r.paths().size() - made) + " more paths of " + mn.name + " not generated (maxTestsPerMethod)\n");
                    break;
                }
                if (p.outcome().kind() == Path.OutcomeKind.TRUNCATED && !opt.includeTruncated()) continue;
                Case c = new Case(r, p, isStatic);
                String base = mn.name + "_" + slug(p);
                int n = nameCounts.merge(base, 1, Integer::sum);
                String methodName = n == 1 ? base : base + "_" + n;
                String code = c.render(methodName, subjectType);
                todosTotal += c.todos.size();
                if (!c.todos.isEmpty()) disabledTests.add(new Issue(methodName, List.copyOf(c.todos)));
                String self = c.isStatic ? null : c.selfCall();
                if (self != null) noteTests.add(new Issue(methodName, List.of("depends on the real behaviour of `" + self + "`")));
                testMethods.add(code);
                made++;
                testsTotal++;
            }
        }

        testsMade = testsTotal;
        pathsSeen = pathsTotal;
        skippedMethods.addAll(skipped);

        // ---- assemble the class ----
        StringBuilder body = new StringBuilder();
        String pkg = Names.packageOf(cn.name);
        String testName = Names.simple(cn.name) + "GeneratedTest";
        imports.add("org.junit.jupiter.api.Test");
        imports.add("org.junit.jupiter.api.DisplayName");
        imports.add("org.junit.jupiter.api.extension.ExtendWith");
        imports.add("org.mockito.junit.jupiter.MockitoExtension");
        imports.add("org.mockito.junit.jupiter.MockitoSettings");
        imports.add("org.mockito.quality.Strictness");

        body.append("/**\n * Generated by truth-seeker from bytecode of {@code ").append(Names.dotted(cn.name)).append("}.\n")
                .append(" * One test per symbolic execution path: ").append(testsTotal).append(" tests from ").append(pathsTotal).append(" paths, ")
                .append(todosTotal).append(" TODOs to review.\n")
                .append(" * Values were solved from branch conditions; review TODOs and expected values before relying on these tests.\n");
        for (String n : classNotes) body.append(" * NOTE: ").append(n).append("\n");
        if (!skipped.isEmpty()) body.append(" * Skipped: ").append(String.join("; ", skipped)).append("\n");
        body.append(" */\n");
        body.append("@ExtendWith(MockitoExtension.class)\n@MockitoSettings(strictness = Strictness.LENIENT)\n");
        body.append("class ").append(testName).append(" {\n\n");
        if (!deps.isEmpty()) {
            imports.add("org.mockito.Mock");
            for (FieldNode f : deps.values()) {
                body.append("    @Mock\n    private ").append(imports.ref(Type.getType(f.desc))).append(" ").append(f.name).append(";\n\n");
            }
        }
        boolean isEnumSubject = (cn.access & Opcodes.ACC_ENUM) != 0;
        if (!isAbstract && !isEnumSubject) {
            // prefer the no-arg constructor for plain classes without collaborators; otherwise build explicitly
            String setUp = deps.isEmpty() && hasNoArgCtor() ? null : explicitConstruction(subjectType);
            if (setUp != null) {
                imports.add("org.junit.jupiter.api.BeforeEach");
                body.append("    private ").append(subjectType).append(" subject;\n\n");
                body.append(setUp);
            } else if (!deps.isEmpty()) {
                imports.add("org.mockito.InjectMocks");
                body.append("    @InjectMocks\n    private ").append(subjectType).append(" subject;\n\n");
            } else if (hasNoArgCtor()) {
                body.append("    private final ").append(subjectType).append(" subject = new ").append(subjectType).append("();\n\n");
            } else {
                body.append("    // TODO: construct the subject; no no-arg constructor and no injectable collaborators were found\n");
                body.append("    private ").append(subjectType).append(" subject;\n\n");
            }
        }
        for (String t : testMethods) body.append(t).append("\n");
        if (needsEnumSafe) {
            imports.add("org.mockito.stubbing.Answer");
            body.append(ENUM_SAFE_ANSWER);
        }
        if (needsSetField || needsGetField) body.append(REFLECTION_HELPERS);
        body.append("}\n");

        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n\n");
        for (String i : imports.sorted()) {
            // same-package types need no import
            if (!pkg.isEmpty() && i.startsWith(pkg + ".") && i.indexOf('.', pkg.length() + 1) < 0) continue;
            sb.append("import ").append(i).append(";\n");
        }
        sb.append("\nimport static org.junit.jupiter.api.Assertions.*;\nimport static org.mockito.ArgumentMatchers.*;\nimport static org.mockito.Mockito.*;\n\n");
        sb.append(body);
        return sb.toString();
    }

    private boolean selected(MethodNode mn) {
        if (mn.name.equals("<init>") || mn.name.equals("<clinit>") || ClassInspector.isSynthetic(mn)) return false;
        if ((mn.access & Opcodes.ACC_ABSTRACT) != 0) return false;
        if ((cn.access & Opcodes.ACC_ENUM) != 0 && (mn.name.equals("values") || mn.name.equals("valueOf"))) return false;
        if (opt.methods() == null || opt.methods().isEmpty()) return true;
        return opt.methods().stream().anyMatch(m -> m.trim().equals(mn.name));
    }

    /**
     * Builds the subject explicitly instead of relying on {@code @InjectMocks}, which silently skips
     * field injection whenever constructor injection succeeds — a common trap with legacy classes that
     * mix {@code @Autowired} fields and constructor arguments.
     */
    private String explicitConstruction(String subjectType) {
        MethodNode ctor = null;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0 || (m.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            if (ctor == null || Type.getArgumentTypes(m.desc).length > Type.getArgumentTypes(ctor.desc).length) ctor = m;
        }
        if (ctor == null) return null;
        if (hasNoArgCtor() && !ctor.desc.equals("()V") && readsThroughMockArgument(ctor)) {
            // new LineItem(1, mock(CartItem)) would hit cartItem.getItem().getItemId() on a null: start empty instead
            StringBuilder sb = new StringBuilder("    @BeforeEach\n    void setUp() {\n");
            sb.append("        subject = new ").append(subjectType).append("();\n");
            for (FieldNode f : deps.values()) {
                needsSetField = true;
                sb.append("        setField(subject, \"").append(f.name).append("\", ").append(f.name).append(");\n");
            }
            return sb.append("    }\n\n").toString();
        }
        Type[] ats = Type.getArgumentTypes(ctor.desc);
        List<String> paramNames = new ArrayList<>();
        int slot = 1;
        for (Type t : ats) {
            String name = null;
            if (ctor.localVariables != null) {
                for (var lv : ctor.localVariables) if (lv.index == slot) name = lv.name;
            }
            if (name == null && ctor.parameters != null && paramNames.size() < ctor.parameters.size()) name = ctor.parameters.get(paramNames.size()).name;
            paramNames.add(name);
            slot += t.getSize();
        }
        Set<String> viaCtor = new HashSet<>();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ats.length; i++) {
            String desc = ats[i].getDescriptor();
            String chosen = null;
            String pn = paramNames.get(i);
            if (pn != null && deps.containsKey(pn) && deps.get(pn).desc.equals(desc)) chosen = pn;
            if (chosen == null) {
                for (FieldNode f : deps.values()) {
                    if (f.desc.equals(desc) && !viaCtor.contains(f.name)) {
                        chosen = f.name;
                        break;
                    }
                }
            }
            if (chosen != null) {
                viaCtor.add(chosen);
                args.add(chosen);
            } else {
                args.add(ctorDefault(ats[i]));
            }
        }
        StringBuilder sb = new StringBuilder("    @BeforeEach\n    void setUp() {\n");
        // constructors often switch on an enum getter of an argument; a plain mock would return null there
        for (String dep : viaCtor) {
            needsEnumSafe = true;
            sb.append("        ").append(dep).append(" = mock(").append(imports.ref(Type.getType(deps.get(dep).desc)))
                    .append(".class, ENUM_SAFE);\n");
        }
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("mock(") && a.endsWith(".class)")) {
                needsEnumSafe = true;
                args.set(i, a.substring(0, a.length() - 1) + ", ENUM_SAFE)");
            }
        }
        sb.append("        subject = new ").append(subjectType).append("(").append(String.join(", ", args)).append(");\n");
        for (FieldNode f : deps.values()) {
            if (viaCtor.contains(f.name)) continue;
            needsSetField = true;
            sb.append("        setField(subject, \"").append(f.name).append("\", ").append(f.name).append(");\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    /**
     * Realistic, non-null constructor arguments: rich domain objects validate them
     * ({@code Objects.requireNonNull(completionTime, "Completion time is required")}).
     */
    private String ctorDefault(Type t) {
        String s = nonNullSample(t, null);
        return s != null ? s : "null /* TODO */";
    }

    /**
     * Source for a second, independent instance of the subject (the {@code other} in {@code equals(Object other)}),
     * built like the subject itself; null when that is not possible.
     */
    private String peerInstance() {
        if ((cn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM)) != 0) return null;
        String type = imports.ref(Type.getObjectType(cn.name));
        if (hasNoArgCtor()) return "new " + type + "()";
        MethodNode ctor = null;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0 || (m.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            if (ctor == null || Type.getArgumentTypes(m.desc).length > Type.getArgumentTypes(ctor.desc).length) ctor = m;
        }
        if (ctor == null) return null;
        List<String> args = new ArrayList<>();
        for (Type t : Type.getArgumentTypes(ctor.desc)) {
            String a = ctorDefault(t);
            if (a.startsWith("null")) return null;
            if (a.startsWith("mock(") && a.endsWith(".class)")) {
                needsEnumSafe = true;
                a = a.substring(0, a.length() - 1) + ", ENUM_SAFE)";
            }
            args.add(a);
        }
        return "new " + type + "(" + String.join(", ", args) + ")";
    }

    /**
     * Does the constructor call a method on what a getter of an object argument returns ({@code cartItem.getItem().getItemId()})?
     * A mock argument answers that getter with null, so the constructor would throw.
     */
    private boolean readsThroughMockArgument(MethodNode ctor) {
        Set<String> depTypes = deps.values().stream().map(f -> f.desc).collect(Collectors.toSet());
        PathExplorer.Result r = PathExplorer.explore(repo, cn, ctor, PathExplorer.Options.defaults().withInlining(false));
        boolean[] found = {false};
        java.util.function.Predicate<Expr> chained = x -> {
            if (x.kind == Expr.Kind.CALL && x.target != null && x.target.kind == Expr.Kind.CALL && x.target.target != null
                    && x.target.target.kind == Expr.Kind.PARAM && x.target.target.desc != null && x.target.target.desc.startsWith("L")
                    && !ClassInspector.isValueType(x.target.target.desc) && !depTypes.contains(x.target.target.desc)
                    && x.target.desc != null && x.target.desc.startsWith("L") && !ClassInspector.isValueType(x.target.desc)) {
                found[0] = true;
            }
            return found[0];
        };
        for (Path p : r.paths()) {
            if (p.outcome().kind() == Path.OutcomeKind.THROW) continue;
            for (Effect e : p.effects()) {
                if (e.target() != null) e.target().mentions(chained);
                if (e.value() != null) e.value().mentions(chained);
                e.args().forEach(a -> a.mentions(chained));
            }
            for (Cond c : p.conds()) c.left().mentions(chained);
            if (found[0]) return true;
        }
        return false;
    }

    private boolean hasNoArgCtor() {
        return cn.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("()V") && (m.access & Opcodes.ACC_PRIVATE) == 0);
    }

    private void collectDependencies() {
        Set<String> injected = ClassInspector.injections(cn).stream().map(ClassInspector.Injection::fieldName).collect(Collectors.toSet());
        // Entities, value objects and other plain classes: only what is handed in (constructor / injection) is a
        // collaborator. Their other object fields are internal state the constructor computes - don't overwrite it.
        boolean plainDomainClass = ClassInspector.stereotypes(repo, cn).stream()
                .allMatch(s -> s.startsWith("JPA") || s.equals("record") || s.equals("enum"));
        for (FieldNode f : cn.fields) {
            if ((f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0) continue;
            Type t = Type.getType(f.desc);
            if (t.getSort() != Type.OBJECT) continue;
            if (isEnum(t.getInternalName())) continue;
            if (plainDomainClass && !injected.contains(f.name)) continue;
            if (ClassInspector.isValueType(f.desc) && !injected.contains(f.name)) continue;
            if (ClassInspector.isValueType(f.desc)) continue; // e.g. @Value String: configure with setField instead
            deps.put(f.name, f);
        }
        if (cn.superName != null && repo.contains(cn.superName) && !cn.superName.equals("java/lang/Object")) {
            ClassNode sup = repo.node(cn.superName);
            for (FieldNode f : sup.fields) {
                if ((f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) continue;
                if (Type.getType(f.desc).getSort() != Type.OBJECT || ClassInspector.isValueType(f.desc)) continue;
                deps.putIfAbsent(f.name, f);
            }
        }
    }

    private boolean isEnum(String internalName) {
        ClassNode n = repo.node(internalName);
        return n != null && (n.access & Opcodes.ACC_ENUM) != 0;
    }

    private static String slug(Path p) {
        Path.Outcome o = p.outcome();
        return switch (o.kind()) {
            case RETURN_VOID -> "path" + p.id();
            case THROW -> "throws" + Names.simple(o.exceptionType());
            case TRUNCATED -> "path" + p.id() + "_partial";
            case RETURN -> {
                Expr v = o.value();
                String s;
                if (v.kind == Expr.Kind.CONST || v.kind == Expr.Kind.NULL) s = v.text.replace("\"", "");
                else if (v.kind == Expr.Kind.STATIC) s = v.name;
                else s = "path" + p.id();
                s = s.replaceAll("[^A-Za-z0-9]+", "_");
                if (s.length() > 30) s = s.substring(0, 30);
                yield "returns" + (s.isEmpty() ? "Empty" : Character.toUpperCase(s.charAt(0)) + s.substring(1));
            }
        };
    }

    // =============================================================================================

    /** Getter assertions written for one returned object the method built. */
    private static final int MAX_FIELD_ASSERTS = 8;

    /** Builds the test for one path. */
    private final class Case {
        final PathExplorer.Result r;
        final Path p;
        final boolean isStatic;
        final Map<Integer, String> paramValues = new HashMap<>();
        final Map<Integer, String> paramVars = new HashMap<>();
        final Map<Integer, Type> paramTypes = new HashMap<>();
        final List<String> arrange = new ArrayList<>();
        /** Statements right after the call under test (clearing the interrupt flag a test set). */
        final List<String> afterAct = new ArrayList<>();
        final LinkedHashMap<String, String> stubs = new LinkedHashMap<>();
        final List<String> todos = new ArrayList<>();
        final Map<String, String> ensured = new HashMap<>();
        final Map<String, String> boundLiteral = new HashMap<>();
        final Set<String> usedNames = new HashSet<>();
        int varCounter;

        Case(PathExplorer.Result r, Path p, boolean isStatic) {
            this.r = r;
            this.p = p;
            this.isStatic = isStatic;
            usedNames.addAll(deps.keySet());
            usedNames.addAll(List.of("subject", "result"));
            for (Expr param : r.params()) {
                String name = param.name;
                if (usedNames.contains(name) || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) name = name + "Arg";
                usedNames.add(name);
                paramVars.put(param.index, name);
            }
        }

        // ---- constraint model ----
        record Constraint(String op, Expr other, Object number) {}

        final class Target {
            final Expr expr;
            final List<Constraint> cs = new ArrayList<>();

            Target(Expr e) {
                expr = e;
            }
        }

        final LinkedHashMap<String, Target> targets = new LinkedHashMap<>();

        void constrain(Expr e, String op, Expr other) {
            constrain(e, op, other, null);
        }

        void constrain(Expr e, String op, Expr other, Object number) {
            targets.computeIfAbsent(canon(e), k -> new Target(e)).cs.add(new Constraint(op, other, number));
        }

        /** One key per value: a local's name and the expression it stands for constrain the same thing. */
        static String canon(Expr e) {
            return e.expansion() != null ? e.expansion() : e.text;
        }

        // ---- for-each loops over an arrangeable collection ----

        /** A for-each loop this test drives with a real collection: one element per iteration taken. */
        final class Loop {
            final Expr iterator;      // coll.iterator()
            final List<String> elements = new ArrayList<>();
            int current; // element the path is at: hasNext() = true decisions seen so far

            Loop(Expr iterator) {
                this.iterator = iterator;
            }

            boolean isHasNext(Expr e) {
                return e.kind == Expr.Kind.CALL && "hasNext".equals(e.name) && e.args.isEmpty() && e.target != null
                        && e.target.text.equals(iterator.text);
            }

            boolean isElement(Expr e) {
                return e.kind == Expr.Kind.CALL && "next".equals(e.name) && e.args.isEmpty() && e.target != null
                        && e.target.text.equals(iterator.text);
            }
        }

        final List<Loop> loops = new ArrayList<>();

        // ---- another instance of the subject's class as an argument (equals(Object other), compareTo, sameValueAs) ----

        /** Parameter index → true when the test passes a real second instance whose fields it sets. */
        final Map<Integer, Boolean> peers = new HashMap<>();

        boolean isPeerField(Expr e) {
            return e.kind == Expr.Kind.FIELD && e.target != null && e.target.kind == Expr.Kind.PARAM && peers.containsKey(e.target.index);
        }

        boolean isGetClass(Expr e) {
            return e.kind == Expr.Kind.CALL && "getClass".equals(e.name) && e.args.isEmpty() && e.target != null;
        }

        /**
         * A parameter whose fields of the subject's own class are read is another instance of it: pass a real one
         * (built like the subject) so field comparisons can be arranged with setField on both sides.
         */
        void arrangePeers() {
            if (isStatic) return;
            Set<Integer> candidates = new LinkedHashSet<>();
            for (Cond c : p.conds()) {
                for (Expr root : c.right() == null ? List.of(c.left()) : List.of(c.left(), c.right())) {
                    root.mentions(x -> {
                        if (x.kind == Expr.Kind.FIELD && x.target != null && x.target.kind == Expr.Kind.PARAM
                                && (x.owner.equals(cn.name) || ("L" + cn.name + ";").equals(x.target.desc))) candidates.add(x.target.index);
                        return false;
                    });
                }
            }
            // getClass() != o.getClass(): the argument must be of another class altogether; == : another instance of this one
            for (Cond c : p.conds()) {
                if (c.kind() == Cond.Kind.CMP && isGetClass(c.left()) && isGetClass(c.right())) {
                    boolean same = c.isTrue() == c.op().equals("==");
                    Expr other = c.left().target.kind == Expr.Kind.PARAM ? c.left().target : c.right().target;
                    if (other.kind != Expr.Kind.PARAM || "subject".equals(paramValues.get(other.index))) continue;
                    classArranged = true;
                    if (!same) {
                        candidates.remove(other.index);
                        paramValues.put(other.index, "new Object()");
                    } else {
                        candidates.add(other.index);
                    }
                }
            }
            if (candidates.isEmpty()) return;
            String peer = peerInstance();
            if (peer == null) return;
            for (int i : candidates) {
                if ("subject".equals(paramValues.get(i))) continue;
                paramValues.put(i, peer);
                peers.put(i, true);
            }
        }

        /** {@code this.f} vs {@code o.f} (and getClass() of both) on a peer: decided by values the test sets. */
        /** A getClass() comparison with an argument was arranged by choosing the argument's class. */
        boolean classArranged;

        boolean collectPeerComparison(Cond c) {
            if (classArranged && c.kind() == Cond.Kind.CMP && isGetClass(c.left()) && isGetClass(c.right())) return true;
            if (peers.isEmpty()) return false;
            if (c.kind() == Cond.Kind.CMP && isGetClass(c.left()) && isGetClass(c.right())) return true; // same class by construction
            if (c.kind() == Cond.Kind.CMP && (c.op().equals("==") || c.op().equals("!="))
                    && (isPeerField(c.left()) || isPeerField(c.right())) && c.right().kind != Expr.Kind.NULL) {
                boolean same = c.isTrue() == c.op().equals("==");
                return sameOrDifferent(c.left(), c.right(), same);
            }
            return false;
        }

        static boolean isIteratorOf(Expr e) {
            return e.kind == Expr.Kind.CALL && "iterator".equals(e.name) && e.args.isEmpty() && e.target != null
                    && e.desc != null && e.desc.equals("Ljava/util/Iterator;");
        }

        /**
         * Finds for-each loops over parameters, subject fields and stubbable collaborator results, and puts a
         * collection with exactly as many elements as the path iterates into them. Elements are mocks, so the
         * per-iteration decisions on them ({@code pet.isNew() [iteration 2]}) become stubs on the k-th element.
         */
        void arrangeLoops() {
            Map<String, int[]> trues = new LinkedHashMap<>();   // collection text → [hasNext true count]
            Map<String, Expr> iterators = new LinkedHashMap<>(); // collection text → coll.iterator()
            Map<String, Expr> colls = new LinkedHashMap<>();
            Map<String, List<Expr>> contained = new LinkedHashMap<>();
            for (Cond c : p.conds()) {
                Expr l = c.left();
                if (c.kind() != Cond.Kind.BOOL || l.kind != Expr.Kind.CALL || l.target == null) continue;
                if ("hasNext".equals(l.name) && l.args.isEmpty() && !isIteratorOf(l.target) && l.target.kind == Expr.Kind.CALL
                        && "Ljava/util/Iterator;".equals(l.target.desc) && !l.text.contains(" [iteration ")) {
                    // Iterator<CartItem> it = cart.getAllCartItems(); while (it.hasNext()): the call itself is the source
                    Expr source = l.target;
                    colls.putIfAbsent(source.text, source);
                    iterators.putIfAbsent(source.text, source);
                    int[] n = trues.computeIfAbsent(source.text, k -> new int[1]);
                    if (c.isTrue()) n[0]++;
                } else if ("hasNext".equals(l.name) && l.args.isEmpty() && !isIteratorOf(l.target) && l.target.kind == Expr.Kind.CALL
                        && "Ljava/util/Iterator;".equals(l.target.desc)) {
                    int[] n = trues.computeIfAbsent(stripIterationTag(l.target.text), k -> new int[1]);
                    if (c.isTrue()) n[0]++;
                } else if ("hasNext".equals(l.name) && l.args.isEmpty() && isIteratorOf(l.target)) {
                    Expr coll = l.target.target;
                    colls.putIfAbsent(coll.text, coll);
                    iterators.putIfAbsent(coll.text, l.target);
                    int[] n = trues.computeIfAbsent(coll.text, k -> new int[1]);
                    if (c.isTrue()) n[0]++;
                } else if ("contains".equals(l.name) && l.args.size() == 1 && "(Ljava/lang/Object;)Z".equals(l.memberDesc)
                        && !l.text.contains(" [iteration ")) {
                    colls.putIfAbsent(l.target.text, l.target);
                    contained.computeIfAbsent(l.target.text, k -> new ArrayList<>());
                    if (c.isTrue()) contained.get(l.target.text).add(l.args.get(0));
                }
            }
            for (var e : colls.entrySet()) {
                Expr coll = e.getValue();
                if (coll.kind == Expr.Kind.FIELD && coll.target != null && coll.target.kind == Expr.Kind.THIS && deps.containsKey(coll.name)) continue;
                if (!(coll.kind == Expr.Kind.PARAM || arrangeable(coll)) || coll.desc == null) continue;
                Type collType = Type.getType(coll.desc);
                Expr iterator = iterators.get(e.getKey());
                Loop loop = iterator == null ? null : new Loop(iterator);
                List<String> elems = new ArrayList<>();
                String alias = null;
                if (loop != null) {
                    // element type: what the loop body casts next() to
                    String[] elemDesc = new String[1];
                    String[] aliasOf = new String[1];
                    List<Expr> all = new ArrayList<>();
                    for (Cond c : p.conds()) { all.add(c.left()); if (c.right() != null) all.add(c.right()); }
                    for (Effect ef : p.effects()) { if (ef.target() != null) all.add(ef.target()); all.addAll(ef.args()); }
                    if (p.outcome().value() != null) all.add(p.outcome().value());
                    for (Expr root : all) root.mentions(x -> {
                        if (loop.isElement(x)) {
                            if (elemDesc[0] == null || "Ljava/lang/Object;".equals(elemDesc[0])) elemDesc[0] = x.desc;
                            if (x.expansion() != null) aliasOf[0] = x.text;
                        }
                        return false;
                    });
                    alias = aliasOf[0];
                    Type elemType = elemDesc[0] == null ? Type.getType(Object.class) : Type.getType(elemDesc[0]);
                    if (elemType.getInternalName().equals("java/lang/Object")) {
                        // the body never inspects the element on this path: type it from List<Stop> in the declaration
                        Type declared = declaredElementType(coll);
                        if (declared != null) elemType = declared;
                    }
                    if (alias != null) usedNames.add(alias);
                    for (int k = 0; k < trues.get(e.getKey())[0]; k++) {
                        String v;
                        if (elemType.getSort() == Type.OBJECT && !elemType.getInternalName().equals("java/lang/Object")
                                && !ClassInspector.isValueType(elemType.getDescriptor()) && !isEnum(elemType.getInternalName())) {
                            v = newVar(elemType);
                        } else {
                            v = nonNullSample(elemType, null);
                            if (v == null) v = "new Object()";
                        }
                        elems.add(v);
                    }
                }
                // coll.contains(x) decided true: x must be in it (false: the fresh elements above are not x)
                boolean unresolvedContains = false;
                for (Expr x : contained.getOrDefault(e.getKey(), List.of())) {
                    String v = ensure(x);
                    if (v == null) unresolvedContains = true;
                    else if (!elems.contains(v)) elems.add(v);
                }
                if (unresolvedContains) continue;
                String literal = iterableLiteral(collType, elems);
                if (literal == null) continue;
                if (coll.kind == Expr.Kind.PARAM) paramValues.put(coll.index, literal);
                else if (!bindLiteral(coll, literal)) continue;
                arrangedCollections.add(e.getKey());
                if (loop == null) continue;
                int taken = trues.get(e.getKey())[0];
                loop.elements.addAll(elems.subList(0, taken));
                for (String v : loop.elements) if (v.matches("[a-z][A-Za-z0-9_$]*")) ensured.put(v, v);
                // after the loop (or when it returns from inside), the loop variable is the last element taken
                if (taken > 0 && alias != null && elems.get(taken - 1).matches("[a-z][A-Za-z0-9_$]*")) {
                    ensured.put(alias, elems.get(taken - 1));
                }
                loops.add(loop);
            }
        }

        /** Collections (by expression text) this test filled with real elements: their contains(..) is decided. */
        final Set<String> arrangedCollections = new HashSet<>();

        static String stripIterationTag(String text) {
            return text.replaceAll(" \\[iteration \\d+\\]", "");
        }

        /** A mutable collection of the given elements in iteration order, or null for unsupported types. */
        String iterableLiteral(Type t, List<String> elems) {
            if (t.getSort() != Type.OBJECT) return null;
            imports.add("java.util.List");
            String of = "List.of(" + String.join(", ", elems) + ")";
            return switch (t.getInternalName()) {
                case "java/util/Iterator" -> { // a real iterator, so the code under test may also remove()
                    if (elems.isEmpty()) {
                        imports.add("java.util.Collections");
                        yield "Collections.emptyIterator()";
                    }
                    imports.add("java.util.ArrayList");
                    yield "new ArrayList<>(" + of + ").iterator()";
                }
                case "java/util/List", "java/util/Collection", "java/lang/Iterable", "java/util/ArrayList" -> {
                    imports.add("java.util.ArrayList");
                    yield "new ArrayList<>(" + of + ")";
                }
                case "java/util/Set", "java/util/LinkedHashSet", "java/util/HashSet" -> {
                    imports.add("java.util.LinkedHashSet");
                    yield "new LinkedHashSet<>(" + of + ")";
                }
                default -> null;
            };
        }

        /**
         * The decision rewritten onto the variable of the element the loop is at, or null when it does not involve a
         * driven loop. Decisions come in path order, so the element is the count of {@code hasNext()} = true so far
         * (a decision first evaluated in a later iteration carries no "[iteration k]" suffix, so the suffix cannot tell).
         */
        Cond onElement(Cond c) {
            for (Loop loop : loops) {
                if (loop.isHasNext(c.left())) {
                    if (c.isTrue()) loop.current++;
                    return c; // consumed: the collection's size decides it
                }
                boolean mentions = c.left().mentions(loop::isElement) || (c.right() != null && c.right().mentions(loop::isElement));
                if (!mentions) continue;
                int k = Math.max(loop.current, 1);
                if (k > loop.elements.size()) return null;
                String v = loop.elements.get(k - 1);
                if (!ensured.containsKey(v)) return null; // value element: its decisions cannot be stubbed
                Expr el = Expr.unknown(v, null);
                Expr left = c.left().substitute(loop::isElement, el);
                Expr right = c.right() == null ? null : c.right().substitute(loop::isElement, el);
                if (left == c.left() && left.text.contains(" [iteration ")) return null;
                return new Cond(c.kind(), left, c.op(), right, c.value(), c.valueExpr(), c.otherCases());
            }
            return null;
        }

        final Cond SAME_AS_BEFORE = Cond.bool(Expr.constant(1, "Z"), true);

        /**
         * A later-iteration decision on something the loop does not change (a parameter's getter, not the element or
         * the iterator): {@link #SAME_AS_BEFORE} when an earlier iteration already decided it the same way, the
         * decision without its iteration tag when it is decided here first, null when it varies or contradicts.
         */
        Cond loopInvariant(Cond c, Cond original) {
            boolean varies = c.left().mentions(x -> x.kind == Expr.Kind.CALL && (Set.of("hasNext", "next", "iterator").contains(x.name)))
                    || (c.right() != null && c.right().mentions(x -> x.kind == Expr.Kind.CALL && Set.of("hasNext", "next", "iterator").contains(x.name)))
                    || c.left().mentions(x -> x.kind == Expr.Kind.LOCAL || x.kind == Expr.Kind.UNKNOWN);
            if (varies) return null;
            String base = stripIteration(c.key());
            for (Cond earlier : p.conds()) {
                if (earlier == original) break;
                if (stripIteration(earlier.key()).equals(base)) return earlier.value().equals(c.value()) ? SAME_AS_BEFORE : null;
            }
            Expr left = c.left().withText(stripIteration(c.left().text));
            Expr right = c.right() == null ? null : c.right().withText(stripIteration(c.right().text));
            return new Cond(c.kind(), left, c.op(), right, c.value(), c.valueExpr(), c.otherCases());
        }

        /**
         * A call on a mock decided again in later loop iterations with a different outcome
         * ({@code while (!future.isDone()) Thread.sleep(..)}, {@code while ((n = in.read()) != -1)}): one stub answering
         * each iteration in turn, {@code when(future.isDone()).thenReturn(false, true)}. Returns the decisions it covers.
         */
        Set<Cond> arrangeSequences() {
            Set<Cond> covered = Collections.newSetFromMap(new IdentityHashMap<>());
            LinkedHashMap<String, List<Cond>> groups = new LinkedHashMap<>();
            for (Cond c : p.conds()) {
                Expr l = c.left();
                if (l == null || l.kind != Expr.Kind.CALL || l.target == null || l.target.kind == Expr.Kind.THIS || isObjectMethod(l)) continue;
                if (c.kind() != Cond.Kind.BOOL && !(c.kind() == Cond.Kind.CMP && c.right() != null && c.right().kind == Expr.Kind.CONST)) continue;
                if (Set.of("hasNext", "next", "iterator").contains(l.name) || loops.stream().anyMatch(lp -> lp.isHasNext(l))) continue;
                groups.computeIfAbsent(stripIteration(c.key()), k -> new ArrayList<>()).add(c);
            }
            for (List<Cond> g : groups.values()) {
                if (g.size() < 2 || g.stream().map(Cond::value).distinct().count() < 2) continue;
                Expr call = g.get(0).left();
                Type t = call.desc == null ? null : Type.getType(call.desc);
                if (t == null || Type.getReturnType(call.memberDesc).getSort() == Type.VOID) continue;
                List<String> answers = new ArrayList<>();
                for (Cond c : g) {
                    String a;
                    if (c.kind() == Cond.Kind.BOOL) a = c.isTrue() ? "true" : "false";
                    else {
                        String op = c.isTrue() ? c.op() : Cond.negate(c.op());
                        Number n = c.right().value instanceof Number num ? num : null;
                        a = n == null ? null : solveNumeric(t, List.of(new Constraint(op, c.right(), n)));
                    }
                    if (a == null) { answers = null; break; }
                    answers.add(a);
                }
                if (answers == null) continue;
                String tv = ensure(call.target);
                if (tv == null) continue;
                stubs.put(stubKey(tv, call), "when(" + tv + "." + call.name + "(" + matchers(call) + ")).thenReturn(" + String.join(", ", answers) + ");");
                covered.addAll(g);
            }
            return covered;
        }

        String stripIteration(String text) {
            return text.replaceAll(" \\[iteration \\d+\\]", "");
        }

        void collect() {
            arrangePeers();
            arrangeLoops();
            Set<Cond> sequenced = arrangeSequences();
            for (Cond c0 : p.conds()) {
                if (sequenced.contains(c0)) continue;
                Cond c = c0;
                if (c0.kind() == Cond.Kind.BOOL && c0.left().kind == Expr.Kind.CALL && "contains".equals(c0.left().name)
                        && c0.left().target != null && arrangedCollections.contains(c0.left().target.text)) continue;
                if (!loops.isEmpty()) {
                    Cond rewritten = onElement(c0);
                    if (rewritten != null && loops.stream().anyMatch(l -> l.isHasNext(c0.left()))) continue;
                    if (rewritten != null) c = rewritten;
                }
                if (collectPeerComparison(c)) continue;
                if (c.left().text.contains(" [iteration ")) {
                    Cond invariant = loopInvariant(c, c0);
                    if (invariant == SAME_AS_BEFORE) continue; // e.g. the parameter's pet.isNew(): one stub answers every iteration
                    if (invariant != null) c = invariant;
                    else {
                        // stubbing one value for a per-iteration decision (e.g. hasNext()) could loop forever
                        todos.add("loop: arrange so that `" + c.describe() + "`");
                        continue;
                    }
                }
                switch (c.kind()) {
                    case THROWS -> {
                        if (c.isTrue()) arrangeThrow(c.left(), c.op());
                        else arrangeNoThrow(c.left());
                    }
                    case BOOL -> collectBool(c.left(), c.isTrue(), c);
                    case CMP -> {
                        String op = c.isTrue() ? c.op() : Cond.negate(c.op());
                        Expr l = c.left(), rgt = c.right();
                        // a.compareTo(b) <op> 0   (BigDecimal, Comparable)
                        if (l.kind == Expr.Kind.CALL && "compareTo".equals(l.name) && l.target != null && l.args.size() == 1
                                && rgt.kind == Expr.Kind.CONST && Integer.valueOf(0).equals(rgt.value)) {
                            Number n = numericValue(l.args.get(0));
                            if (n != null) {
                                constrain(l.target, op, l.args.get(0), n);
                                break;
                            }
                        }
                        // identity checks in equals(): this == o  →  pass the subject itself; != → any other instance
                        if ((op.equals("==") || op.equals("!=")) && !isStatic
                                && ((l.kind == Expr.Kind.THIS && rgt.kind == Expr.Kind.PARAM) || (rgt.kind == Expr.Kind.THIS && l.kind == Expr.Kind.PARAM))) {
                            Expr param = l.kind == Expr.Kind.PARAM ? l : rgt;
                            if (op.equals("==")) paramValues.put(param.index, "subject");
                            else if ("subject".equals(paramValues.get(param.index))) todos.add("`" + c.describe() + "` contradicts an earlier identity check");
                            break;
                        }
                        // s.length() <op> n  →  pick a length, then a string of that length
                        if (l.kind == Expr.Kind.CALL && "length".equals(l.name) && l.target != null && l.args.isEmpty()
                                && "Ljava/lang/String;".equals(l.target.desc) && rgt.kind == Expr.Kind.CONST && rgt.value instanceof Number n) {
                            String len = solveNumeric(Type.INT_TYPE, List.of(new Constraint(op, rgt, n)));
                            if (len != null && Integer.parseInt(len) >= 0 && Integer.parseInt(len) <= 10_000) {
                                constrain(l.target, "==", Expr.constant("a".repeat(Integer.parseInt(len)), "Ljava/lang/String;"));
                                break;
                            }
                        }
                        if (splitCount(l, op, rgt)) break;
                        if (rgt.isConst() || isEnumConst(rgt)) constrain(l, op, rgt, rgt.value instanceof Number num ? num : null);
                        else if (l.isConst() || isEnumConst(l)) constrain(rgt, op, l, l.value instanceof Number num ? num : null);
                        else if (isAssignableNumber(rgt) && isAssignableNumber(l)) {
                            // e.g. attempts >= this.maxRetries: pin one side, solve the other against it
                            Number pinned = pinnedValue(rgt);
                            Expr k = Expr.constant(pinned, rgt.desc);
                            constrain(rgt, "==", k, pinned);
                            constrain(l, op, k, pinned);
                        } else if (collectMinMax(l, op, rgt) || collectMinMax(rgt, mirrorOp(op), l)) {
                            // page <= Math.max(results.getTotalPages(), 1): pinned the inner value, solved the other side
                        } else todos.add("arrange so that `" + c.describe() + "`");
                    }
                    case SWITCH -> {
                        if (stringSwitchHash(c)) {
                            // switch (s.hashCode()): the case is chosen by the s.equals("..") decision that follows;
                            // the default branch needs a string hashing to none of the cases
                            if (c.value().contains("default")) {
                                Set<String> hashes = new HashSet<>();
                                for (Expr o : c.otherCases()) hashes.add(o.text);
                                for (Cond o : p.conds()) if (o.kind() == Cond.Kind.SWITCH && o.left() == c.left()) hashes.addAll(Arrays.asList(o.value().split(" \\| ")));
                                String pick = "value";
                                for (int k = 0; hashes.contains(String.valueOf(pick.hashCode())); k++) pick = "value" + k;
                                constrain(c.left().target, "==", Expr.constant(pick, "Ljava/lang/String;"));
                            }
                            break;
                        }
                        if (c.value().contains("default")) {
                            if (!c.otherCases().isEmpty() && c.otherCases().stream().allMatch(TestGenerator.this::isEnumConst)) {
                                for (Expr other : c.otherCases()) constrain(c.left(), "!=", other);
                            } else {
                                todos.add("arrange so that `" + c.left().text + "` matches no explicit case (default branch)");
                            }
                        } else if (c.value().startsWith("instanceof ")) {
                            String simple = c.value().substring("instanceof ".length()).split(" \\| ")[0].trim();
                            String internal = null;
                            try {
                                internal = repo.require(simple).name;
                            } catch (IllegalArgumentException ignored) {
                                // not in the archive
                            }
                            if (internal != null) constrain(c.left(), "instanceof", Expr.unknown(internal, "L" + internal + ";").withDesc("L" + internal + ";"), 1);
                            else todos.add("pass an instance of " + simple + " as `" + c.left().text + "`");
                        } else if (c.valueExpr() != null) constrain(c.left(), "==", c.valueExpr());
                        else {
                            String first = c.value().split(" \\| ")[0].trim();
                            try {
                                constrain(c.left(), "==", Expr.constant(Integer.parseInt(first), "I"), Integer.parseInt(first));
                            } catch (NumberFormatException ex) {
                                if (first.startsWith("'") && first.length() == 3) constrain(c.left(), "==", Expr.constant(first.charAt(1), "C"));
                                else todos.add("arrange so that `" + c.left().text + "` is " + c.value());
                            }
                        }
                    }
                }
            }
        }

        /**
         * Well-known static helpers (Spring / commons-lang StringUtils, Objects, CollectionUtils) translated into
         * constraints on their argument, e.g. {@code StringUtils.hasText(pet.getName())} → {@code pet.getName()} non-empty.
         */
        boolean collectStaticPredicate(Expr e, boolean v) {
            String owner = Names.simple(e.owner);
            Expr a = e.args.get(0);
            if (owner.equals("StringUtils") || owner.equals("Strings") || owner.equals("CollectionUtils") || owner.equals("ObjectUtils")) {
                switch (e.name) {
                    case "hasText", "hasLength", "isNotBlank", "isNotEmpty" -> {
                        constrain(a, "empty", null, v ? 0 : 1);
                        return true;
                    }
                    case "isEmpty", "isBlank", "isNullOrEmpty" -> {
                        constrain(a, "empty", null, v ? 1 : 0);
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            if (owner.equals("Objects")) {
                switch (e.name) {
                    case "isNull" -> {
                        constrain(a, v ? "==" : "!=", Expr.nul());
                        return true;
                    }
                    case "nonNull" -> {
                        constrain(a, v ? "!=" : "==", Expr.nul());
                        return true;
                    }
                    case "equals" -> {
                        if (e.args.size() != 2) return false;
                        Expr b = e.args.get(1);
                        if (b.isConst() || isEnumConst(b)) { constrain(a, v ? "==" : "!=", b); return true; }
                        if (a.isConst() || isEnumConst(a)) { constrain(b, v ? "==" : "!=", a); return true; }
                        return sameOrDifferent(a, b, v);
                    }
                    default -> {
                        return false;
                    }
                }
            }
            return false;
        }

        /** {@code a.equals(b)} over two arrangeable values: bind both to one sample (true) or two distinct samples (false). */
        boolean sameOrDifferent(Expr a, Expr b, boolean same) {
            String desc = a.desc != null && !a.desc.equals("Ljava/lang/Object;") ? a.desc : b.desc;
            if (desc == null || !arrangeable(a) || !arrangeable(b)) return false;
            // one side already has a value (an earlier decision on it, or a @Mock field of the subject): relate the
            // other side to it instead of picking both afresh (e.g. the same parameter compared on every loop iteration)
            Expr pa = pinnedEquality(a), pb = pinnedEquality(b);
            Expr pinned = null, free = null;
            if (pb != null && pa == null) { pinned = pb; free = a; }
            if (pa != null && pb == null) { pinned = pa; free = b; }
            if (pinned != null && same) {
                constrain(free, "==", pinned);
                return true;
            }
            Expr first, second;
            switch (desc) {
                case "Ljava/lang/Integer;", "I", "Ljava/lang/Short;", "S" -> { first = Expr.constant(1, "I"); second = Expr.constant(2, "I"); }
                case "Ljava/lang/Long;", "J" -> { first = Expr.constant(1L, "J"); second = Expr.constant(2L, "J"); }
                case "Ljava/lang/String;" -> { first = Expr.constant("value", desc); second = Expr.constant("other", desc); }
                case "Z", "Ljava/lang/Boolean;" -> { first = Expr.constant(1, "Z"); second = Expr.constant(0, "Z"); }
                default -> {
                    Type t = Type.getType(desc);
                    if (isMockable(t)) {
                        // mocks are equal only to themselves: one fresh mock per distinct value needed
                        first = Expr.unknown(newVar(t), desc);
                        second = pinned != null || same ? first : Expr.unknown(newVar(t), desc);
                    } else {
                        String[] two = distinctSamples(t);
                        if (two == null) return false;
                        first = Expr.unknown(two[0], desc);
                        second = Expr.unknown(two[1], desc);
                    }
                }
            }
            if (pinned != null) {
                constrain(free, "==", pinned.text.equals(first.text) ? second : first);
                return true;
            }
            constrain(a, "==", first);
            constrain(b, "==", same ? first : second);
            return true;
        }

        boolean isMockable(Type t) {
            return t.getSort() == Type.OBJECT && !isEnum(t.getInternalName()) && !ClassInspector.isValueType(t.getDescriptor())
                    && !t.getInternalName().startsWith("java/");
        }

        /** Two unequal values of a type, as source: enum constants, shifted dates, or two mocks (identity equality). */
        String[] distinctSamples(Type t) {
            if (t.getSort() != Type.OBJECT) return null;
            String d = t.getDescriptor();
            if (isEnum(t.getInternalName())) {
                List<String> cs = ClassInspector.enumConstants(repo.node(t.getInternalName()));
                if (cs.size() < 2) return null;
                return new String[]{imports.ref(t) + "." + cs.get(0), imports.ref(t) + "." + cs.get(1)};
            }
            String factory = collectionFactory(t);
            if ("List.of".equals(factory) || "Set.of".equals(factory)) {
                String coll = mutableCollection(t);
                return new String[]{coll, coll.replace("<>()", "<>(" + factory + "(new Object()))")};
            }
            String base = nonNullSample(t, null);
            if (base == null) return null;
            if (d.startsWith("Ljava/time/Local")) return new String[]{base, base + ".plusDays(1)"};
            if (d.equals("Ljava/time/Instant;")) return new String[]{base, base + ".plusSeconds(86400)"};
            if (d.equals("Ljava/math/BigDecimal;")) return new String[]{base, "BigDecimal.TEN"};
            if (d.equals("Ljava/lang/Long;")) return new String[]{"1L", "2L"};
            if (d.equals("Ljava/lang/Double;")) return new String[]{"1.0", "2.0"};
            return null;
        }

        /** The constant an earlier decision pinned {@code e} to with {@code ==}, or null. */
        Expr pinnedEquality(Expr e) {
            if (e.kind == Expr.Kind.FIELD && e.target != null && e.target.kind == Expr.Kind.THIS && deps.containsKey(e.name)) {
                return Expr.unknown(e.name, e.desc); // the subject's @Mock collaborator
            }
            Target t = targets.get(canon(e));
            if (t == null) return null;
            for (Constraint c : t.cs) {
                if (c.op().equals("==") && c.other() != null && (c.other().isConst() || c.other().kind == Expr.Kind.UNKNOWN)) return c.other();
            }
            return null;
        }

        /**
         * Makes {@code call} throw {@code exception}: {@code when(..).thenThrow(..)} / {@code doThrow(..).when(..)} on a
         * mock, or input that makes a JDK parser fail ({@code Integer.parseInt(param)} → a non-number).
         */
        void arrangeThrow(Expr call, String exception) {
            String simple = Names.simple(exception);
            if (call.kind == Expr.Kind.CALL && call.target != null && call.target.kind != Expr.Kind.THIS && !isObjectMethod(call)) {
                String tv = ensure(call.target);
                if (tv != null) {
                    String ex = caughtException(call, exception);
                    boolean isVoid = Type.getReturnType(call.memberDesc).getSort() == Type.VOID;
                    stubs.put(stubKey(tv, call), isVoid
                            ? "doThrow(" + ex + ").when(" + tv + ")." + call.name + "(" + matchers(call) + ");"
                            : "when(" + tv + "." + call.name + "(" + matchers(call) + ")).thenThrow(" + ex + ");");
                    return;
                }
            }
            if (call.kind == Expr.Kind.CALL && call.target == null && !call.args.isEmpty()
                    && "Ljava/lang/String;".equals(call.args.get(0).desc) && arrangeable(call.args.get(0))
                    && (call.name.startsWith("parse") || call.name.equals("valueOf"))
                    && (call.owner.startsWith("java/lang/") || call.owner.startsWith("java/time/") || call.owner.startsWith("java/math/"))) {
                constrain(call.args.get(0), "==", Expr.constant("not-a-valid-value", "Ljava/lang/String;"));
                return;
            }
            if (interruptible(call) && exception.equals("java/lang/InterruptedException") && firstInterruptible(call)) {
                // a blocking call throws at once when the thread is already interrupted
                arrange.add("Thread.currentThread().interrupt(); // makes `" + call.text + "` throw InterruptedException");
                afterAct.add("Thread.interrupted(); // clear the flag the test set");
                return;
            }
            todos.add("make `" + call.text + "` throw " + simple);
        }

        /** Thread.sleep / TimeUnit.sleep / Thread.join: throw InterruptedException when the thread is interrupted. */
        static boolean interruptible(Expr call) {
            if (call.kind != Expr.Kind.CALL || call.owner == null) return false;
            return (call.owner.equals("java/lang/Thread") && (call.name.equals("sleep") || call.name.equals("join")))
                    || (call.owner.equals("java/util/concurrent/TimeUnit") && call.name.equals("sleep"));
        }

        /** No interruptible call on this path is required to complete normally (the flag would make it throw instead). */
        boolean firstInterruptible(Expr call) {
            return p.conds().stream().noneMatch(c -> c.kind() == Cond.Kind.THROWS && !c.isTrue() && c.left() != null && interruptible(c.left()));
        }

        /**
         * The exception to throw: a real instance, or a mock when the handler on this path inspects it
         * ({@code ex.getMostSpecificCause().getMessage()}) so those getters can be stubbed like any collaborator.
         */
        String caughtException(Expr call, String exception) {
            String text = dev.truthseeker.symbolic.PathExplorer.thrownText(exception, call);
            Set<String> names = new LinkedHashSet<>();
            List<Expr> all = new ArrayList<>();
            for (Cond c : p.conds()) { all.add(c.left()); if (c.right() != null) all.add(c.right()); }
            for (Effect ef : p.effects()) { if (ef.target() != null) all.add(ef.target()); all.addAll(ef.args()); }
            for (Expr root : all) root.mentions(x -> {
                if (x.kind == Expr.Kind.UNKNOWN && canon(x).equals(text)) names.add(x.text);
                return false;
            });
            Type t = Type.getObjectType(exception);
            if (names.isEmpty()) return exceptionInstance(t);
            String v = newVar(t);
            names.add(text);
            for (String n : names) ensured.put(n, v);
            return v;
        }

        /** Valid input for a JDK parser the path needs to succeed ({@code Integer.parseInt(s)} → "1"). */
        void arrangeNoThrow(Expr call) {
            if (call.kind != Expr.Kind.CALL || call.target != null || call.args.isEmpty()) return;
            Expr a = call.args.get(0);
            if (!"Ljava/lang/String;".equals(a.desc) || !arrangeable(a)) return;
            String valid = switch (call.owner) {
                case "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/math/BigInteger" -> "1";
                case "java/lang/Double", "java/lang/Float", "java/math/BigDecimal" -> "1.5";
                case "java/time/LocalDate" -> "2024-01-15";
                case "java/time/LocalDateTime" -> "2024-01-15T10:30:00";
                case "java/time/LocalTime" -> "10:30:00";
                case "java/time/Instant" -> "2024-01-15T10:30:00Z";
                default -> null;
            };
            if (valid != null) constrain(a, "==", Expr.constant(valid, "Ljava/lang/String;"));
        }

        /** An instance of an exception type: its (String) or no-arg constructor, else a mock (still a Throwable). */
        String exceptionInstance(Type t) {
            String ref = imports.ref(t);
            ClassNode node = repo.node(t.getInternalName());
            if (node != null) {
                if ((node.access & Opcodes.ACC_ABSTRACT) == 0) {
                    for (MethodNode m : node.methods) {
                        if (m.name.equals("<init>") && (m.access & Opcodes.ACC_PUBLIC) != 0 && m.desc.equals("(Ljava/lang/String;)V")) return "new " + ref + "(\"simulated failure\")";
                    }
                    for (MethodNode m : node.methods) {
                        if (m.name.equals("<init>") && (m.access & Opcodes.ACC_PUBLIC) != 0 && m.desc.equals("()V")) return "new " + ref + "()";
                    }
                }
                return "mock(" + ref + ".class)";
            }
            try {
                Class<?> k = Class.forName(t.getClassName(), false, ClassLoader.getPlatformClassLoader());
                if (!java.lang.reflect.Modifier.isAbstract(k.getModifiers())) {
                    try { k.getConstructor(String.class); return "new " + ref + "(\"simulated failure\")"; } catch (NoSuchMethodException ignored) { }
                    try { k.getConstructor(); return "new " + ref + "()"; } catch (NoSuchMethodException ignored) { }
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
                // unknown here
            }
            return "mock(" + ref + ".class)";
        }

        /**
         * {@code x <op> Math.max(a, k)} / {@code Math.min}: pin the arrangeable operand {@code a}, evaluate, and solve
         * {@code x} against the result.
         */
        boolean collectMinMax(Expr x, String op, Expr mm) {
            if (mm.kind != Expr.Kind.CALL || mm.target != null || !"java/lang/Math".equals(mm.owner) || mm.args.size() != 2
                    || !(mm.name.equals("max") || mm.name.equals("min")) || !isAssignableNumber(x) && !arrangeable(x)) return false;
            long[] vals = new long[2];
            for (int i = 0; i < 2; i++) {
                Expr a = mm.args.get(i);
                if (a.kind == Expr.Kind.CONST && a.value instanceof Number n) {
                    vals[i] = n.longValue();
                } else if (a.desc != null && "IJ".contains(a.desc) && a.desc.length() == 1 && (arrangeable(a) || isAssignableNumber(a))) {
                    Number pin = pinnedValue(a);
                    constrain(a, "==", Expr.constant(pin, a.desc), pin);
                    vals[i] = pin.longValue();
                } else {
                    return false;
                }
            }
            long r = mm.name.equals("max") ? Math.max(vals[0], vals[1]) : Math.min(vals[0], vals[1]);
            Expr k = "J".equals(x.desc) ? Expr.constant(r, "J") : Expr.constant((int) r, "I");
            constrain(x, op, k, k.value instanceof Number n ? n : null);
            return true;
        }

        /** {@code a op b} written as {@code b op' a}. */
        static String mirrorOp(String op) {
            return switch (op) {
                case "<" -> ">"; case ">" -> "<"; case "<=" -> ">="; case ">=" -> "<="; default -> op;
            };
        }

        /** Values a test can set: parameters, fields of the subject, and calls on mockable receivers. */
        boolean arrangeable(Expr e) {
            return switch (e.kind) {
                case PARAM -> true;
                case FIELD -> e.target != null && ((e.target.kind == Expr.Kind.THIS && !isStatic) || isPeerField(e));
                case CALL -> e.target != null && e.target.kind != Expr.Kind.THIS
                        && (e.target.kind == Expr.Kind.PARAM || e.target.kind == Expr.Kind.CALL || e.target.kind == Expr.Kind.FIELD
                        || ensured.containsKey(e.target.text));
                default -> false;
            };
        }

        boolean isNow(Expr e) {
            return e.kind == Expr.Kind.CALL && e.target == null && "now".equals(e.name) && e.owner.startsWith("java/time/");
        }

        boolean isTemporal(Expr e) {
            return e.desc != null && e.desc.startsWith("Ljava/time/");
        }

        /** A date/time a day in the future or past, as source: {@code LocalDate.now().plusDays(1)}. */
        String relativeToNow(String desc, boolean future) {
            String type = imports.ref(Type.getType(desc));
            if (desc.equals("Ljava/time/Instant;")) return type + ".now()." + (future ? "plusSeconds(86400)" : "minusSeconds(86400)");
            return type + ".now()." + (future ? "plusDays(1)" : "minusDays(1)");
        }

        /**
         * Preconditions implied by the path without an explicit branch: an {@code Optional} that is unwrapped with
         * {@code get()}/{@code orElseThrow()} on a non-throwing path must be present, and the receiver of a chained
         * call must be a mock rather than null.
         */
        void ensureImplicit() {
            requireDereferencedParams();
            // everything recorded on a throwing path happened before the throw; only an NPE path may rely on a null
            if (p.outcome().kind() == Path.OutcomeKind.THROW && "java/lang/NullPointerException".equals(p.outcome().exceptionType())) return;
            List<Expr> roots = new ArrayList<>();
            for (Effect ef : p.effects()) {
                if (ef.target() != null) roots.add(ef.target());
                roots.addAll(ef.args());
                if (ef.value() != null) roots.add(ef.value());
            }
            if (p.outcome().value() != null) roots.add(p.outcome().value());
            // receivers of side-effect calls (cargo.specifyNewRoute(..)) must exist
            for (Effect ef : p.effects()) {
                if (ef.kind() == Effect.Kind.CALL && ef.target() != null) requireNonNull(ef.target());
            }
            // so must everything the path dereferenced, also inside inlined constructors (cartItem.getItem().getItemId())
            for (Expr d : p.dereferenced()) {
                Loop over = null;
                for (Loop l : loops) if (d.mentions(l::isElement)) over = l;
                if (over == null) {
                    requireNonNull(d);
                    continue;
                }
                // cartItem.getItem() inside a driven loop: every element the test supplies needs its own answer
                for (String v : over.elements) {
                    if (!ensured.containsKey(v)) continue;
                    Loop l = over;
                    requireNonNull(d.substitute(l::isElement, Expr.unknown(v, null)));
                }
            }
            // a collaborator's answer handed on to code that is not followed (order.initOrder(accountBean.getAccount(), cart))
            for (Effect ef : p.effects()) {
                if (ef.kind() != Effect.Kind.CALL) continue;
                for (Expr a : ef.args()) {
                    if (a.kind == Expr.Kind.CALL && a.desc != null && a.desc.startsWith("L") && !ClassInspector.isValueType(a.desc)) requireNonNull(a);
                }
            }
            for (Expr root : roots) {
                root.mentions(x -> {
                    // constructor arguments are usually validated (Objects.requireNonNull(origin, "Origin is required"))
                    if (x.kind == Expr.Kind.NEW) x.args.forEach(this::requireNonNull);
                    if (x.kind != Expr.Kind.CALL || x.target == null) return false;
                    Expr t = x.target;
                    if (isOptional(t) && (x.name.equals("get") || x.name.equals("orElseThrow"))) {
                        if (!boundLiteral.containsKey(t.text)) ensure(x);
                    } else if (t.kind == Expr.Kind.CALL && !isOptional(t) && t.desc != null && t.desc.startsWith("L")
                            && !ClassInspector.isValueType(t.desc) && !boundLiteral.containsKey(t.text) && !boundLiteral.containsKey(canon(t))) {
                        ensure(t);
                    }
                    return false;
                });
            }
        }

        /** Makes a collaborator-produced value non-null: a mock for collaborator types, a sample for value types. */
        void requireNonNull(Expr a) {
            if (boundLiteral.containsKey(a.text) || ensured.containsKey(a.text)) return;
            // a collaborator field set by a lifecycle method rather than injected (this.handler from initialize(..))
            if (a.kind == Expr.Kind.FIELD && a.target != null && a.target.kind == Expr.Kind.THIS && !isStatic && !deps.containsKey(a.name)
                    && a.desc != null && a.desc.startsWith("L") && !ClassInspector.isValueType(a.desc) && isMockable(Type.getType(a.desc))) {
                ensure(a);
                return;
            }
            if (a.kind != Expr.Kind.CALL || a.target == null || a.target.kind == Expr.Kind.THIS) return;
            if (a.desc == null || !a.desc.startsWith("L") || isOptional(a)) return;
            if (!ClassInspector.isValueType(a.desc)) {
                ensure(a);
            } else {
                String lit = nonNullSample(Type.getType(a.desc), a);
                if (lit != null) bindLiteral(a, lit);
            }
        }

        /**
         * Parameters the path calls methods on ({@code e.getMessage()}) or casts ({@code (TextMessage) message}) need a
         * value of that type: an instance for JDK types such as exceptions, a mock of the cast type otherwise.
         */
        void requireDereferencedParams() {
            List<Expr> roots = new ArrayList<>(p.dereferenced());
            for (Cond c : p.conds()) { roots.add(c.left()); if (c.right() != null) roots.add(c.right()); }
            for (Effect ef : p.effects()) { if (ef.target() != null) roots.add(ef.target()); roots.addAll(ef.args()); if (ef.value() != null) roots.add(ef.value()); }
            if (p.outcome().kind() != Path.OutcomeKind.THROW && p.outcome().value() != null) roots.add(p.outcome().value());
            Map<Integer, Expr> used = new LinkedHashMap<>();
            for (Expr d : p.dereferenced()) if (d.kind == Expr.Kind.PARAM) used.put(d.index, d);
            for (Expr root : roots) {
                root.mentions(x -> {
                    if ((x.kind == Expr.Kind.CALL || x.kind == Expr.Kind.FIELD) && x.target != null && x.target.kind == Expr.Kind.PARAM) {
                        used.putIfAbsent(x.target.index, x.target);
                    }
                    return false;
                });
            }
            for (Expr param : used.values()) {
                Expr declared = r.params().stream().filter(q -> q.index == param.index).findFirst().orElse(null);
                if (declared == null || param.desc == null || !param.desc.startsWith("L")) continue;
                if (p.conds().stream().anyMatch(c -> c.describe().contains(declared.text + " == null"))) continue;
                String current = paramValues.get(param.index);
                boolean narrowed = !param.desc.equals(declared.desc);
                if (current != null && !current.equals("null") && !(narrowed && current.equals(defaultFor(Type.getType(declared.desc))))) continue;
                Type t = Type.getType(param.desc);
                String value = !ClassInspector.isValueType(param.desc) && isMockable(t) ? mockOf(t) : nonNullSample(t, param);
                if (value == null) continue;
                paramValues.put(param.index, value);
                if (narrowed) paramTypes.put(param.index, t);
            }
        }

        /** First call on {@code this} that the path depends on (cannot be stubbed without a spy), or null. */
        String selfCall() {
            List<Expr> all = new ArrayList<>();
            for (Cond c : p.conds()) { all.add(c.left()); if (c.right() != null) all.add(c.right()); }
            for (Effect ef : p.effects()) { if (ef.target() != null) all.add(ef.target()); all.addAll(ef.args()); }
            if (p.outcome().value() != null) all.add(p.outcome().value());
            String[] found = new String[1];
            for (Expr root : all) {
                root.mentions(x -> {
                    if (found[0] == null && x.kind == Expr.Kind.CALL && x.target != null && x.target.kind == Expr.Kind.THIS
                            && !(x.name.equals("getClass") && x.args.isEmpty())) {
                        found[0] = x.expansion() != null ? x.expansion() : x.text;
                    }
                    return false;
                });
            }
            for (Effect ef : p.effects()) {
                if (found[0] == null && ef.kind() == Effect.Kind.CALL && ef.target() != null && ef.target().kind == Expr.Kind.THIS) found[0] = ef.text();
            }
            return found[0];
        }

        final Map<String, Number> pins = new HashMap<>();

        Number pinnedValue(Expr e) {
            return pins.computeIfAbsent(e.text, k -> "J".equals(e.desc) ? (Number) 10L : (Number) 10);
        }

        boolean isAssignableNumber(Expr e) {
            if (e.desc == null || !"IJSB".contains(e.desc) || e.desc.length() != 1) return false;
            if (e.kind == Expr.Kind.PARAM) return true;
            return e.kind == Expr.Kind.FIELD && e.target != null && e.target.kind == Expr.Kind.THIS && !isStatic;
        }

        /** Numeric value of an expression whose inputs were all bound to literals, or null. */
        Number evaluate(Expr e) {
            if (e.kind == Expr.Kind.CONST && e.value instanceof Number n) return n;
            String lit = e.kind == Expr.Kind.PARAM ? paramValues.get(e.index) : boundLiteral.get(e.text);
            if (lit != null) {
                try {
                    return Long.parseLong(lit.replace("L", ""));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (e.kind == Expr.Kind.BINARY && e.args.size() == 2) {
                Number a = evaluate(e.args.get(0)), b = evaluate(e.args.get(1));
                if (a == null || b == null) return null;
                return switch (e.name) {
                    case "+" -> a.longValue() + b.longValue();
                    case "-" -> a.longValue() - b.longValue();
                    case "*" -> a.longValue() * b.longValue();
                    default -> null;
                };
            }
            return null;
        }

        void collectBool(Expr e, boolean v, Cond c) {
            if (e.kind == Expr.Kind.CALL && e.target == null && !e.args.isEmpty() && collectStaticPredicate(e, v)) return;
            if (e.kind == Expr.Kind.CALL && e.target != null) {
                switch (e.name) {
                    case "equals", "equalsIgnoreCase" -> {
                        if (e.args.size() == 1) {
                            Expr a = e.target, b = e.args.get(0);
                            if (b.isConst() || isEnumConst(b)) { constrain(a, v ? "==" : "!=", b); return; }
                            if (a.isConst() || isEnumConst(a)) { constrain(b, v ? "==" : "!=", a); return; }
                            if (sameOrDifferent(a, b, v)) return;
                        }
                    }
                    case "contains", "startsWith", "endsWith" -> {
                        // s.toLowerCase().contains("dup")  →  s = "dup" (true) / "#" (false)
                        Expr s = e.target;
                        while (s.kind == Expr.Kind.CALL && s.target != null && s.args.isEmpty() && "Ljava/lang/String;".equals(s.desc)
                                && (s.name.equals("toLowerCase") || s.name.equals("toUpperCase") || s.name.equals("trim") || s.name.equals("strip"))) {
                            s = s.target;
                        }
                        if (e.args.size() == 1 && e.args.get(0).kind == Expr.Kind.CONST && e.args.get(0).value instanceof String needle
                                && "Ljava/lang/String;".equals(s.desc) && arrangeable(s) && !needle.isEmpty()) {
                            constrain(s, "==", Expr.constant(v ? needle : "#", "Ljava/lang/String;"));
                            return;
                        }
                    }
                    case "isAfter", "isBefore" -> {
                        if (e.args.size() == 1 && isNow(e.args.get(0)) && isTemporal(e.target)) {
                            boolean future = e.name.equals("isAfter") == v;
                            constrain(e.target, "==", Expr.unknown(relativeToNow(e.target.desc, future), e.target.desc));
                            return;
                        }
                        if (e.args.size() == 1 && orderDates(e.target, e.args.get(0), e.name.equals("isAfter") == v)) return;
                    }
                    case "isEmpty" -> {
                        if (isOptional(e.target)) {
                            constrain(e.target, "present", null, v ? 0 : 1);
                            return;
                        }
                        Expr t = e.target;
                        if (isStringLike(t)) {
                            constrain(unwrapString(t), "empty", null, v ? 1 : 0);
                            return;
                        }
                        Expr src = pipelineSource(t, false);
                        if (src != t && arrangeable(src)) t = src;
                        if (t.desc != null && t.desc.startsWith("L") && mutableCollection(Type.getType(t.desc)) == null
                                && arrangeable(e) && !t.desc.equals("Ljava/lang/Object;")) {
                            constrain(e, "bool", null, v ? 1 : 0); // a mocked Page/Slice/custom container: stub isEmpty() itself
                            return;
                        }
                        constrain(t, "empty", null, v ? 1 : 0);
                        return;
                    }
                    case "isPresent" -> {
                        if (isOptional(e.target)) { constrain(e.target, "present", null, v ? 1 : 0); return; }
                    }
                    case "isBlank" -> {
                        constrain(unwrapString(e.target), "empty", null, v ? 1 : 0);
                        return;
                    }
                    default -> {}
                }
            }
            if (e.kind == Expr.Kind.INSTANCEOF) {
                constrain(e.target, "instanceof", Expr.unknown(e.owner, "L" + e.owner + ";"), v ? 1 : 0);
                return;
            }
            if (e.kind == Expr.Kind.UNARY && "!".equals(e.name)) {
                collectBool(e.target, !v, c);
                return;
            }
            constrain(e, "bool", null, v ? 1 : 0);
        }

        // ---- solving ----
        void solve() {
            // collections first: their element may settle reads such as `lastLeg == null`
            List<Target> order = new ArrayList<>(targets.values());
            order.sort(Comparator.comparingInt(t -> t.cs.stream().anyMatch(c -> c.op().equals("empty")) ? 0 : 1));
            for (Target t : order) {
                if (satisfied.contains(canon(t.expr))) continue;
                String literal = literalFor(t);
                if (literal == null) continue;
                bind(t.expr, literal, t);
            }
        }

        /** {@code keywords.split("\\s+").length > i}: a string with as many tokens as the comparison needs. */
        boolean splitCount(Expr l, String op, Expr rgt) {
            Expr arr = l.measuredArray();
            if (arr == null || arr.kind != Expr.Kind.CALL || !"split".equals(arr.name) || arr.args.isEmpty() || arr.target == null
                    || !(rgt.value instanceof Number n) || !"Ljava/lang/String;".equals(arr.target.desc)) return false;
            Expr s0 = unwrapString(arr.target);
            if (!arrangeable(s0) || !(arr.args.get(0).value instanceof String regex)) return false;
            String sep = switch (regex) {
                case "\\s+", "\\s", " +", " ", "\\s*", "[ ]+" -> " ";
                case ",", ";", ":", "\\|", "/", "-", "\\." -> regex.replace("\\", "");
                case "\\s*,\\s*", ",\\s*" -> ",";
                default -> null;
            };
            if (sep == null) return false;
            String count = solveNumeric(Type.INT_TYPE, List.of(new Constraint(op, rgt, n)));
            if (count == null) return false;
            int k = Integer.parseInt(count);
            if (k == 0 && sep.equals(" ")) { // "   ".split("\\s+") has no tokens at all
                constrain(s0, "==", Expr.constant("   ", "Ljava/lang/String;"));
                return true;
            }
            if (k < 1 || k > 20) return false;
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < k; i++) tokens.add(String.valueOf((char) ('a' + i)));
            constrain(s0, "==", Expr.constant(String.join(sep, tokens), "Ljava/lang/String;"));
            return true;
        }

        /** Targets already arranged as a side effect of another (an element read that the collection literal provides). */
        final Set<String> satisfied = new HashSet<>();

        boolean isStringLike(Expr e) {
            return "Ljava/lang/String;".equals(e.desc) || "Ljava/lang/CharSequence;".equals(e.desc);
        }

        /** {@code s.strip().toLowerCase()} → {@code s}: normalisations that keep emptiness and plain literals unchanged. */
        Expr unwrapString(Expr s) {
            while (s.kind == Expr.Kind.CALL && s.target != null && s.args.isEmpty() && "Ljava/lang/String;".equals(s.desc)
                    && Set.of("toLowerCase", "toUpperCase", "trim", "strip", "stripLeading", "stripTrailing").contains(s.name)) {
                s = s.target;
            }
            return s;
        }

        /**
         * The collection a derived one was built from, through steps that keep it empty exactly when the source is
         * ({@code List.copyOf(src.stream().distinct().sorted(c).collect(toList()))} → {@code src}). With
         * {@code keepsElements}, only steps that also keep the element objects ({@code map} is excluded).
         */
        Expr pipelineSource(Expr e, boolean keepsElements) {
            for (int guard = 0; guard < 20; guard++) {
                if (e.kind == Expr.Kind.CALL && e.target == null && e.args.size() == 1
                        && ((Set.of("java/util/List", "java/util/Set", "java/util/Collection").contains(e.owner) && e.name.equals("copyOf"))
                        || (e.owner.equals("java/util/Collections") && e.name.startsWith("unmodifiable")))) {
                    e = e.args.get(0);
                } else if (e.kind == Expr.Kind.NEW && e.args.size() == 1 && e.owner != null && e.owner.startsWith("java/util/")
                        && Set.of("ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque").contains(Names.simple(e.owner))
                        && e.args.get(0).desc != null && !e.args.get(0).desc.equals("I")) {
                    e = e.args.get(0);
                } else if (e.kind == Expr.Kind.CALL && e.target != null && (
                        (e.args.isEmpty() && Set.of("stream", "parallelStream", "distinct", "sorted", "toList").contains(e.name))
                        || (e.args.size() == 1 && e.name.equals("sorted"))
                        || (e.args.size() == 1 && e.name.equals("map") && !keepsElements)
                        || (e.args.size() == 1 && e.name.equals("collect") && isCollectingToCollection(e.args.get(0))))) {
                    e = e.target;
                } else break;
            }
            return e;
        }

        boolean isCollectingToCollection(Expr c) {
            return c.kind == Expr.Kind.CALL && c.target == null && "java/util/stream/Collectors".equals(c.owner)
                    && Set.of("toList", "toSet", "toUnmodifiableList", "toUnmodifiableSet").contains(c.name);
        }

        /**
         * {@code a.isAfter(b)} (or before) where both sides can be set: pin {@code a} to a sample date and put
         * {@code b} a day away; a side read through {@code toLocalDate()} is set as the start of that day.
         */
        boolean orderDates(Expr a, Expr b, boolean aAfter) {
            if (!isTemporal(a) || !isTemporal(b)) return false;
            Expr sa = dateSettable(a), sb = dateSettable(b);
            if (sa == null || sb == null) return false;
            String base = nonNullSample(Type.getType(a.desc), a);
            if (base == null) return false;
            String other = a.desc.equals("Ljava/time/Instant;") ? base + (aAfter ? ".minusSeconds(86400)" : ".plusSeconds(86400)")
                    : base + (aAfter ? ".minusDays(1)" : ".plusDays(1)");
            constrain(sa, "==", Expr.unknown(asDateOf(sa, a, base), sa.desc));
            constrain(sb, "==", Expr.unknown(asDateOf(sb, b, other), sb.desc));
            return true;
        }

        /** The value a test sets to control date {@code e}: itself, or the date-time it was converted from. */
        Expr dateSettable(Expr e) {
            if (e.kind == Expr.Kind.CALL && e.target != null && e.args.isEmpty() && "toLocalDate".equals(e.name)
                    && "Ljava/time/LocalDateTime;".equals(e.target.desc)) return arrangeable(e.target) ? e.target : null;
            // a call on a JDK value (date.plusDays(1)) cannot be stubbed
            if (e.kind == Expr.Kind.CALL && e.target != null && e.target.desc != null && e.target.desc.startsWith("Ljava/")) return null;
            return arrangeable(e) ? e : null;
        }

        String asDateOf(Expr settable, Expr compared, String src) {
            if (settable == compared) return src;
            return "(" + src + ").atStartOfDay()"; // LocalDate → LocalDateTime read back through toLocalDate()
        }


        String literalFor(Target t) {
            Expr e = t.expr;
            Type type = typeOf(e, t);
            Boolean nullWanted = null;
            Boolean present = null, empty = null, bool = null;
            String eqConst = null;
            Set<String> neqConsts = new LinkedHashSet<>();
            String instanceType = null;
            List<Constraint> numeric = new ArrayList<>();
            for (Constraint c : t.cs) {
                switch (c.op()) {
                    case "bool" -> bool = ((Integer) c.number()) == 1;
                    case "present" -> present = ((Integer) c.number()) == 1;
                    case "empty" -> empty = ((Integer) c.number()) == 1;
                    case "instanceof" -> {
                        if (((Integer) c.number()) == 1) instanceType = c.other().owner != null ? c.other().owner : c.other().text;
                        else nullWanted = Boolean.TRUE;
                    }
                    default -> {
                        Expr o = c.other();
                        if (o.kind == Expr.Kind.NULL) {
                            nullWanted = c.op().equals("==");
                        } else if (c.number() != null && !(o.value instanceof String)) {
                            numeric.add(c);
                        } else if (c.op().equals("==")) {
                            eqConst = literalOf(o);
                        } else if (c.op().equals("!=")) {
                            neqConsts.add(literalOf(o));
                        } else {
                            todos.add("arrange so that `" + e.text + " " + c.op() + " " + o.text + "`");
                        }
                    }
                }
            }
            if (Boolean.TRUE.equals(nullWanted)) return "null";
            if (bool != null) return bool ? "true" : "false";
            if (eqConst != null) return eqConst;
            if (present != null) {
                if (!present) return "Optional.empty()";
                imports.add("java.util.Optional");
                String elem = ensureOptionalElement(e);
                return "Optional.of(" + elem + ")";
            }
            if (empty != null) return emptyLiteral(type, empty, e);
            if (instanceType != null) {
                return mockOf(Type.getObjectType(instanceType));
            }
            if (!numeric.isEmpty()) {
                String n = solveNumeric(type, numeric);
                if (n == null) todos.add("no value found for `" + e.text + "` satisfying " + numeric.stream().map(c -> c.op() + " " + c.number()).toList());
                return n;
            }
            if (!neqConsts.isEmpty()) return otherThan(type, neqConsts, e);
            if (Boolean.FALSE.equals(nullWanted)) return nonNullSample(type, e);
            return null;
        }

        Type typeOf(Expr e, Target t) {
            if (e.desc != null) return Type.getType(e.desc);
            for (Constraint c : t.cs) if (c.other() != null && c.other().desc != null) return Type.getType(c.other().desc);
            return Type.getType(Object.class);
        }

        String solveNumeric(Type type, List<Constraint> cs) {
            List<Double> candidates = new ArrayList<>();
            for (Constraint c : cs) {
                double n = ((Number) c.number()).doubleValue();
                candidates.addAll(List.of(n, n + 1, n - 1, n + 0.5, n - 0.5));
            }
            candidates.addAll(List.of(0.0, 1.0, -1.0, 100.0));
            boolean integral = !(type.getSort() == Type.FLOAT || type.getSort() == Type.DOUBLE
                    || type.getDescriptor().equals("Ljava/lang/Double;") || type.getDescriptor().equals("Ljava/lang/Float;")
                    || type.getDescriptor().equals("Ljava/math/BigDecimal;"));
            for (double v : candidates) {
                if (integral && v != Math.floor(v)) continue;
                boolean ok = true;
                for (Constraint c : cs) {
                    double n = ((Number) c.number()).doubleValue();
                    ok &= switch (c.op()) {
                        case "==" -> v == n; case "!=" -> v != n; case "<" -> v < n; case "<=" -> v <= n; case ">" -> v > n; default -> v >= n;
                    };
                }
                if (ok) return formatNumber(type, v);
            }
            return null;
        }

        String formatNumber(Type type, double v) {
            long l = (long) v;
            boolean whole = v == Math.floor(v);
            return switch (type.getDescriptor()) {
                case "J", "Ljava/lang/Long;" -> l + "L";
                case "D", "Ljava/lang/Double;" -> whole ? l + ".0" : String.valueOf(v);
                case "F", "Ljava/lang/Float;" -> (whole ? l + ".0" : String.valueOf(v)) + "f";
                case "S" -> "(short) " + l;
                case "B" -> "(byte) " + l;
                case "C" -> "(char) " + l;
                case "Ljava/math/BigDecimal;" -> {
                    imports.add("java.math.BigDecimal");
                    yield "new BigDecimal(\"" + (whole ? String.valueOf(l) : String.valueOf(v)) + "\")";
                }
                case "Ljava/math/BigInteger;" -> {
                    imports.add("java.math.BigInteger");
                    yield "BigInteger.valueOf(" + l + ")";
                }
                default -> String.valueOf(l);
            };
        }

        String otherThan(Type type, Set<String> excluded, Expr e) {
            if (type.getSort() == Type.OBJECT && isEnum(type.getInternalName())) {
                ClassNode en = repo.node(type.getInternalName());
                String ref = imports.ref(type);
                for (String c : ClassInspector.enumConstants(en)) {
                    String lit = ref + "." + c;
                    if (excluded.stream().noneMatch(x -> x.endsWith("." + c))) return lit;
                }
            }
            if (type.getDescriptor().equals("Ljava/lang/String;")) {
                String cand = "\"other\"";
                return excluded.contains(cand) ? "\"other-value\"" : cand;
            }
            if (type.getSort() == Type.OBJECT && excluded.stream().allMatch(x -> x.contains("."))) {
                todos.add("choose a value for `" + e.text + "` other than " + excluded);
                return null;
            }
            return nonNullSample(type, e);
        }

        String emptyLiteral(Type type, boolean empty, Expr e) {
            String d = type.getDescriptor();
            if (d.equals("Ljava/lang/String;") || d.equals("Ljava/lang/CharSequence;")) return empty ? "\"\"" : "\"value\"";
            String coll = mutableCollection(type);
            if (coll != null) {
                if (empty) return coll;
                String element = elementFor(e);
                if (element != null && element.endsWith(") null")) {
                    imports.add("java.util.Arrays");
                    return coll.replace("<>()", "<>(Arrays.asList(" + element + "))"); // List.of rejects null
                }
                if (element != null) return coll.replace("<>()", "<>(" + collectionFactory(type) + "(" + element + "))");
                todos.add("`" + e.text + "` must be non-empty: put a realistic element into it");
                return coll.replace("<>()", "<>(" + collectionFactory(type) + "(new Object()))");
            }
            todos.add("arrange so that `" + e.text + ".isEmpty()` is " + empty);
            return null;
        }

        /**
         * One mock element for a collection the path needs non-empty, typed by what the code reads back
         * ({@code (Leg) this.legs.get(0)}, {@code iterator().next()}); every such read stands for that element.
         */
        String elementFor(Expr coll) {
            List<Expr> all = new ArrayList<>();
            for (Cond c : p.conds()) { all.add(c.left()); if (c.right() != null) all.add(c.right()); }
            for (Effect ef : p.effects()) { if (ef.target() != null) all.add(ef.target()); all.addAll(ef.args()); }
            if (p.outcome().value() != null) all.add(p.outcome().value());
            List<Expr> reads = new ArrayList<>();
            String key = canon(coll);
            for (Expr root : all) root.mentions(x -> {
                if (x.kind == Expr.Kind.CALL && x.target != null && x.desc != null && x.desc.startsWith("L")) {
                    boolean get = (x.name.equals("get") && x.args.size() == 1)
                            && (canon(x.target).equals(key) || canon(pipelineSource(x.target, true)).equals(key));
                    boolean next = x.name.equals("next") && x.args.isEmpty() && x.target.kind == Expr.Kind.CALL
                            && "iterator".equals(x.target.name) && x.target.target != null
                            && (canon(x.target.target).equals(key) || canon(pipelineSource(x.target.target, true)).equals(key));
                    if (get || next) reads.add(x);
                }
                return false;
            });
            Type t = null;
            for (Expr r : reads) if (!r.desc.equals("Ljava/lang/Object;")) { t = Type.getType(r.desc); break; }
            if (t == null) t = declaredElementType(coll);
            if (t == null || !isMockable(t)) return null;
            // a read the path needs null (`lastLeg == null` on a non-empty list): the element itself is null
            for (Expr r : reads) {
                Target rt = targets.get(canon(r));
                if (rt != null && rt.cs.stream().anyMatch(c -> c.op().equals("==") && c.other() != null && c.other().kind == Expr.Kind.NULL)) {
                    for (Expr x : reads) satisfied.add(canon(x));
                    return "(" + imports.ref(t) + ") null";
                }
            }
            if (reads.isEmpty()) {
                // only traversed by a stream/lambda the path does not show: an element whose getters return mocks, not null
                imports.add("org.mockito.Mockito");
                String name = newVarNamed(t);
                arrange.add(imports.ref(t) + " " + name + " = mock(" + imports.ref(t) + ".class, Mockito.RETURNS_MOCKS);");
                return name;
            }
            String v = newVar(t);
            for (Expr r : reads) {
                ensured.put(r.text, v);
                ensured.put(canon(r), v);
            }
            return v;
        }

        /** Element for {@code Optional.of(...)}: a mock of the type the code casts {@code get()} to, if visible on the path. */
        String ensureOptionalElement(Expr optionalExpr) {
            String elemDesc = findUnwrapType(optionalExpr);
            Type t = elemDesc != null ? Type.getType(elemDesc) : Type.getType(Object.class);
            String key = optionalExpr.text + ".get()";
            if (ensured.containsKey(key)) return ensured.get(key);
            if (t.getInternalName().equals("java/lang/Object")) {
                todos.add("replace the placeholder element of `" + optionalExpr.text + "` with a real instance");
                return "new Object()";
            }
            String v = newVar(t);
            for (String k : List.of(".get()", ".orElseThrow()")) ensured.put(optionalExpr.text + k, v);
            return v;
        }

        String findUnwrapType(Expr optionalExpr) {
            List<Expr> all = new ArrayList<>();
            for (Cond c : p.conds()) { all.add(c.left()); if (c.right() != null) all.add(c.right()); }
            for (Effect ef : p.effects()) { if (ef.target() != null) all.add(ef.target()); all.addAll(ef.args()); if (ef.value() != null) all.add(ef.value()); }
            if (p.outcome().value() != null) all.add(p.outcome().value());
            String[] found = new String[1];
            for (Expr root : all) {
                root.mentions(x -> {
                    if (found[0] == null && x.kind == Expr.Kind.CALL && x.target != null && x.target.text.equals(optionalExpr.text)
                            && (x.name.equals("get") || x.name.startsWith("orElse")) && x.desc != null && !x.desc.equals("Ljava/lang/Object;")) {
                        found[0] = x.desc;
                    }
                    return false;
                });
            }
            return found[0];
        }

        /** Element type from the generic signature of a subject field or parameter ({@code List<Leg> legs}). */
        Type declaredElementType(Expr coll) {
            String sig = null;
            if (coll.kind == Expr.Kind.FIELD && coll.target != null && coll.target.kind == Expr.Kind.THIS) {
                for (String c = cn.name; c != null && sig == null; ) {
                    ClassNode node = c.equals(cn.name) ? cn : repo.node(c);
                    if (node == null) break;
                    for (FieldNode fn : node.fields) if (fn.name.equals(coll.name)) sig = fn.signature;
                    c = node.superName;
                }
            } else if (coll.kind == Expr.Kind.PARAM && r.method().localVariables != null && coll.extra instanceof Integer slot) {
                for (LocalVariableNode lv : r.method().localVariables) if (lv.index == slot && lv.signature != null) sig = lv.signature;
            } else if (coll.kind == Expr.Kind.CALL && coll.owner != null) {
                for (String c = coll.owner; c != null && sig == null; ) {
                    ClassNode node = repo.node(c);
                    if (node == null) break;
                    for (MethodNode m : node.methods) {
                        if (m.name.equals(coll.name) && m.desc.equals(coll.memberDesc) && m.signature != null) {
                            sig = m.signature.substring(m.signature.indexOf(')') + 1);
                        }
                    }
                    c = node.superName;
                }
            }
            if (sig == null) return null;
            int lt = sig.indexOf('<');
            if (lt < 0) return null;
            int i = lt + 1;
            if (i < sig.length() && (sig.charAt(i) == '+' || sig.charAt(i) == '=')) i++;
            if (i >= sig.length() || sig.charAt(i) != 'L') return null;
            int end = i;
            while (end < sig.length() && sig.charAt(end) != ';' && sig.charAt(end) != '<') end++;
            return Type.getObjectType(sig.substring(i + 1, end));
        }

        String newVarNamed(Type t) {
            String base = Names.simple(t.getInternalName());
            base = Character.toLowerCase(base.charAt(0)) + base.substring(1);
            String name = base;
            while (usedNames.contains(name)) name = base + (++varCounter);
            usedNames.add(name);
            return name;
        }

        String newVar(Type t) {
            String base = Names.simple(t.getInternalName());
            base = Character.toLowerCase(base.charAt(0)) + base.substring(1);
            String name = base;
            while (usedNames.contains(name)) name = base + (++varCounter);
            usedNames.add(name);
            arrange.add(imports.ref(t) + " " + name + " = " + mockOf(t) + ";");
            return name;
        }

        /** Returns a variable that holds the (mock) value of {@code e}, creating stubs as needed; null if impossible. */
        String ensure(Expr e) {
            if (e == null) return null;
            // an alias is looked up by what it stands for: a reassigned parameter's name may mean another value
            if (e.expansion() == null && ensured.containsKey(e.text)) return ensured.get(e.text);
            if (ensured.containsKey(canon(e))) return ensured.get(canon(e));
            String v = null;
            switch (e.kind) {
                case THIS -> v = null;
                case PARAM -> {
                    Type t = Type.getType(e.desc);
                    if (t.getSort() == Type.OBJECT && !ClassInspector.isValueType(e.desc)) {
                        paramValues.putIfAbsent((Integer) e.index, mockOf(t));
                        // the code casts a wider parameter (e.g. Object) to this type: declare the variable as it
                        paramTypes.putIfAbsent(e.index, t);
                        v = paramVars.get(e.index);
                    }
                }
                case FIELD -> {
                    if (e.target != null && e.target.kind == Expr.Kind.THIS && deps.containsKey(e.name)) v = e.name;
                    else if (e.target != null && e.target.kind == Expr.Kind.THIS && Type.getType(e.desc).getSort() == Type.OBJECT
                            && !ClassInspector.isValueType(e.desc)) {
                        v = newVar(Type.getType(e.desc));
                        needsSetField = true;
                        arrange.add("setField(subject, \"" + e.name + "\", " + v + ");");
                    }
                }
                case CALL -> {
                    if (e.target != null && isOptional(e.target) && (e.name.equals("get") || e.name.equals("orElseThrow"))) {
                        String elem = ensureOptionalElement(e.target);
                        imports.add("java.util.Optional");
                        bindLiteral(e.target, "Optional.of(" + elem + ")");
                        v = elem.equals("new Object()") ? null : elem;
                        break;
                    }
                    if (isMapLookup(e) && !e.name.equals("containsKey")) {
                        if (arrangeMapLookup(e, "mock(")) v = ensured.get(canon(e));
                        break;
                    }
                    Type t = e.desc == null ? null : Type.getType(e.desc);
                    if (t == null || t.getSort() != Type.OBJECT || ClassInspector.isValueType(e.desc)) break;
                    if (e.name.equals("next") && e.args.isEmpty() && e.target.kind == Expr.Kind.CALL && "iterator".equals(e.target.name)
                            && e.target.args.isEmpty() && e.target.target != null && isMockable(t)) {
                        // page.iterator().next() on a mocked Page/Iterable: a real iterator over one mock element
                        String owner = ensure(e.target.target);
                        if (owner == null) break;
                        v = newVar(t);
                        imports.add("java.util.List");
                        stubs.put(stubKey(owner, e.target), "when(" + owner + ".iterator()).thenReturn(List.of(" + v + ").iterator());");
                        break;
                    }
                    String tv = ensure(e.target);
                    if (tv == null) break;
                    v = newVar(t);
                    stubs.put(claimStubKey(tv, e), "when(" + tv + "." + e.name + "(" + matchers(e) + ")).thenReturn(" + v + ");");
                }
                default -> v = null;
            }
            if (v != null) {
                if (e.expansion() == null) ensured.put(e.text, v);
                ensured.put(canon(e), v);
            }
            return v;
        }

        void bind(Expr e, String literal, Target t) {
            if (!bindLiteral(e, literal)) todos.add("arrange so that `" + e.text + "` = " + literal);
        }

        /** For enum instance methods: which constant to call the method on. */
        String enumReceiver;

        boolean bindLiteral(Expr e, String literal) {
            // a fresh mock only says "non-null": a mock already standing for this value (maybe under another name) does
            if (literal.startsWith("mock(") && (ensured.containsKey(e.text) || ensured.containsKey(canon(e)))) return true;
            if (literal.startsWith("mock(") && e.kind == Expr.Kind.CALL && e.target != null && !isMapLookup(e)
                    && e.desc != null && Type.getType(e.desc).getSort() == Type.OBJECT && !boundLiteral.containsKey(e.text)
                    && literal.startsWith("mock(" + imports.ref(Type.getType(e.desc)) + ".class")) {
                String tv = ensure(e.target);
                String existing = tv == null ? null : stubs.get(stubKey(tv, e));
                // the same stub (find(any())) already answers another call with a non-null value: keep that one
                if (existing != null) return !existing.endsWith("thenReturn(null);");
                if (ensure(e) != null) return true; // a named mock: later assertions can say the result is this very instance
            }
            String before = boundLiteral.get(e.text);
            boolean bound = bindLiteralTo(e, literal);
            if (!bound) { // nothing was arranged: do not claim the value
                if (before == null) boundLiteral.remove(e.text);
                else boundLiteral.put(e.text, before);
            }
            return bound;
        }

        private boolean bindLiteralTo(Expr e, String literal) {
            boundLiteral.put(e.text, literal);
            switch (e.kind) {
                case THIS -> {
                    if ((cn.access & Opcodes.ACC_ENUM) == 0) return false;
                    enumReceiver = literal;
                    return true;
                }
                case PARAM -> {
                    if (peers.containsKey(e.index) && !literal.equals("null")) return true; // the peer is non-null already
                    paramValues.put(e.index, literal);
                    return true;
                }
                case FIELD -> {
                    if (e.target == null) return false;
                    if (e.target.kind == Expr.Kind.THIS) {
                        if (deps.containsKey(e.name) && !literal.equals("null")) {
                            // the @Mock is already non-null: it *is* the value
                            boundLiteral.put(e.text, e.name);
                            ensured.put(e.text, e.name);
                            return true;
                        }
                        needsSetField = true;
                        arrange.add("setField(subject, \"" + e.name + "\", " + literal + ");");
                        return true;
                    }
                    String tv = ensure(e.target);
                    if (tv == null) return false;
                    needsSetField = true;
                    arrange.add("setField(" + tv + ", \"" + e.name + "\", " + literal + ");");
                    return true;
                }
                case CALL -> {
                    if (e.target == null && isNumberParse(e) && !literal.equals("null")) {
                        // Integer.parseInt(request.getParameter(id)) < 1: hand the parser that number as text
                        return bindLiteral(e.args.get(0), "\"" + literal.replaceAll("[LlfFdD]$", "").replace(".0", "") + "\"");
                    }
                    if (e.target == null) return false; // static call: would need mockStatic
                    if (isMapLookup(e)) return arrangeMapLookup(e, literal);
                    if (isObjectMethod(e)) return false; // Mockito cannot stub equals/hashCode
                    String tv = ensure(e.target);
                    if (tv == null) return false;
                    if (Type.getReturnType(e.memberDesc).getSort() == Type.VOID) return false;
                    stubs.put(claimStubKey(tv, e), "when(" + tv + "." + e.name + "(" + matchers(e) + ")).thenReturn(" + literal + ");");
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        boolean isNumberParse(Expr e) {
            return e.kind == Expr.Kind.CALL && e.target == null && e.args.size() == 1 && "Ljava/lang/String;".equals(e.args.get(0).desc)
                    && (("java/lang/Integer".equals(e.owner) && (e.name.equals("parseInt") || e.name.equals("valueOf")))
                    || ("java/lang/Long".equals(e.owner) && (e.name.equals("parseLong") || e.name.equals("valueOf"))));
        }

        /** {@code this.itemMap.get(key)} / {@code remove(key)} / {@code containsKey(key)} on a map the subject owns. */
        boolean isMapLookup(Expr e) {
            return e.kind == Expr.Kind.CALL && e.target != null && e.args.size() == 1
                    && Set.of("get", "remove", "containsKey", "getOrDefault").contains(e.name)
                    && e.target.kind == Expr.Kind.FIELD && e.target.target != null && e.target.target.kind == Expr.Kind.THIS && !isStatic
                    && !deps.containsKey(e.target.name) && e.target.desc != null && isMapType(Type.getType(e.target.desc));
        }

        boolean isMapType(Type t) {
            return t.getSort() == Type.OBJECT && Set.of("java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap",
                    "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentMap").contains(t.getInternalName());
        }

        final Map<String, String> mapVars = new HashMap<>();

        /** A real map put into the subject's field (once per test), so entries can be added for the path's lookups. */
        String mapVar(Expr field) {
            if (mapVars.containsKey(field.name)) return mapVars.get(field.name);
            Type t = Type.getType(field.desc);
            String impl = t.getInternalName().startsWith("java/util/concurrent/") ? "java.util.concurrent.ConcurrentHashMap"
                    : t.getInternalName().equals("java/util/Map") ? "java.util.HashMap" : t.getClassName();
            imports.add(impl);
            List<Type> kv = declaredTypeArguments(field);
            String decl = imports.ref(t) + (kv.size() == 2 ? "<" + imports.ref(kv.get(0)) + ", " + imports.ref(kv.get(1)) + ">" : "<Object, Object>");
            String name = newVarNamed(Type.getObjectType("java/util/" + Character.toUpperCase(field.name.charAt(0)) + field.name.substring(1)));
            arrange.add(decl + " " + name + " = new " + impl.substring(impl.lastIndexOf('.') + 1) + "<>();");
            needsSetField = true;
            arrange.add("setField(subject, \"" + field.name + "\", " + name + ");");
            mapVars.put(field.name, name);
            return name;
        }

        /** Puts (or leaves out) the entry a lookup needs: {@code cartItem == null} → none, non-null → a mock value. */
        boolean arrangeMapLookup(Expr e, String literal) {
            String map = mapVar(e.target);
            boolean absent = e.name.equals("containsKey") ? literal.equals("false") : literal.equals("null");
            if (absent) return true;
            String key = renderExpected(e.args.get(0), 0);
            if (key == null) return false;
            List<Type> kv = declaredTypeArguments(e.target);
            Type valueType = kv.size() == 2 ? kv.get(1) : Type.getType(Object.class);
            String value;
            if (!e.name.equals("containsKey") && !literal.startsWith("mock(")) value = literal;
            else if (isMockable(valueType)) value = newVar(valueType);
            else value = nonNullSample(valueType, null);
            if (value == null) return false;
            arrange.add(map + ".put(" + key + ", " + value + ");");
            if (!e.name.equals("containsKey") && value.matches("[a-z][A-Za-z0-9_$]*")) {
                ensured.put(canon(e), value);
                if (e.expansion() == null) ensured.put(e.text, value);
            }
            return true;
        }

        /** Type arguments of a subject field's generic declaration ({@code Map<String, CartItem>} → [String, CartItem]). */
        List<Type> declaredTypeArguments(Expr field) {
            String sig = null;
            for (String c = cn.name; c != null && sig == null; ) {
                ClassNode node = c.equals(cn.name) ? cn : repo.node(c);
                if (node == null) break;
                for (FieldNode fn : node.fields) if (fn.name.equals(field.name)) sig = fn.signature;
                c = node.superName;
            }
            List<Type> out = new ArrayList<>();
            if (sig == null || sig.indexOf('<') < 0) return out;
            String body = sig.substring(sig.indexOf('<') + 1);
            int depth = 0, start = 0;
            for (int i = 0; i < body.length() && depth >= 0; i++) {
                char ch = body.charAt(i);
                if (ch == '<') depth++;
                else if (ch == '>') { if (depth == 0) break; depth--; }
                else if (ch == ';' && depth == 0) {
                    String one = body.substring(start, i + 1);
                    if (one.startsWith("+") || one.startsWith("-")) one = one.substring(1);
                    int lt = one.indexOf('<');
                    if (lt >= 0) one = one.substring(0, lt) + ";";
                    if (one.startsWith("L")) out.add(Type.getType(one));
                    else return List.of();
                    start = i + 1;
                }
            }
            return out;
        }

        String stubKey(String tv, Expr call) {
            StringBuilder k = new StringBuilder(tv + "." + call.name + call.memberDesc);
            for (Expr a : call.args) if ((a.kind == Expr.Kind.CONST && a.value != null) || isEnumConst(a) || exactParam(call, a)) k.append('|').append(a.text);
            return k.toString();
        }

        /** Calls stubbed with their exact parameter arguments, because another call shares their any() stub. */
        final Set<String> preciseCalls = new HashSet<>();
        /** Which call each stub key answers. */
        final Map<String, String> stubCalls = new HashMap<>();

        boolean exactParam(Expr call, Expr a) {
            return preciseCalls.contains(call.text) && a.kind == Expr.Kind.PARAM && paramVars.containsKey(a.index);
        }

        /**
         * The stub key for {@code call}; when another call already owns that key ({@code find(origin)} and
         * {@code find(destination)} both being {@code find(any())}), this one matches its parameter arguments exactly.
         */
        String claimStubKey(String tv, Expr call) {
            String key = stubKey(tv, call);
            String owner = stubCalls.get(key);
            if (owner != null && !owner.equals(call.text) && stubs.containsKey(key)
                    && call.args.stream().anyMatch(a -> a.kind == Expr.Kind.PARAM && paramVars.containsKey(a.index))) {
                preciseCalls.add(call.text);
                key = stubKey(tv, call);
            }
            stubCalls.putIfAbsent(key, call.text);
            return key;
        }

        String matchers(Expr call) {
            Type[] ats = Type.getArgumentTypes(call.memberDesc);
            boolean overloaded = isOverloaded(call.owner, call.name, ats.length);
            List<String> ms = new ArrayList<>();
            for (int i = 0; i < ats.length; i++) {
                Expr a = i < call.args.size() ? call.args.get(i) : null;
                // session.getAttribute("cart") and getAttribute("accountBean") are different stubs
                if (a != null && ((a.kind == Expr.Kind.CONST && a.value != null) || isEnumConst(a))) {
                    String lit = literalOf(a);
                    if (ats[i].getSort() == Type.LONG && !lit.endsWith("L")) lit += "L";
                    ms.add("eq(" + lit + ")");
                } else if (a != null && exactParam(call, a)) {
                    ms.add("eq(" + paramVars.get(a.index) + ")");
                } else ms.add(anyMatcher(ats[i], overloaded));
            }
            return String.join(", ", ms);
        }

        // ---- rendering ----
        String render(String testName, String subjectType) {
            collect();
            solve();
            ensureImplicit();
            boolean unresolved = !todos.isEmpty(); // precondition TODOs only; assertion TODOs are inline comments
            MethodNode mn = r.method();
            Type[] ats = Type.getArgumentTypes(mn.desc);
            Type ret = Type.getReturnType(mn.desc);

            // expected outcome (may add stubs) must be computed before arrange lines are printed
            List<String> asserts = new ArrayList<>();
            String receiver = isStatic ? subjectType : "subject";
            if (!isStatic && (cn.access & Opcodes.ACC_ENUM) != 0) {
                if (enumReceiver == null) {
                    List<String> constants = ClassInspector.enumConstants(cn);
                    enumReceiver = constants.isEmpty() ? "null /* TODO */" : subjectType + "." + constants.get(0);
                }
                receiver = enumReceiver;
            }
            String invoke = receiver + "." + mn.name + "("
                    + r.params().stream().map(pp -> paramVars.get(pp.index)).collect(Collectors.joining(", ")) + ")";
            String act;
            Path.Outcome o = p.outcome();
            if (o.kind() == Path.OutcomeKind.THROW) {
                act = "assertThrows(" + imports.ref(o.exceptionType()) + ".class, () -> " + invoke + ");";
            } else if (ret.getSort() == Type.VOID || o.kind() != Path.OutcomeKind.RETURN) {
                act = invoke + ";";
            } else {
                act = "var result = " + invoke + ";";
                for (String a : expectReturn(o.value(), ret)) {
                    // arrays compare by identity in assertEquals
                    asserts.add(ret.getSort() == Type.ARRAY && a.startsWith("assertEquals(") && a.endsWith(", result);")
                            ? "assertArrayEquals(" + a.substring("assertEquals(".length()) : a);
                }
            }
            asserts.addAll(expectEffects());

            StringBuilder sb = new StringBuilder();
            String display = p.conds().stream().map(Cond::describe).collect(Collectors.joining(" && "));
            if (display.isEmpty()) display = "always";
            display = mn.name + ": " + display + " → " + o.describe();
            if (display.length() > 160) display = display.substring(0, 157) + "...";
            sb.append("    @Test\n    @DisplayName(\"").append(display.replace("\\", "\\\\").replace("\"", "\\\"")).append("\")\n");
            if (unresolved) {
                // a test that cannot reach its path would pass or fail for the wrong reason
                imports.add("org.junit.jupiter.api.Disabled");
                sb.append("    @Disabled(\"truth-seeker: arrange the TODO preconditions, then remove @Disabled\")\n");
            }
            // methods under test may declare checked exceptions (ParseException, SQLException, legacy RemoteException…)
            sb.append("    void ").append(testName).append("() throws Exception {\n");
            if (o.kind() == Path.OutcomeKind.TRUNCATED) sb.append("        // NOTE: analysis of this path was cut short (").append(o.note()).append(")\n");
            sb.append("        // Arrange\n");
            // mock declarations first: parameters may be built from them (a list holding a mock element)
            java.util.function.Predicate<String> isMockDecl = a -> a.matches("[\\w.<>\\[\\]]+ \\w+ = mock\\(.*\\);");
            for (String a : arrange) if (isMockDecl.test(a)) sb.append("        ").append(a).append("\n");
            for (int i = 0; i < r.params().size(); i++) {
                Expr param = r.params().get(i);
                String lit = paramValues.get(param.index);
                if (lit == null) lit = defaultFor(ats[i]);
                Type declared = paramTypes.getOrDefault(param.index, ats[i]);
                sb.append("        ").append(imports.ref(declared)).append(" ").append(paramVars.get(param.index)).append(" = ").append(lit).append(";\n");
            }
            for (String a : arrange) if (!isMockDecl.test(a)) sb.append("        ").append(a).append("\n");
            for (String s : stubs.values()) sb.append("        ").append(s).append("\n");
            for (String t : todos) sb.append("        // TODO: ").append(t).append("\n");
            String self = selfCall();
            if (self != null && !isStatic) {
                sb.append("        // NOTE: depends on the real behaviour of `").append(self).append("` (calls on the subject itself are not stubbed)\n");
            }
            sb.append("\n        // Act").append(o.kind() == Path.OutcomeKind.THROW ? " + Assert" : "").append("\n");
            sb.append("        ").append(act).append("\n");
            for (String a : afterAct) sb.append("        ").append(a).append("\n");
            if (!asserts.isEmpty()) {
                sb.append("\n        // Assert\n");
                for (String a : asserts) sb.append("        ").append(a).append("\n");
            }
            sb.append("    }\n");
            return sb.toString();
        }

        String defaultFor(Type t) {
            return switch (t.getSort()) {
                case Type.BOOLEAN -> "false";
                case Type.CHAR -> "'a'";
                case Type.BYTE, Type.SHORT, Type.INT -> "0";
                case Type.LONG -> "0L";
                case Type.FLOAT -> "0.0f";
                case Type.DOUBLE -> "0.0";
                default -> {
                    String s = nonNullSample(t, null);
                    yield s == null ? "null" : s;
                }
            };
        }

        List<String> expectReturn(Expr v, Type ret) {
            List<String> out = new ArrayList<>();
            if (v.kind == Expr.Kind.NULL) {
                out.add("assertNull(result);");
            } else if (v.kind == Expr.Kind.CONST) {
                String lit = literalOf(v);
                if (ret.getSort() == Type.BOOLEAN) out.add(lit.equals("true") ? "assertTrue(result);" : "assertFalse(result);");
                else if (ret.getSort() == Type.DOUBLE || ret.getSort() == Type.FLOAT) out.add("assertEquals(" + lit + ", result, 1e-9);");
                else out.add("assertEquals(" + lit + ", result);");
            } else if (isEnumConst(v)) {
                out.add("assertEquals(" + literalOf(v) + ", result);");
            } else if (v.kind == Expr.Kind.PARAM) {
                out.add((ret.getSort() == Type.OBJECT ? "assertSame(" : "assertEquals(") + paramVars.get(v.index) + ", result);");
            } else if (ensured.containsKey(v.text)) {
                out.add("assertSame(" + ensured.get(v.text) + ", result);");
            } else if (boundLiteral.containsKey(v.text) && boundLiteral.get(v.text).startsWith("mock(")) {
                out.add("assertNotNull(result); // a fresh mock() is never equal to the returned instance");
            } else if (boundLiteral.containsKey(v.text)) {
                String lit = boundLiteral.get(v.text);
                if (ret.getSort() == Type.BOOLEAN) out.add(lit.equals("true") ? "assertTrue(result);" : "assertFalse(result);");
                else out.add("assertEquals(" + lit + ", result);");
            } else if (v.kind == Expr.Kind.FIELD && v.target != null && v.target.kind == Expr.Kind.THIS && !isStatic
                    && deps.containsKey(v.name) && v.desc != null && Type.getType(v.desc).getSort() == Type.OBJECT) {
                out.add("assertSame(" + v.name + ", result);"); // the field holds the injected @Mock
            } else if (v.kind == Expr.Kind.FIELD && v.target != null && v.target.kind == Expr.Kind.THIS && !isStatic
                    && !deps.containsKey(v.name) && (cn.access & Opcodes.ACC_ENUM) == 0 && v.desc != null) {
                // getter-like: put a known value into the field and expect it back
                Type t = Type.getType(v.desc);
                boolean mockable = t.getSort() == Type.OBJECT && !ClassInspector.isValueType(v.desc) && !isEnum(t.getInternalName());
                String lit = mockable ? newVar(t) : nonNullSample(t, v);
                if (lit == null) {
                    out.add("// TODO: assert result equals `" + v.text + "`");
                } else {
                    needsSetField = true;
                    arrange.add("setField(subject, \"" + v.name + "\", " + lit + ");");
                    if (mockable) out.add("assertSame(" + lit + ", result);");
                    else if (ret.getSort() == Type.BOOLEAN) out.add(lit.equals("true") ? "assertTrue(result);" : "assertFalse(result);");
                    else if (ret.getSort() == Type.DOUBLE || ret.getSort() == Type.FLOAT) out.add("assertEquals(" + lit + ", result, 1e-9);");
                    else out.add("assertEquals(" + lit + ", result);");
                }
            } else if (v.kind == Expr.Kind.BINARY && evaluate(v) != null && ret.getSort() >= Type.CHAR && ret.getSort() <= Type.LONG) {
                Number n = evaluate(v);
                out.add("assertEquals(" + (ret.getSort() == Type.LONG ? n.longValue() + "L" : String.valueOf(n.longValue())) + ", result);");
            } else if (v.kind == Expr.Kind.CALL && v.target != null && pipeline(v) != null) {
                out.addAll(expectPipeline(v, ret));
            } else if (v.kind == Expr.Kind.CALL && v.target != null) {
                Type t = Type.getType(v.desc);
                if (t.getSort() == Type.OBJECT && !ClassInspector.isValueType(v.desc)) {
                    String var = ensure(v);
                    if (var != null) {
                        out.add("assertSame(" + var + ", result);");
                        return out;
                    }
                } else {
                    String lit = nonNullSample(t, v);
                    if (lit != null && lit.startsWith("new ") && !lit.contains("<>") && ret.getSort() == Type.OBJECT) {
                        // an instance compares by identity (new Object(), an exception): name it and expect that very one
                        String var = "object";
                        for (int k = 1; usedNames.contains(var); k++) var = "object" + k;
                        if (bindLiteral(v, var)) {
                            usedNames.add(var);
                            arrange.add(0, imports.ref(t) + " " + var + " = " + lit + ";");
                            out.add("assertSame(" + var + ", result);");
                            return out;
                        }
                    }
                    if (lit != null && bindLiteral(v, lit)) {
                        out.add(ret.getSort() == Type.BOOLEAN ? (lit.equals("true") ? "assertTrue(result);" : "assertFalse(result);") : "assertEquals(" + lit + ", result);");
                        return out;
                    }
                }
                String rendered = renderExpected(v, 0);
                if (rendered != null) out.add(assertEqualsTo(rendered, ret));
                else out.add("// TODO: assert result equals `" + v.text + "`");
            } else {
                String rendered = renderExpected(v, 0);
                List<String> mav = rendered == null ? expectModelAndView(v) : List.of();
                List<String> built = rendered == null && mav.isEmpty() ? expectBuiltFields(v) : List.of();
                if (rendered != null) out.add(assertEqualsTo(rendered, ret));
                else if (!mav.isEmpty()) out.addAll(mav);
                else if (!built.isEmpty()) out.addAll(built);
                else if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) out.add("assertNotNull(result); // TODO: expected `" + v.text + "`");
                else out.add("// TODO: assert result equals `" + v.text + "`");
            }
            return out;
        }

        /** Element variables already stubbed into a collaborator's returned collection, by stub key. */
        final Map<String, String> filledStubs = new HashMap<>();

        /**
         * A list or set the method created empty and filled with {@code add}/{@code addAll} ({@code products.addAll(mapper.search(..))}):
         * the same elements in the same order. Collections a collaborator returns get a stub with one mock element. Null when
         * anything else touches the collection or an element cannot be named.
         */
        String filledCollection(Expr coll, int depth) {
            if (!coll.args.isEmpty() || coll.owner == null || !coll.owner.startsWith("java/util/")) return null;
            boolean set = coll.owner.endsWith("Set");
            if (!set && !coll.owner.endsWith("List")) return null;
            List<String> elems = new ArrayList<>();
            for (Effect ef : p.effects()) {
                boolean onIt = ef.target() != null && ef.target().text.equals(coll.text);
                boolean mentions = (ef.target() != null && ef.target().mentions(x -> x.text.equals(coll.text)))
                        || ef.args().stream().anyMatch(a -> a.mentions(x -> x.text.equals(coll.text)))
                        || (ef.value() != null && ef.value().mentions(x -> x.text.equals(coll.text)));
                if (!mentions) continue;
                if (!onIt || ef.kind() != Effect.Kind.CALL || ef.args().size() != 1) return null;
                Expr a = ef.args().get(0);
                if (ef.name().equals("add")) {
                    String el = elementValue(a, depth);
                    if (el == null) return null;
                    elems.add(el);
                } else if (ef.name().equals("addAll")) {
                    List<String> more = collaboratorElements(a);
                    if (more == null) return null;
                    elems.addAll(more);
                } else {
                    return null;
                }
            }
            if (set && elems.stream().distinct().count() != elems.size()) return null;
            imports.add("java.util.List");
            if (!set) return "List.of(" + String.join(", ", elems) + ")";
            imports.add("java.util.Set");
            return "Set.of(" + String.join(", ", elems) + ")";
        }

        /** How the test names a value the method added to a collection, or null. */
        String elementValue(Expr a, int depth) {
            if (a.kind == Expr.Kind.NULL) return null;
            if (a.kind == Expr.Kind.PARAM && paramVars.containsKey(a.index)) {
                String val = paramValues.get(a.index);
                return "null".equals(val) ? null : paramVars.get(a.index);
            }
            if (a.expansion() == null && ensured.containsKey(a.text)) return ensured.get(a.text);
            if (ensured.containsKey(canon(a))) return ensured.get(canon(a));
            String r = renderExpected(a, depth + 1);
            if (r != null) return r.equals("null") ? null : r;
            if (a.kind == Expr.Kind.CALL && a.target != null && a.desc != null && isMockable(Type.getType(a.desc))) return ensure(a);
            return null;
        }

        /** Elements of a collection a collaborator returns: stubbed to hold one mock element (or what is already stubbed). */
        List<String> collaboratorElements(Expr c) {
            String bound = boundLiteral.get(c.text);
            if (bound != null) return bound.matches("new \\w+<>\\(\\)|List\\.of\\(\\)|Set\\.of\\(\\)|Collections\\.empty\\w*\\(\\)") ? List.of() : null;
            if (c.kind != Expr.Kind.CALL || c.target == null || c.desc == null || ensured.containsKey(c.text)) return null;
            Type ct = Type.getType(c.desc);
            Type et = declaredElementType(c);
            if (et == null || !isMockable(et) || ct.getSort() != Type.OBJECT || ct.getInternalName().equals("java/util/Iterator")
                    || iterableLiteral(ct, List.of()) == null) return null;
            String tv = ensure(c.target);
            if (tv == null) return null;
            String key = stubKey(tv, c);
            if (filledStubs.containsKey(key)) return List.of(filledStubs.get(key));
            if (stubs.containsKey(key)) return null; // stubbed for a condition already, with other contents
            String v = newVar(et);
            if (!bindLiteral(c, iterableLiteral(ct, List.of(v)))) return null;
            filledStubs.put(key, v);
            return List.of(v);
        }

        /**
         * A Spring {@code ModelAndView} the method built: its view name and the model entries added to it, from the
         * constructor arguments, {@code setViewName} and {@code addObject} calls. Empty when it cannot be followed.
         */
        List<String> expectModelAndView(Expr v) {
            if (v.kind != Expr.Kind.NEW || !"org/springframework/web/servlet/ModelAndView".equals(v.owner)) return List.of();
            Expr view = null;
            LinkedHashMap<String, Expr> named = new LinkedHashMap<>();
            List<Expr> unnamed = new ArrayList<>();
            List<Expr> ctor = v.args;
            if (!ctor.isEmpty() && "Ljava/lang/String;".equals(ctor.get(0).desc)) view = ctor.get(0);
            if (ctor.size() == 3 && ctor.get(1).kind == Expr.Kind.CONST) named.put(literalOf(ctor.get(1)), ctor.get(2));
            for (Effect ef : p.effects()) {
                boolean mentions = (ef.target() != null && ef.target().mentions(x -> x.text.equals(v.text)))
                        || ef.args().stream().anyMatch(a -> a.mentions(x -> x.text.equals(v.text)));
                if (!mentions) continue;
                if (ef.kind() != Effect.Kind.CALL || ef.target() == null || !ef.target().text.equals(v.text)) return List.of();
                switch (ef.name()) {
                    case "setViewName" -> view = ef.args().get(0);
                    case "addObject" -> {
                        if (ef.args().size() == 2 && ef.args().get(0).kind == Expr.Kind.CONST) named.put(literalOf(ef.args().get(0)), ef.args().get(1));
                        else if (ef.args().size() == 1) unnamed.add(ef.args().get(0));
                        else return List.of();
                    }
                    default -> { return List.of(); }
                }
            }
            List<String> out = new ArrayList<>();
            out.add("assertNotNull(result);");
            if (view != null) {
                String r = view.kind == Expr.Kind.CONST ? literalOf(view) : renderExpected(view, 0);
                if (r != null) out.add("assertEquals(" + r + ", result.getViewName());");
            }
            for (var en : named.entrySet()) {
                if (en.getValue().kind == Expr.Kind.NULL) {
                    out.add("assertNull(result.getModel().get(" + en.getKey() + "));");
                    continue;
                }
                String el = elementValue(en.getValue(), 0);
                if (el != null) out.add("assertEquals(" + el + ", result.getModel().get(" + en.getKey() + "));");
            }
            for (Expr u : unnamed) { // the attribute name comes from the value's class, which a mock does not keep
                String el = elementValue(u, 0);
                if (el != null) out.add("assertTrue(result.getModel().containsValue(" + el + "));");
            }
            return out.size() > 1 ? out : List.of();
        }

        /** Set while asserting the fields of a returned object, so nested objects are not expanded with the wrong fields. */
        boolean inBuiltFields;

        /**
         * The method returns an object it built itself ({@code return new Delivery(lastEvent, ..)}): an instance is never
         * equal to one the test could build, so assert what its getters return instead, from the field values the
         * constructor set (or the field's default when no constructor set it). Empty when nothing can be asserted.
         */
        List<String> expectBuiltFields(Expr v) {
            if (inBuiltFields || v.kind != Expr.Kind.NEW || v.owner == null || p.returnedFields() == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            inBuiltFields = true;
            try {
                for (MethodNode g : simpleGetters(v.owner)) {
                    if (out.size() >= MAX_FIELD_ASSERTS) break;
                    String field = getterField(g);
                    Type t = Type.getReturnType(g.desc);
                    // declared wider than what it builds (Object processItem(..) returning new MyOutputRecord(..)): cast
                    boolean wider = !Type.getReturnType(r.method().desc).getDescriptor().equals("L" + v.owner + ";");
                    String actual = (wider ? "((" + imports.ref(Type.getObjectType(v.owner)) + ") result)" : "result") + "." + g.name + "()";
                    Expr fv = p.returnedFields().get(field);
                    if (fv == null && lazyGetter(g)) continue; // would create the value on first call
                    List<String> lines;
                    if (fv == null) {
                        lines = List.of(switch (t.getSort()) {
                            case Type.OBJECT, Type.ARRAY -> "assertNull(" + actual + ");";
                            case Type.BOOLEAN -> "assertFalse(" + actual + ");";
                            case Type.FLOAT, Type.DOUBLE -> "assertEquals(0.0, " + actual + ", 1e-9);";
                            case Type.LONG -> "assertEquals(0L, " + actual + ");";
                            case Type.CHAR -> "assertEquals('\0', " + actual + ");";
                            default -> "assertEquals(0, " + actual + ");";
                        });
                    } else {
                        if (fv.desc != null && !fv.desc.equals(t.getDescriptor()) && t.getSort() == Type.OBJECT) fv = fv.withDesc(t.getDescriptor());
                        lines = new ArrayList<>();
                        for (String line : expectReturn(fv, t)) {
                            lines.add(line.replace("(result)", "(" + actual + ")").replace(", result)", ", " + actual + ")")
                                    .replace(", result, 1e-9)", ", " + actual + ", 1e-9)"));
                        }
                    }
                    if (lines.stream().anyMatch(l -> l.contains("TODO") || l.contains("fresh mock()"))) continue;
                    out.addAll(lines);
                }
            } finally {
                inBuiltFields = false;
            }
            if (!out.isEmpty()) out.add(0, "assertNotNull(result);");
            return out;
        }

        /** Public no-arg methods of a class (and its application superclasses) that just return one of its fields. */
        List<MethodNode> simpleGetters(String owner) {
            List<MethodNode> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String pkg = Names.packageOf(cn.name);
            for (String c = owner; c != null && !c.equals("java/lang/Object"); ) {
                ClassNode node = repo.node(c);
                if (node == null) break;
                boolean visibleClass = (node.access & Opcodes.ACC_PUBLIC) != 0 || Names.packageOf(node.name).equals(pkg);
                for (MethodNode m : node.methods) {
                    if (!visibleClass || !m.desc.startsWith("()") || m.name.startsWith("<") || !seen.add(m.name)) continue;
                    if (m.name.equals("hashCode") || m.name.equals("toString")) continue;
                    if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
                    boolean visible = (m.access & Opcodes.ACC_PUBLIC) != 0
                            || (m.access & Opcodes.ACC_PRIVATE) == 0 && Names.packageOf(node.name).equals(pkg);
                    if (visible && getterField(m) != null) out.add(m);
                }
                c = node.superName;
            }
            return out;
        }

        /**
         * The field a method returns when its body is just {@code return this.field;}, or a lazy initialisation of it
         * ({@code if (vets == null) vets = new ArrayList<>(); return vets;}), else null.
         */
        String getterField(MethodNode m) {
            List<AbstractInsnNode> code = new ArrayList<>();
            for (AbstractInsnNode in : m.instructions) if (in.getOpcode() >= 0) code.add(in);
            if (lazyGetter(m)) return ((FieldInsnNode) code.get(code.size() - 2)).name;
            if (code.size() < 3 || code.size() > 4 || code.get(0).getOpcode() != Opcodes.ALOAD
                    || ((org.objectweb.asm.tree.VarInsnNode) code.get(0)).var != 0 || code.get(1).getOpcode() != Opcodes.GETFIELD) return null;
            if (code.size() == 4 && code.get(2).getOpcode() != Opcodes.CHECKCAST) return null;
            int last = code.get(code.size() - 1).getOpcode();
            if (last < Opcodes.IRETURN || last > Opcodes.ARETURN) return null;
            return ((FieldInsnNode) code.get(1)).name;
        }

        /** {@code if (f == null) f = new X(); return f;}: only the field, NEW of a JDK collection and a null check. */
        boolean lazyGetter(MethodNode m) {
            List<AbstractInsnNode> code = new ArrayList<>();
            for (AbstractInsnNode in : m.instructions) if (in.getOpcode() >= 0) code.add(in);
            int n = code.size();
            if (n < 8 || n > 12 || code.get(n - 1).getOpcode() != Opcodes.ARETURN || code.get(n - 2).getOpcode() != Opcodes.GETFIELD) return false;
            String field = ((FieldInsnNode) code.get(n - 2)).name;
            boolean nullCheck = false, store = false;
            for (int i = 0; i < n - 2; i++) {
                AbstractInsnNode in = code.get(i);
                switch (in.getOpcode()) {
                    case Opcodes.ALOAD -> { if (((org.objectweb.asm.tree.VarInsnNode) in).var != 0) return false; }
                    case Opcodes.GETFIELD -> { if (!((FieldInsnNode) in).name.equals(field)) return false; }
                    case Opcodes.PUTFIELD -> { if (!((FieldInsnNode) in).name.equals(field)) return false; store = true; }
                    case Opcodes.IFNONNULL, Opcodes.IFNULL -> nullCheck = true;
                    case Opcodes.NEW -> { if (!((org.objectweb.asm.tree.TypeInsnNode) in).desc.startsWith("java/util/")) return false; }
                    case Opcodes.DUP, Opcodes.GOTO -> { }
                    case Opcodes.INVOKESPECIAL -> { if (!((org.objectweb.asm.tree.MethodInsnNode) in).name.equals("<init>")
                            || !((org.objectweb.asm.tree.MethodInsnNode) in).desc.equals("()V")) return false; }
                    default -> { return false; }
                }
            }
            return nullCheck && store;
        }

        String assertEqualsTo(String expected, Type ret) {
            if (ret.getSort() == Type.DOUBLE || ret.getSort() == Type.FLOAT) return "assertEquals(" + expected + ", result, 1e-9);";
            if (ret.getSort() == Type.ARRAY) return "assertArrayEquals(" + expected + ", result);";
            return "assertEquals(" + expected + ", result);";
        }

        private static final Set<String> STATIC_PURE = Set.of(
                "java/util/Objects.hash", "java/util/Objects.hashCode", "java/util/Objects.equals", "java/util/Objects.toString",
                "java/util/Objects.requireNonNullElse", "java/lang/String.valueOf", "java/lang/String.join",
                "java/util/Collections.unmodifiableList", "java/util/Collections.unmodifiableSet", "java/util/Collections.unmodifiableMap",
                "java/util/Collections.unmodifiableCollection", "java/util/Collections.emptyList", "java/util/Collections.emptySet",
                "java/util/Collections.emptyMap", "java/util/Collections.singletonList", "java/util/List.of", "java/util/List.copyOf",
                "java/util/Set.of", "java/util/Set.copyOf", "java/util/Map.of", "java/lang/Integer.valueOf", "java/lang/Long.valueOf",
                "java/lang/Boolean.valueOf", "java/lang/Integer.toString", "java/lang/Long.toString", "java/lang/Math.max",
                "java/lang/Math.min", "java/lang/Math.abs");

        /** A call on a stream (or size/emptiness query) of {@code e}: reads the collection, never changes it. */
        boolean onlyReads(Effect ef, Expr e) {
            if (ef.kind() != Effect.Kind.CALL || ef.target() == null) return false;
            if (ef.args().stream().anyMatch(a -> a.mentions(x -> x.text.equals(e.text)))) return false;
            Expr t = ef.target();
            while (t.kind == Expr.Kind.CALL && t.target != null && !t.target.text.equals(e.text)) {
                if (t.args.stream().anyMatch(a -> a.mentions(x -> x.text.equals(e.text)))) return false;
                t = t.target;
            }
            return t.kind == Expr.Kind.CALL && t.target != null && t.target.text.equals(e.text)
                    && Set.of("stream", "parallelStream", "size", "isEmpty", "contains").contains(t.name);
        }

        /** {@code this.f = e} where nothing else on the path calls methods on or passes along {@code this.f}. */
        boolean storedUntouched(Effect ef, Expr e) {
            if (ef.kind() != Effect.Kind.FIELD_WRITE || ef.value() == null || !ef.value().text.equals(e.text)
                    || ef.target() == null || ef.target().kind != Expr.Kind.THIS) return false;
            String field = "this." + ef.name();
            return p.effects().stream().filter(o -> o != ef).noneMatch(o -> (o.target() != null && o.target().mentions(x -> x.text.equals(field)))
                    || o.args().stream().anyMatch(a -> a.mentions(x -> x.text.equals(field))));
        }

        /** What a stream pipeline or iterator over a collection the test controls yields. */
        record Pipeline(String expected, boolean iterator) {}

        /** Pipelines already evaluated, by expression text (evaluation may arrange the source field once). */
        final Map<String, Pipeline> pipelines = new HashMap<>();

        List<String> expectPipeline(Expr v, Type ret) {
            Pipeline pl = pipeline(v);
            if (pl == null) return List.of();
            if (!pl.iterator() && ret.getSort() == Type.BOOLEAN && Set.of("true", "false").contains(pl.expected())) {
                return List.of(pl.expected().equals("true") ? "assertTrue(result);" : "assertFalse(result);");
            }
            if (!pl.iterator()) return List.of(assertEqualsTo(pl.expected(), ret));
            imports.add("java.util.ArrayList");
            imports.add("java.util.List");
            String var = "remaining";
            for (int i = 1; usedNames.contains(var); i++) var = "remaining" + i;
            usedNames.add(var);
            return List.of("List<Object> " + var + " = new ArrayList<>();", "result.forEachRemaining(" + var + "::add);",
                    "assertEquals(" + pl.expected() + ", " + var + ");");
        }

        /**
         * {@code this.items.iterator()}, {@code this.specialties.stream().sorted(..).collect(Collectors.toList())},
         * {@code this.lines.stream().map(..).reduce(BigDecimal.ZERO, BigDecimal::add)}: evaluated on the elements the
         * test puts in the collection. Steps that call a lambda per element are only evaluated on an empty collection,
         * sorting and distinct on at most one element. An unarranged source field gets one mock element when every step
         * allows it, else none. Null when a step is not understood.
         */
        Pipeline pipeline(Expr e) {
            if (pipelines.containsKey(e.text)) return pipelines.get(e.text);
            List<Expr> steps = new ArrayList<>();
            Expr src = e;
            while (src.kind == Expr.Kind.CALL && src.target != null && steps.size() < 8) {
                steps.add(0, src);
                src = src.target;
            }
            if (steps.isEmpty() || !steps.get(0).owner.startsWith("java/util/")) return null;
            if (!Set.of("stream", "iterator").contains(steps.get(0).name) || !steps.get(0).args.isEmpty()) return null;
            Pipeline result = null;
            if (src.kind == Expr.Kind.NEW && src.args.isEmpty() && src.owner != null && src.owner.startsWith("java/util/")
                    && renderExpected(src, 1) != null) { // created empty, untouched
                result = evaluate(steps, List.of(), null, false);
            } else if (src.kind == Expr.Kind.FIELD && src.target != null && src.target.kind == Expr.Kind.THIS && !isStatic && src.desc != null) {
                result = pipelineOverField(src, steps);
            }
            pipelines.put(e.text, result);
            return result;
        }

        Pipeline pipelineOverField(Expr field, List<Expr> steps) {
            if (deps.containsKey(field.name) || ensured.containsKey(field.text)) return null;
            Type ft = Type.getType(field.desc);
            String bound = boundLiteral.get(field.text);
            if (bound != null) {
                List<String> elems = elementsOf(bound);
                if (elems == null) return null;
                boolean onlyNullChecked = elems.isEmpty() && p.conds().stream()
                        .noneMatch(c -> c.describe().contains(field.text + ".") || c.describe().contains(field.text + " ="));
                Pipeline one = onlyNullChecked ? withOneElement(field, ft, steps, bound) : null;
                return one != null ? one : evaluate(steps, elems, declaredElementType(field), false);
            }
            Pipeline one = withOneElement(field, ft, steps, null);
            if (one != null) return one;
            String empty = iterableLiteral(ft, List.of());
            Pipeline none = evaluate(steps, List.of(), null, false);
            if (empty == null || none == null || empty.contains("Iterator")) return null;
            needsSetField = true;
            arrange.add("setField(subject, \"" + field.name + "\", " + empty + ");");
            boundLiteral.put(field.text, empty);
            return none;
        }

        /** Arranges the field with one mock element if the steps can be evaluated on it (replacing an empty non-null literal). */
        Pipeline withOneElement(Expr field, Type ft, List<Expr> steps, String replacing) {
            Type et = declaredElementType(field);
            if (et == null || !isMockable(et) || evaluate(steps, List.of("x"), et, true) == null) return null;
            if (iterableLiteral(ft, List.of("x")) == null || ft.getInternalName().equals("java/util/Iterator")) return null;
            String setLine = replacing == null ? null : "setField(subject, \"" + field.name + "\", " + replacing + ");";
            if (replacing != null && !arrange.contains(setLine)) return null;
            String v = newVar(et);
            String lit = iterableLiteral(ft, List.of(v));
            needsSetField = true;
            if (setLine != null) arrange.remove(setLine);
            arrange.add("setField(subject, \"" + field.name + "\", " + lit + ");");
            boundLiteral.put(field.text, lit);
            return evaluate(steps, List.of(v), et, false);
        }

        /** Elements of a collection literal this generator wrote ({@code new ArrayList<>(List.of(leg))}), or null. */
        List<String> elementsOf(String literal) {
            var m = Pattern.compile("new \\w+<>\\((?:List\\.of\\(([^()]*)\\))?\\)").matcher(literal);
            if (!m.matches()) return null;
            if (m.group(1) == null || m.group(1).isBlank()) return List.of();
            List<String> out = new ArrayList<>();
            for (String part : m.group(1).split(",")) {
                if (!part.trim().matches("[a-z][A-Za-z0-9_$]*")) return null;
                out.add(part.trim());
            }
            return out;
        }

        /** One element flowing through a pipeline: what the code sees, and how the test names it. */
        record Elem(Expr expr, String code) {}

        /**
         * Runs the steps over elements known by name. Steps that run a lambda per element apply its body to the
         * element: a predicate is arranged to hold (stubs on the mock element), a mapping yields the body's value.
         * In a dry run nothing is arranged; it only says whether the steps could be decided. Null when a step cannot.
         */
        Pipeline evaluate(List<Expr> steps, List<String> names, Type elemType, boolean dry) {
            boolean stream = false;
            List<Elem> cur = new ArrayList<>();
            for (String nm : names) {
                Expr el = Expr.unknown("\u0000elem:" + nm, elemType == null ? null : elemType.getDescriptor());
                if (!dry) ensured.put(el.text, nm);
                cur.add(new Elem(el, nm));
            }
            for (int i = 0; i < steps.size(); i++) {
                Expr st = steps.get(i);
                boolean last = i == steps.size() - 1;
                String n = st.name;
                if (i == 0) {
                    if (n.equals("iterator")) return last ? new Pipeline(listOf(codes(cur)), true) : null;
                    stream = true;
                    continue;
                }
                if (!stream || st.owner == null || !st.owner.equals("java/util/stream/Stream")) return null;
                switch (n) {
                    case "sorted", "distinct" -> { if (cur.size() > 1) return null; }
                    case "filter" -> {
                        if (cur.size() > 1 || st.args.size() != 1) return null;
                        if (!cur.isEmpty()) {
                            Boolean keep = holds(st.args.get(0), cur.get(0), dry);
                            if (keep == null) return null;
                            if (!keep) cur = new ArrayList<>();
                        }
                    }
                    case "map" -> {
                        if (cur.size() > 1 || st.args.size() != 1) return null;
                        if (!cur.isEmpty()) {
                            Elem mapped = mapped(st.args.get(0), cur.get(0), dry);
                            if (mapped == null) return null;
                            cur = new ArrayList<>(List.of(mapped));
                        }
                    }
                    case "peek", "flatMap", "limit", "skip" -> { if (!cur.isEmpty()) return null; }
                    case "toList" -> { return last ? new Pipeline(listOf(codes(cur)), false) : null; }
                    case "collect" -> {
                        if (!last || st.args.size() != 1) return null;
                        Expr c = st.args.get(0);
                        if (c.kind != Expr.Kind.CALL || !"java/util/stream/Collectors".equals(c.owner) || !c.args.isEmpty()) return null;
                        return switch (c.name) {
                            case "toList", "toUnmodifiableList" -> new Pipeline(listOf(codes(cur)), false);
                            case "toSet", "toUnmodifiableSet" -> {
                                imports.add("java.util.Set");
                                yield new Pipeline("Set.of(" + String.join(", ", codes(cur)) + ")", false);
                            }
                            default -> null;
                        };
                    }
                    case "count" -> { return last ? new Pipeline(cur.size() + "L", false) : null; }
                    case "anyMatch", "allMatch", "noneMatch" -> {
                        if (!last || cur.size() > 1 || st.args.size() != 1) return null;
                        boolean empty = cur.isEmpty();
                        if (!empty) { // one element: the predicate decides; arrange it to hold
                            Boolean h = holds(st.args.get(0), cur.get(0), dry);
                            if (h == null) return null;
                            boolean any = h;
                            return new Pipeline(String.valueOf(n.equals("noneMatch") ? !any : any), false);
                        }
                        return new Pipeline(n.equals("anyMatch") ? "false" : "true", false);
                    }
                    case "findFirst", "findAny" -> {
                        if (!last) return null;
                        imports.add("java.util.Optional");
                        return new Pipeline(cur.isEmpty() ? "Optional.empty()" : "Optional.of(" + cur.get(0).code() + ")", false);
                    }
                    case "reduce" -> {
                        if (!last || cur.size() > 1) return null;
                        if (st.args.size() == 1) { // reduce(op): the single element, or nothing
                            imports.add("java.util.Optional");
                            return new Pipeline(cur.isEmpty() ? "Optional.empty()" : "Optional.of(" + cur.get(0).code() + ")", false);
                        }
                        if (st.args.size() != 2) return null;
                        Expr identity = st.args.get(0);
                        if (cur.isEmpty()) {
                            String r = dry ? "x" : renderExpected(identity, 1);
                            return r == null ? null : new Pipeline(r, false);
                        }
                        Expr combined = applyLambda(st.args.get(1), List.of(identity, cur.get(0).expr()));
                        if (combined == null) return null;
                        if (dry) return new Pipeline("x", false);
                        String r = renderExpected(combined, 1);
                        return r == null ? null : new Pipeline(r, false);
                    }
                    default -> { return null; }
                }
            }
            return null;
        }

        static List<String> codes(List<Elem> elems) {
            return elems.stream().map(Elem::code).toList();
        }

        /**
         * Whether a predicate holds for the element. A body with decisions ({@code a.equals(x) && b.equals(y)}) is made
         * to take a path that returns true; a body that is a single call is arranged to return true.
         */
        Boolean holds(Expr predicate, Elem el, boolean dry) {
            List<Applied> paths = applyLambdaPaths(predicate, List.of(el.expr()));
            if (paths == null) return null;
            if (paths.stream().allMatch(a -> isFalse(a.value()))) return false;
            for (Applied a : paths) {
                Expr body = a.value();
                boolean returnsTrue = body.kind == Expr.Kind.CONST && body.value instanceof Number num && num.intValue() != 0;
                boolean callBody = body.kind == Expr.Kind.CALL && body.target != null;
                if (!returnsTrue && !callBody) continue;
                if (!a.conds().stream().allMatch(c -> canSatisfy(c.getKey(), c.getValue())) || (callBody && !canSatisfy(body, true))) continue;
                if (dry) return true;
                for (var c : a.conds()) if (!satisfy(c.getKey(), c.getValue())) return null;
                if (callBody && !satisfy(body, true)) return null;
                return true;
            }
            return null;
        }

        static boolean isFalse(Expr e) {
            return e.kind == Expr.Kind.CONST && e.value instanceof Number n && n.intValue() == 0;
        }

        static boolean isObjectEquals(Expr e) {
            return e.kind == Expr.Kind.CALL && e.name.equals("equals") && e.args.size() == 1 && "(Ljava/lang/Object;)Z".equals(e.memberDesc);
        }

        boolean canSatisfy(Expr decision, boolean want) {
            if (decision.kind != Expr.Kind.CALL || decision.target == null) return false;
            if (isObjectEquals(decision)) return want && decision.target.kind == Expr.Kind.CALL;
            return !isObjectMethod(decision);
        }

        /** Arranges a boolean call on a mock to give {@code want}; equals by making the receiver return the other value. */
        boolean satisfy(Expr decision, boolean want) {
            if (isObjectEquals(decision)) {
                // Mockito cannot stub equals: the receiver returns the very value it is compared with
                String other = renderExpected(decision.args.get(0), 1);
                return other != null && !other.equals("null") && bindLiteral(decision.target, other);
            }
            return bindLiteral(decision, want ? "true" : "false");
        }

        /** The element a mapping function produces, named for the test (a stubbed mock or value). */
        Elem mapped(Expr function, Elem el, boolean dry) {
            Expr body = applyLambda(function, List.of(el.expr()));
            if (body == null || body.desc == null) return null;
            if (dry) return new Elem(body, "x");
            Type t = Type.getType(body.desc);
            String code;
            if (t.getSort() == Type.OBJECT && body.kind == Expr.Kind.CALL && isMockable(t)) code = ensure(body);
            else code = renderExpected(body, 1);
            if (code == null || code.equals("null")) return null;
            return new Elem(body, code);
        }

        /**
         * The body of a lambda or method reference applied to arguments, as an expression over them:
         * {@code Leg::getLoadLocation} on {@code leg} is {@code leg.getLoadLocation()}; a {@code lambda$..} method is
         * explored and must have a single path that returns without side effects. Null otherwise.
         */
        Expr applyLambda(Expr lam, List<Expr> actuals) {
            List<Applied> paths = applyLambdaPaths(lam, actuals);
            return paths != null && paths.size() == 1 && paths.get(0).conds().isEmpty() ? paths.get(0).value() : null;
        }

        /** One path through a lambda body applied to arguments: boolean decisions taken (expression, outcome) and the value. */
        record Applied(List<Map.Entry<Expr, Boolean>> conds, Expr value) {}

        /**
         * Every path of a lambda's body applied to arguments ({@code leg -> leg.getA().equals(x) && leg.getB().equals(y)}
         * has three). Only boolean decisions on calls are kept; null when a path has anything else, or side effects.
         */
        List<Applied> applyLambdaPaths(Expr lam, List<Expr> actuals) {
            if (lam.kind != Expr.Kind.LAMBDA || lam.owner == null || lam.name == null || lam.memberDesc == null) return null;
            List<Expr> all = new ArrayList<>(lam.args);
            all.addAll(actuals);
            Type[] ats = Type.getArgumentTypes(lam.memberDesc);
            String ret = Type.getReturnType(lam.memberDesc).getDescriptor();
            int tag = lam.index;
            if (!lam.name.startsWith("lambda$")) {
                Expr v = switch (tag) {
                    case Opcodes.H_INVOKESTATIC -> all.size() == ats.length ? Expr.call(null, lam.owner, lam.name, lam.memberDesc, all) : null;
                    case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE ->
                            all.size() == ats.length + 1 ? Expr.call(all.get(0), lam.owner, lam.name, lam.memberDesc, all.subList(1, all.size())) : null;
                    default -> null;
                };
                return v == null ? null : List.of(new Applied(List.of(), v));
            }
            ClassNode node = lam.owner.equals(cn.name) ? cn : repo.node(lam.owner);
            if (node == null) return null;
            MethodNode body = node.methods.stream().filter(m -> m.name.equals(lam.name) && m.desc.equals(lam.memberDesc)).findFirst().orElse(null);
            if (body == null) return null;
            boolean isStaticBody = (body.access & Opcodes.ACC_STATIC) != 0;
            if (all.size() != ats.length + (isStaticBody ? 0 : 1)) return null;
            PathExplorer.Result r;
            try {
                r = PathExplorer.explore(repo, node, body, PathExplorer.Options.defaults());
            } catch (RuntimeException ex) {
                return null;
            }
            if (r.truncated() || r.paths().isEmpty() || r.paths().size() > 8) return null;
            int offset = isStaticBody ? 0 : 1;
            List<Expr> params = r.params();
            java.util.function.UnaryOperator<Expr> bind = x0 -> {
                Expr x = x0;
                for (int k = 0; k < params.size(); k++) {
                    Expr param = params.get(k);
                    Expr actual = all.get(k + offset);
                    x = x.substitute(y -> y == param || (y.kind == Expr.Kind.PARAM && y.index == param.index && y.text.equals(param.text)), actual);
                }
                if (!isStaticBody) {
                    Expr self = all.get(0);
                    x = x.substitute(y -> y.kind == Expr.Kind.THIS, self);
                }
                Expr done = x;
                // anything of the body left unsubstituted (inside an operator, say) cannot be named by the test
                boolean leftover = params.stream().anyMatch(param -> done.mentions(y -> y.kind == Expr.Kind.PARAM && y.text.equals(param.text)
                        && all.stream().noneMatch(a -> a.mentions(z -> z == y))));
                return leftover ? null : done;
            };
            List<Applied> out = new ArrayList<>();
            for (Path path : r.paths()) {
                if (path.outcome().kind() != Path.OutcomeKind.RETURN || path.outcome().value() == null) return null;
                for (Effect ef : path.effects()) { // only calls that compute a value (BigDecimal.multiply) may remain
                    if (ef.kind() != Effect.Kind.CALL || ef.target() == null || ef.owner() == null || !ef.owner().startsWith("java/")
                            || !isPureInstanceCall(Expr.call(ef.target(), ef.owner(), ef.name(), ef.desc(), ef.args()))) return null;
                }
                List<Map.Entry<Expr, Boolean>> conds = new ArrayList<>();
                for (Cond c : path.conds()) {
                    if (c.kind() != Cond.Kind.BOOL || c.left() == null) return null;
                    Expr left = bind.apply(c.left());
                    if (left == null) return null;
                    conds.add(Map.entry(left, "T".equals(c.value())));
                }
                Expr v = bind.apply(path.outcome().value());
                if (v == null) return null;
                out.add(new Applied(conds, v.desc == null ? v.withDesc(ret) : v));
            }
            return out;
        }

        String listOf(List<String> elems) {
            imports.add("java.util.List");
            return "List.of(" + String.join(", ", elems) + ")";
        }

        /**
         * A static constant the test can name: an accessible {@code static final} field ({@code BigDecimal.ZERO},
         * {@code Order.MAX_ITEMS}), or a private one initialised from literals by a pure JDK factory
         * ({@code DateTimeFormatter.ofPattern("yyyy/MM/dd")}), re-created in the test. Else null.
         */
        String staticConstant(Expr e) {
            if (e.owner == null || e.name == null) return null;
            if (e.owner.startsWith("java/")) {
                try {
                    Class<?> c = Class.forName(e.owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                    int mod = c.getField(e.name).getModifiers();
                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers()) && java.lang.reflect.Modifier.isStatic(mod)
                            && java.lang.reflect.Modifier.isFinal(mod)) return imports.ref(Type.getObjectType(e.owner)) + "." + e.name;
                } catch (ReflectiveOperationException | LinkageError ex) {
                    return null;
                }
                return null;
            }
            ClassNode node = e.owner.equals(cn.name) ? cn : repo.node(e.owner);
            if (node == null) return null;
            FieldNode fn = node.fields.stream().filter(f -> f.name.equals(e.name)).findFirst().orElse(null);
            if (fn == null || (fn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) return null;
            boolean samePackage = Names.packageOf(node.name).equals(Names.packageOf(cn.name));
            boolean visibleClass = (node.access & Opcodes.ACC_PUBLIC) != 0 || samePackage;
            boolean visibleField = (fn.access & Opcodes.ACC_PUBLIC) != 0 || ((fn.access & Opcodes.ACC_PRIVATE) == 0 && samePackage);
            if (visibleClass && visibleField && !node.name.contains("$")) return imports.ref(Type.getObjectType(node.name)) + "." + e.name;
            return constantInitializer(node, fn);
        }

        /** {@code static final X F = Factory.of("literal", ..);} in the class initializer, as test source; else null. */
        String constantInitializer(ClassNode node, FieldNode fn) {
            MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
            if (clinit == null) return null;
            List<AbstractInsnNode> code = new ArrayList<>();
            for (AbstractInsnNode in : clinit.instructions) if (in.getOpcode() >= 0) code.add(in);
            for (int i = 0; i < code.size(); i++) {
                if (code.get(i).getOpcode() != Opcodes.PUTSTATIC || !((FieldInsnNode) code.get(i)).name.equals(fn.name) || i < 1) continue;
                if (!(code.get(i - 1) instanceof org.objectweb.asm.tree.MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) return null;
                if (!CONSTANT_FACTORIES.contains(call.owner + "." + call.name)) return null;
                int argc = Type.getArgumentTypes(call.desc).length;
                if (i - 1 - argc < 0) return null;
                List<String> args = new ArrayList<>();
                for (int k = i - 1 - argc; k < i - 1; k++) {
                    if (!(code.get(k) instanceof org.objectweb.asm.tree.LdcInsnNode ldc) || !(ldc.cst instanceof String str)) return null;
                    args.add(Expr.constant(str, "Ljava/lang/String;").text);
                }
                return imports.ref(Type.getObjectType(call.owner)) + "." + call.name + "(" + String.join(", ", args) + ")";
            }
            return null;
        }

        private static final Set<String> CONSTANT_FACTORIES = Set.of("java/time/format/DateTimeFormatter.ofPattern",
                "java/util/regex/Pattern.compile", "java/math/BigDecimal.valueOf", "java/time/Duration.parse", "java/time/ZoneId.of");

        /** Instance methods that only read their receiver: safe to re-evaluate in the assertion. */
        boolean isPureInstanceCall(Expr e) {
            String o = e.owner == null ? "" : e.owner;
            if (Set.of("hashCode", "toString", "equals").contains(e.name)) return true;
            if (o.equals("java/lang/String") || o.startsWith("java/time/") || o.startsWith("java/math/")
                    || Set.of("java/lang/Integer", "java/lang/Long", "java/lang/Boolean", "java/lang/Double").contains(o)) return true;
            if (o.equals("java/lang/Class")) return Set.of("isAssignableFrom", "isInstance", "getSimpleName", "getName").contains(e.name);
            if (o.startsWith("java/lang/") && (e.name.equals("getMessage") || e.name.equals("getLocalizedMessage")) && e.args.isEmpty()) return true;
            if (o.startsWith("java/util/") && Set.of("size", "isEmpty", "contains", "containsKey", "get").contains(e.name)) return true;
            return false;
        }

        /**
         * The expected value as test source built from what the test controls: literals, parameters, the subject's
         * fields (set here to known values), stubbed collaborator results and pure JDK functions of those
         * ({@code Objects.hash(origin, destination)}, {@code "Voyage " + voyageNumber}). Null when some part is not
         * predictable (a lambda, a local builder, the clock, an application constructor).
         */
        String renderExpected(Expr e, int depth) {
            if (depth > 6) return null;
            switch (e.kind) {
                case NULL: return "null";
                case CONST: return literalOf(e);
                case PARAM: return paramVars.get(e.index);
                case STATIC: return isEnumConst(e) ? literalOf(e) : staticConstant(e);
                default: break;
            }
            if (e.expansion() == null && ensured.containsKey(e.text)) return ensured.get(e.text);
            if (e.kind == Expr.Kind.CALL && e.target != null) {
                Pipeline pl = pipeline(e);
                if (pl != null && !pl.iterator()) return pl.expected();
            }
            if (ensured.containsKey(canon(e))) return ensured.get(canon(e));
            String bound = boundLiteral.get(e.text);
            if (bound != null && !bound.startsWith("mock(") && !bound.contains("new ")) return bound;
            if (e.isConcat()) {
                List<Expr> flat = new ArrayList<>();
                Deque<Expr> todo = new ArrayDeque<>(e.args);
                while (!todo.isEmpty()) { // "a" + ("" + x) → "a" + x
                    Expr part = todo.pollFirst();
                    if (part.isConcat()) {
                        List<Expr> inner = new ArrayList<>(part.args);
                        Collections.reverse(inner);
                        inner.forEach(todo::addFirst);
                    } else flat.add(part);
                }
                List<String> parts = new ArrayList<>();
                for (Expr part : flat) {
                    String r = renderExpected(part, depth + 1);
                    if (r == null) return null;
                    parts.add(part.kind == Expr.Kind.BINARY ? "(" + r + ")" : r);
                }
                if (parts.isEmpty()) return "\"\"";
                // the first operand must already be a String, or `a + b` would add numerically
                boolean leadingString = !parts.isEmpty() && (parts.get(0).startsWith("\"") || "Ljava/lang/String;".equals(flat.get(0).desc));
                return (leadingString ? "" : "\"\" + ") + String.join(" + ", parts);
            }
            switch (e.kind) {
                case FIELD -> {
                    if (e.target == null || e.target.kind != Expr.Kind.THIS || isStatic || (cn.access & Opcodes.ACC_ENUM) != 0 || e.desc == null) return null;
                    if (deps.containsKey(e.name)) return e.name;
                    Type t = Type.getType(e.desc);
                    String lit;
                    if (t.getSort() == Type.OBJECT && !ClassInspector.isValueType(e.desc) && !isEnum(t.getInternalName())) lit = newVar(t);
                    else if (t.getSort() == Type.OBJECT && mutableCollection(t) != null) lit = mutableCollection(t);
                    else lit = nonNullSample(t, e);
                    if (lit == null) return null;
                    needsSetField = true;
                    arrange.add("setField(subject, \"" + e.name + "\", " + lit + ");");
                    if (lit.matches("[a-z][A-Za-z0-9_$]*")) ensured.put(e.text, lit);
                    else boundLiteral.put(e.text, lit);
                    return lit;
                }
                case BINARY -> {
                    if (e.args.size() != 2 || !Set.of("+", "-", "*", "/", "%").contains(e.name)) return null;
                    String a = renderExpected(e.args.get(0), depth + 1), b = renderExpected(e.args.get(1), depth + 1);
                    return a == null || b == null ? null : "(" + a + " " + e.name + " " + b + ")";
                }
                case ARRAY_NEW -> {
                    // new Class[]{String.class, Integer.class}: the same literal, element by element
                    List<Expr> elems = e.arrayElements();
                    if (elems == null || e.desc == null || !e.desc.startsWith("[")) return null;
                    List<String> parts = new ArrayList<>();
                    for (Expr el : elems) {
                        String r = renderExpected(el, depth + 1);
                        if (r == null) return null;
                        parts.add(r);
                    }
                    return "new " + imports.ref(Type.getType(e.desc).getElementType()) + "[]".repeat(Type.getType(e.desc).getDimensions())
                            + "{" + String.join(", ", parts) + "}";
                }
                case NEW -> {
                    // an empty collection only if nothing on the path added to it (List<OrderLine> lines = new ArrayList<>(); lines.add(..))
                    // (stored as is into a field nothing else on the path touches, like a lazy getter's `specialties = new HashSet<>()`, is not a change)
                    boolean touched = p.effects().stream().anyMatch(ef -> !storedUntouched(ef, e) && !onlyReads(ef, e)
                            && ((ef.target() != null && ef.target().mentions(x -> x.text.equals(e.text)))
                            || ef.args().stream().anyMatch(a -> a.mentions(x -> x.text.equals(e.text)))
                            || (ef.value() != null && ef.value().mentions(x -> x.text.equals(e.text)))));
                    if (!touched && e.args.isEmpty() && e.owner != null && e.owner.startsWith("java/util/") && collectionFactory(Type.getObjectType(e.owner)) != null) {
                        return "new " + imports.ref(Type.getObjectType(e.owner)) + "<>()";
                    }
                    if (!touched && e.owner != null && (e.owner.equals("java/math/BigDecimal") || e.owner.equals("java/math/BigInteger"))) {
                        Type[] cats = Type.getArgumentTypes(e.memberDesc);
                        List<String> args = new ArrayList<>();
                        for (int k = 0; k < e.args.size(); k++) {
                            String a = renderExpected(e.args.get(k), depth + 1);
                            if (a == null || a.equals("null")) return null;
                            args.add(cats.length == e.args.size() && cats[k].getSort() == Type.INT && a.matches("-?\\d+L") ? a.substring(0, a.length() - 1) : a);
                        }
                        return "new " + imports.ref(Type.getObjectType(e.owner)) + "(" + String.join(", ", args) + ")";
                    }
                    return touched ? filledCollection(e, depth) : null;
                }
                case CALL -> {
                    Type[] ats = Type.getArgumentTypes(e.memberDesc);
                    if (e.target == null) {
                        if (!STATIC_PURE.contains(e.owner + "." + e.name)) return null;
                        List<String> args = renderArgs(e, ats, depth);
                        return args == null ? null : imports.ref(Type.getObjectType(e.owner)) + "." + e.name + "(" + String.join(", ", args) + ")";
                    }
                    if (isPureInstanceCall(e)) {
                        String recv = renderExpected(e.target, depth + 1);
                        List<String> args = renderArgs(e, ats, depth);
                        if (recv == null || args == null) return null;
                        if (recv.matches("-?\\d[\\w.]*|'.'|true|false") && e.target.desc != null && e.target.desc.startsWith("L")) {
                            recv = "((" + imports.ref(Type.getType(e.target.desc)) + ") " + recv + ")"; // 1.hashCode() is not Java
                        }
                        if (recv.matches("new \\w+<>\\(\\)") && args.isEmpty()) { // queries on an empty collection
                            boolean list = recv.contains("List") || recv.contains("Deque");
                            switch (e.name) {
                                case "size": return "0";
                                case "isEmpty": return "true";
                                case "hashCode": return list ? "1" : "0";
                                default: break;
                            }
                        }
                        return recv + "." + e.name + "(" + String.join(", ", args) + ")";
                    }
                    // a collaborator's answer: stub it (or reuse the stub) and expect that value
                    if (!arrangeable(e) || e.desc == null) return null;
                    Type t = Type.getType(e.desc);
                    if (t.getSort() == Type.OBJECT && !ClassInspector.isValueType(e.desc)) return ensure(e);
                    String lit = nonNullSample(t, e);
                    return lit != null && bindLiteral(e, lit) ? lit : null;
                }
                default -> {
                    return null;
                }
            }
        }

        /** Rendered arguments; a varargs array built in place ({@code new Object[]{a, b}}) is spread back out. */
        List<String> renderArgs(Expr call, Type[] ats, int depth) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < call.args.size(); i++) {
                Expr a = call.args.get(i);
                if (i == call.args.size() - 1 && ats.length > 0 && ats[ats.length - 1].getSort() == Type.ARRAY && a.arrayElements() != null) {
                    for (Expr el : a.arrayElements()) {
                        String r = renderExpected(el, depth + 1);
                        if (r == null) return null;
                        out.add(r.equals("null") ? "(Object) null" : r);
                    }
                    continue;
                }
                String r = renderExpected(a, depth + 1);
                if (r == null) return null;
                out.add(r);
            }
            return out;
        }

        List<String> expectEffects() {
            List<String> out = new ArrayList<>();
            // final value of each field written on `this`
            Map<String, Effect> lastWrite = new LinkedHashMap<>();
            for (Effect e : p.effects()) {
                if (e.kind() == Effect.Kind.FIELD_WRITE && e.target().kind == Expr.Kind.THIS) lastWrite.put(e.name(), e);
            }
            for (Effect e : lastWrite.values()) {
                Expr v = e.value();
                String lit = null;
                if (v.kind == Expr.Kind.CONST || v.kind == Expr.Kind.NULL || isEnumConst(v)) lit = literalOf(v);
                else if (v.kind == Expr.Kind.PARAM) lit = paramVars.get(v.index);
                if (lit == null) continue;
                needsGetField = true;
                if (Type.getType(e.desc()).getSort() == Type.LONG && v.kind == Expr.Kind.CONST && !lit.endsWith("L")) lit = lit + "L";
                out.add("assertEquals(" + lit + ", getField(subject, \"" + e.name() + "\"));");
            }
            // collaborator interactions: @Mock dependencies and mocks created for this test (e.g. entities)
            Set<String> verified = new LinkedHashSet<>();
            for (Effect e : p.effects()) {
                if (e.kind() != Effect.Kind.CALL || e.target() == null) continue;
                Expr t = e.target();
                String mockVar = null;
                if (t.kind == Expr.Kind.FIELD && t.target != null && t.target.kind == Expr.Kind.THIS && deps.containsKey(t.name)) mockVar = t.name;
                else if (ensured.containsKey(t.text)) mockVar = ensured.get(t.text);
                else if (t.kind == Expr.Kind.PARAM && String.valueOf(paramValues.get(t.index)).startsWith("mock(")) mockVar = paramVars.get(t.index);
                if (mockVar == null) continue;
                boolean isVoid = Type.getReturnType(e.desc()).getSort() == Type.VOID;
                if (!isVoid && !MUTATOR.matcher(e.name()).matches()) continue;
                Type[] ats = Type.getArgumentTypes(e.desc());
                boolean overloaded = isOverloaded(e.owner(), e.name(), ats.length);
                List<String> ms = new ArrayList<>();
                for (int i = 0; i < e.args().size(); i++) ms.add(argMatcher(e.args().get(i), ats[i], overloaded));
                verified.add("verify(" + mockVar + ", atLeastOnce())." + e.name() + "(" + String.join(", ", ms) + ");");
            }
            out.addAll(verified);
            return out;
        }

        /** {@code eq(value)} when the argument's value is known in the test, otherwise a typed any-matcher. */
        String argMatcher(Expr a, Type t, boolean overloaded) {
            String known = null;
            if (a.kind == Expr.Kind.CONST || a.kind == Expr.Kind.NULL || isEnumConst(a)) known = literalOf(a);
            else if (a.kind == Expr.Kind.PARAM) known = paramVars.get(a.index);
            else if (ensured.containsKey(canon(a))) known = ensured.get(canon(a));
            else if (a.expansion() == null && ensured.containsKey(a.text)) known = ensured.get(a.text); // not a reassigned parameter's name
            if (known != null && !known.equals("null")) {
                if (t.getSort() == Type.LONG && a.kind == Expr.Kind.CONST && !known.endsWith("L")) known += "L";
                return "eq(" + known + ")";
            }
            if (known != null) return overloaded && t.getSort() == Type.OBJECT ? "isNull(" + imports.ref(t) + ".class)" : "isNull()";
            return anyMatcher(t, overloaded);
        }

        boolean isOptional(Expr e) {
            return e != null && "Ljava/util/Optional;".equals(e.desc);
        }
    }

    // =============================================================================================

    // ---- value samples (shared by test bodies and subject construction) ----

    String collectionFactory(Type type) {
        if (type.getSort() != Type.OBJECT) return null;
        String n = type.getInternalName();
        return switch (n) {
            case "java/util/List", "java/util/Collection", "java/lang/Iterable", "java/util/ArrayList" -> { imports.add("java.util.List"); yield "List.of"; }
            case "java/util/Set", "java/util/HashSet" -> { imports.add("java.util.Set"); yield "Set.of"; }
            case "java/util/Map", "java/util/HashMap" -> { imports.add("java.util.Map"); yield "Map.of"; }
            default -> null;
        };
    }

    String mutableCollection(Type type) {
        String f = collectionFactory(type);
        if (f == null) return null;
        return switch (f) {
            case "List.of" -> { imports.add("java.util.ArrayList"); yield "new ArrayList<>()"; }
            case "Set.of" -> { imports.add("java.util.HashSet"); yield "new HashSet<>()"; }
            default -> { imports.add("java.util.HashMap"); yield "new HashMap<>()"; }
        };
    }

    String nonNullSample(Type type, Expr e) {
        String d = type.getDescriptor();
        switch (d) {
            case "Z", "Ljava/lang/Boolean;": return "true";
            case "I", "Ljava/lang/Integer;", "S", "B": return "1";
            case "J", "Ljava/lang/Long;": return "1L";
            case "D", "Ljava/lang/Double;": return "1.0";
            case "F", "Ljava/lang/Float;": return "1.0f";
            case "C", "Ljava/lang/Character;": return "'a'";
            case "Ljava/lang/String;", "Ljava/lang/CharSequence;": return "\"value\"";
            case "Ljava/math/BigDecimal;": imports.add("java.math.BigDecimal"); return "BigDecimal.ONE";
            case "Ljava/time/LocalDate;": imports.add("java.time.LocalDate"); return "LocalDate.of(2024, 1, 15)";
            case "Ljava/time/LocalDateTime;": imports.add("java.time.LocalDateTime"); return "LocalDateTime.of(2024, 1, 15, 10, 30)";
            case "Ljava/time/Instant;": imports.add("java.time.Instant"); return "Instant.parse(\"2024-01-15T10:30:00Z\")";
            case "Ljava/util/Date;": imports.add("java.util.Date"); return "new Date(1705314600000L)";
            case "Ljava/util/UUID;": imports.add("java.util.UUID"); return "UUID.fromString(\"00000000-0000-0000-0000-000000000001\")";
            case "Ljava/util/Optional;": imports.add("java.util.Optional"); return "Optional.empty()";
            case "Ljava/lang/Object;": return "new Object()";
            case "Ljava/lang/Class;": return "Object.class";
            default: break;
        }
        // mutable: code under test often adds to passed-in collections (errors, model attributes, results)
        String coll = mutableCollection(type);
        if (coll != null) return coll;
        if (type.getSort() == Type.ARRAY) return "new " + imports.ref(type.getElementType()) + "[0]";
        if (type.getSort() == Type.OBJECT && isEnum(type.getInternalName())) {
            List<String> cs = ClassInspector.enumConstants(repo.node(type.getInternalName()));
            if (!cs.isEmpty()) return imports.ref(type) + "." + cs.get(0);
        }
        if (type.getSort() == Type.OBJECT && !type.getInternalName().startsWith("java/lang/")) {
            return mockOf(type);
        }
        if (type.getSort() == Type.OBJECT && type.getInternalName().startsWith("java/lang/")) {
            // an exception handed to a listener or handler (onSkipReadItem(Exception e)): a real one with a message
            try {
                Class<?> c = Class.forName(type.getClassName(), false, ClassLoader.getPlatformClassLoader());
                if (Throwable.class.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    c.getConstructor(String.class);
                    return "new " + imports.ref(type) + "(\"value\")";
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // no public (String) constructor
            }
        }
        return null;
    }

    /**
     * A typed any-matcher. For overloaded methods, reference types get an explicit type witness
     * ({@code ArgumentMatchers.<Integer>any()}) so javac can pick the overload; unlike {@code any(Integer.class)}
     * it still matches null.
     */
    private String anyMatcher(Type t, boolean overloaded) {
        return switch (t.getSort()) {
            case Type.INT -> "anyInt()"; case Type.LONG -> "anyLong()"; case Type.BOOLEAN -> "anyBoolean()";
            case Type.DOUBLE -> "anyDouble()"; case Type.FLOAT -> "anyFloat()"; case Type.SHORT -> "anyShort()";
            case Type.BYTE -> "anyByte()"; case Type.CHAR -> "anyChar()";
            default -> {
                if (!overloaded || t.getDescriptor().equals("Ljava/lang/Object;")) yield "any()";
                imports.add("org.mockito.ArgumentMatchers");
                yield "ArgumentMatchers.<" + imports.ref(t) + ">any()";
            }
        };
    }

    /** Does the owner (or a supertype in the archive) declare another method with this name and arity? Unknown owners count as overloaded. */
    private boolean isOverloaded(String owner, String name, int arity) {
        ClassNode start = repo.node(owner);
        if (start == null) return true;
        Set<String> descs = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String n = todo.poll();
            if (!seen.add(n)) continue;
            ClassNode c = repo.node(n);
            if (c == null) continue;
            for (MethodNode m : c.methods) {
                if (m.name.equals(name) && Type.getArgumentTypes(m.desc).length == arity && (m.access & Opcodes.ACC_BRIDGE) == 0) {
                    descs.add(m.desc.substring(0, m.desc.indexOf(')')));
                }
            }
            if (c.superName != null) todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return descs.size() > 1;
    }

    /** A mock whose enum-returning methods default to the first constant (switch on null would throw NPE). */
    private String mockOf(Type t) {
        needsEnumSafe = true;
        return "mock(" + imports.ref(t) + ".class, ENUM_SAFE)";
    }

    /** A switch on {@code s.hashCode()} of a String: how javac compiles {@code switch (s)}. */
    static boolean stringSwitchHash(Cond c) {
        Expr l = c.left();
        return l != null && l.kind == Expr.Kind.CALL && "hashCode".equals(l.name) && l.args.isEmpty() && l.target != null
                && "Ljava/lang/String;".equals(l.target.desc);
    }

    /** equals/hashCode cannot be stubbed with Mockito (and stubbing toString hides bugs). */
    private static boolean isObjectMethod(Expr call) {
        return ("hashCode".equals(call.name) && "()I".equals(call.memberDesc))
                || ("equals".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.memberDesc))
                || ("toString".equals(call.name) && "()Ljava/lang/String;".equals(call.memberDesc));
    }

    private boolean isEnumConst(Expr e) {
        return e.kind == Expr.Kind.STATIC && e.desc != null && e.desc.equals("L" + e.owner + ";");
    }

    private Number numericValue(Expr e) {
        if (e.kind == Expr.Kind.CONST && e.value instanceof Number n) return n;
        if (e.kind == Expr.Kind.STATIC && (e.owner.equals("java/math/BigDecimal") || e.owner.equals("java/math/BigInteger"))) {
            return switch (e.name) { case "ZERO" -> 0; case "ONE" -> 1; case "TWO" -> 2; case "TEN" -> 10; default -> null; };
        }
        if (e.kind == Expr.Kind.CALL && "valueOf".equals(e.name) && e.target == null && e.args.size() == 1
                && e.args.get(0).value instanceof Number n) return n;
        if (e.kind == Expr.Kind.NEW && e.owner.equals("java/math/BigDecimal") && e.args.size() == 1) {
            Object v = e.args.get(0).value;
            if (v instanceof Number n) return n;
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Java source literal for a constant / enum constant expression. */
    private String literalOf(Expr e) {
        if (e.kind == Expr.Kind.NULL) return "null";
        if (isEnumConst(e)) return imports.ref(e.owner) + "." + e.name;
        if (e.kind == Expr.Kind.STATIC) return imports.ref(e.owner) + "." + e.name;
        if (e.kind != Expr.Kind.CONST) return e.text;
        Object v = e.value;
        if (v instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
        if (v instanceof Character c) return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
        if (v instanceof Type t) return imports.ref(t) + ".class";
        if ("Z".equals(e.desc) && v instanceof Integer i) return i != 0 ? "true" : "false";
        if (v instanceof Long l) return l + "L";
        if (v instanceof Float f) return f + "f";
        return String.valueOf(v);
    }

    private static final String REFLECTION_HELPERS = """
                // ---- reflection helpers (generated) ----

                private static void setField(Object target, String name, Object value) {
                    try {
                        java.lang.reflect.Field f = findField(target.getClass(), name);
                        f.setAccessible(true);
                        f.set(target, value);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }

                private static Object getField(Object target, String name) {
                    try {
                        java.lang.reflect.Field f = findField(target.getClass(), name);
                        f.setAccessible(true);
                        return f.get(target);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }

                private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
                    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                        try {
                            return c.getDeclaredField(name);
                        } catch (NoSuchFieldException ignored) {
                            // keep looking in superclasses
                        }
                    }
                    throw new NoSuchFieldException(name);
                }
            """;

    // =============================================================================================

    /** Tracks imports; falls back to fully-qualified names on simple-name clashes. */
    static final class Imports {
        private final Map<String, String> bySimple = new HashMap<>();
        private final Set<String> imports = new TreeSet<>();

        void add(String fqcn) {
            String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            bySimple.putIfAbsent(simple, fqcn);
            if (bySimple.get(simple).equals(fqcn)) imports.add(fqcn);
        }

        String ref(String internalName) {
            return ref(Type.getObjectType(internalName));
        }

        String ref(Type t) {
            if (t.getSort() == Type.ARRAY) return ref(t.getElementType()) + "[]".repeat(t.getDimensions());
            if (t.getSort() != Type.OBJECT) return t.getClassName();
            String fq = t.getClassName().replace('$', '.');
            String simple = fq.substring(fq.lastIndexOf('.') + 1);
            if (fq.startsWith("java.lang.") && fq.indexOf('.', 10) < 0) return simple;
            String existing = bySimple.putIfAbsent(simple, fq);
            if (existing != null && !existing.equals(fq)) return fq;
            imports.add(fq);
            return simple;
        }

        Collection<String> sorted() {
            return imports;
        }
    }
}
