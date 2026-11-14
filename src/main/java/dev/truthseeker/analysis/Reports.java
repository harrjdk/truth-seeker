package dev.truthseeker.analysis;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/** Markdown reports for the inventory-style tools. */
public final class Reports {
    private Reports() {}

    // ---- scan_archive ---------------------------------------------------------------------------

    public static String scan(ClassRepository repo, String packagePrefix, boolean includeLibraries, int limit) {
        StringBuilder sb = new StringBuilder();
        List<ClassRepository.ClassEntry> all = new ArrayList<>(repo.entries());
        long app = all.stream().filter(e -> !e.library()).count();
        sb.append("# Archive scan: `").append(repo.root().getFileName()).append("`\n\n");
        sb.append("- Classes indexed: **").append(all.size()).append("** (application: ").append(app)
                .append(", bundled libraries: ").append(all.size() - app).append(")\n");
        if (repo.duplicates() > 0) sb.append("- Duplicate class names across jars: ").append(repo.duplicates()).append(" (possible classpath conflicts)\n");

        List<ClassRepository.ClassEntry> selected = all.stream()
                .filter(e -> includeLibraries || !e.library())
                .filter(e -> Endpoints.matches(e.internalName(), packagePrefix))
                .toList();

        // bytecode versions
        Map<Integer, Long> versions = selected.stream().collect(Collectors.groupingBy(ClassRepository.ClassEntry::majorVersion, TreeMap::new, Collectors.counting()));
        sb.append("- Bytecode levels (selected classes): ");
        sb.append(versions.entrySet().stream().map(v -> Names.javaVersion(v.getKey()) + " ×" + v.getValue()).collect(Collectors.joining(", ")));
        sb.append("\n");
        if (versions.keySet().stream().anyMatch(v -> v < 61)) {
            sb.append("  - Note: classes compiled below Java 17 still analyze fine; this is useful for migration planning.\n");
        }

        // packages
        Map<String, Long> packages = selected.stream().collect(Collectors.groupingBy(e -> Names.packageOf(e.internalName()), TreeMap::new, Collectors.counting()));
        sb.append("- Packages: ").append(packages.size()).append("\n");

        // libraries
        if (!includeLibraries) {
            Set<String> jars = all.stream().filter(ClassRepository.ClassEntry::library)
                    .map(e -> jarOf(e.origin())).collect(Collectors.toCollection(TreeSet::new));
            if (!jars.isEmpty()) {
                sb.append("- Bundled library jars: ").append(jars.size()).append(" — ")
                        .append(jars.stream().limit(60).collect(Collectors.joining(", ")))
                        .append(jars.size() > 60 ? ", …" : "").append("\n");
            }
        }
        if (!repo.descriptors().isEmpty()) {
            sb.append("- Descriptors / config files: ").append(String.join(", ", repo.descriptors().keySet())).append("\n");
        }
        if (!repo.warnings().isEmpty()) {
            sb.append("- Warnings: ").append(repo.warnings().size()).append(" (first: ").append(repo.warnings().get(0)).append(")\n");
        }

        // stereotypes
        Map<String, List<String>> byStereo = new TreeMap<>();
        List<String[]> rows = new ArrayList<>();
        for (ClassRepository.ClassEntry e : selected) {
            ClassNode cn = repo.node(e.internalName());
            if (cn.name.contains("$") && (cn.access & Opcodes.ACC_ENUM) == 0 && isAnonymousOrSynthetic(cn)) continue;
            List<String> st = ClassInspector.stereotypes(repo, cn);
            for (String s : st) byStereo.computeIfAbsent(s, k -> new ArrayList<>()).add(Names.dotted(cn.name));
            if (!st.isEmpty()) {
                int maxCx = cn.methods.stream().mapToInt(ClassInspector::complexity).max().orElse(0);
                rows.add(new String[]{Names.dotted(cn.name), String.join(", ", st), String.valueOf(cn.methods.size()), String.valueOf(maxCx)});
            }
        }
        sb.append("\n## Stereotype summary\n\n");
        if (byStereo.isEmpty()) sb.append("_No framework stereotypes detected._\n");
        for (var en : byStereo.entrySet()) sb.append("- ").append(en.getKey()).append(": ").append(en.getValue().size()).append("\n");

        sb.append("\n## Framework components\n\n| Class | Stereotypes | Methods | Max complexity |\n|---|---|---|---|\n");
        rows.sort(Comparator.comparing(r -> r[0]));
        int shown = 0;
        for (String[] r : rows) {
            if (shown++ >= limit) {
                sb.append("| … ").append(rows.size() - limit).append(" more | | | |\n");
                break;
            }
            sb.append("| `").append(r[0]).append("` | ").append(r[1]).append(" | ").append(r[2]).append(" | ").append(r[3]).append(" |\n");
        }

        // complexity hot spots
        record Hot(String cls, String m, int cx) {}
        List<Hot> hots = new ArrayList<>();
        for (ClassRepository.ClassEntry e : selected) {
            ClassNode cn = repo.node(e.internalName());
            if ((cn.access & Opcodes.ACC_SYNTHETIC) != 0) continue; // e.g. javac's $SwitchMap$ holder classes
            for (MethodNode mn : cn.methods) {
                if (ClassInspector.isSynthetic(mn)) continue;
                int cx = ClassInspector.complexity(mn);
                if (cx >= 10) hots.add(new Hot(Names.dotted(cn.name), Names.methodSignature(mn.name, mn.desc), cx));
            }
        }
        hots.sort(Comparator.comparingInt(Hot::cx).reversed());
        if (!hots.isEmpty()) {
            sb.append("\n## Complexity hot spots (cyclomatic ≥ 10)\n\n| Complexity | Class | Method |\n|---|---|---|\n");
            for (Hot h : hots.subList(0, Math.min(25, hots.size()))) {
                sb.append("| ").append(h.cx).append(" | `").append(h.cls).append("` | `").append(h.m).append("` |\n");
            }
            sb.append("\nGood candidates for `truth_table` and `generate_unit_tests`.\n");
        }

        sb.append("\n## Packages\n\n");
        packages.forEach((p, n) -> sb.append("- `").append(p.isEmpty() ? "(default)" : p).append("` (").append(n).append(")\n"));
        return sb.toString();
    }

    private static boolean isAnonymousOrSynthetic(ClassNode cn) {
        String last = cn.name.substring(cn.name.lastIndexOf('$') + 1);
        return last.isEmpty() || Character.isDigit(last.charAt(0)) || (cn.access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private static String jarOf(String origin) {
        String[] parts = origin.split("!/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].toLowerCase(Locale.ROOT).endsWith(".jar")) return parts[i].substring(parts[i].lastIndexOf('/') + 1);
        }
        return parts[0];
    }

    // ---- list_endpoints -------------------------------------------------------------------------

    public static String endpoints(ClassRepository repo, String packagePrefix) {
        List<Endpoints.Endpoint> eps = Endpoints.find(repo, packagePrefix);
        StringBuilder sb = new StringBuilder("# Entry points\n\n");
        if (eps.isEmpty()) return sb.append("_None found (no Spring/JAX-RS/Servlet/messaging annotations or web.xml/struts-config mappings)._\n").toString();
        sb.append("| Kind | Verb | Path / destination | Handler | Declared in |\n|---|---|---|---|---|\n");
        for (Endpoints.Endpoint e : eps) {
            sb.append("| ").append(e.kind()).append(" | ").append(e.verb()).append(" | `").append(e.path()).append("` | `")
                    .append(e.handler()).append("` | ").append(e.source()).append(" |\n");
        }
        return sb.toString();
    }

    // ---- describe_class -------------------------------------------------------------------------

    public static String describe(ClassRepository repo, ClassNode cn) {
        StringBuilder sb = new StringBuilder();
        ClassRepository.ClassEntry entry = repo.entry(cn.name).orElseThrow();
        sb.append("# ").append(ClassInspector.kind(cn)).append(" `").append(Names.dotted(cn.name)).append("`\n\n");
        sb.append("- Bytecode: ").append(Names.javaVersion(entry.majorVersion())).append(" (major ").append(entry.majorVersion()).append(")\n");
        sb.append("- Origin: `").append(entry.origin()).append("`").append(entry.library() ? " (library)" : "").append("\n");
        if (cn.sourceFile != null) sb.append("- Source file: ").append(cn.sourceFile).append("\n");
        if (cn.superName != null && !cn.superName.equals("java/lang/Object")) sb.append("- Extends: `").append(Names.dotted(cn.superName)).append("`\n");
        if (!cn.interfaces.isEmpty()) sb.append("- Implements: ").append(cn.interfaces.stream().map(i -> "`" + Names.dotted(i) + "`").collect(Collectors.joining(", "))).append("\n");
        if (cn.permittedSubclasses != null && !cn.permittedSubclasses.isEmpty()) {
            sb.append("- Sealed, permits: ").append(cn.permittedSubclasses.stream().map(Names::simple).collect(Collectors.joining(", "))).append("\n");
        }
        List<String> st = ClassInspector.stereotypes(repo, cn);
        if (!st.isEmpty()) sb.append("- Stereotypes: ").append(String.join(", ", st)).append("\n");
        List<AnnotationNode> anns = ClassInspector.annotations(cn);
        if (!anns.isEmpty()) sb.append("- Annotations: ").append(anns.stream().map(ClassInspector::renderAnnotation).collect(Collectors.joining(" "))).append("\n");
        boolean hasDebug = cn.methods.stream().anyMatch(m -> m.localVariables != null && !m.localVariables.isEmpty());
        sb.append("- Debug info (local variable names): ").append(hasDebug ? "yes" : "no — parameters appear as arg0, arg1…").append("\n");

        if ((cn.access & Opcodes.ACC_ENUM) != 0) {
            sb.append("- Enum constants: ").append(String.join(", ", ClassInspector.enumConstants(cn))).append("\n");
        }

        List<ClassInspector.Injection> inj = ClassInspector.injections(cn);
        if (!inj.isEmpty()) {
            sb.append("\n## Dependencies (injected collaborators)\n\n| Field | Type | Injected via |\n|---|---|---|\n");
            for (var i : inj) sb.append("| `").append(i.fieldName()).append("` | `").append(Names.typeName(i.typeDesc())).append("` | ").append(i.how()).append(" |\n");
        }

        sb.append("\n## Fields\n\n");
        if (cn.fields.isEmpty()) sb.append("_none_\n");
        for (FieldNode fn : cn.fields) {
            if ((fn.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            sb.append("- `").append(ClassInspector.access(fn.access)).append(fn.access == 0 ? "" : " ").append(Names.typeName(fn.desc)).append(" ").append(fn.name).append("`");
            if (fn.value != null) sb.append(" = `").append(fn.value).append("`");
            List<AnnotationNode> fa = ClassInspector.annotations(fn);
            if (!fa.isEmpty()) sb.append(" ").append(fa.stream().map(ClassInspector::renderAnnotation).collect(Collectors.joining(" ")));
            sb.append("\n");
        }

        sb.append("\n## Methods\n\n| Method | Access | Complexity | Calls out | Annotations |\n|---|---|---|---|---|\n");
        for (MethodNode mn : cn.methods) {
            if (ClassInspector.isSynthetic(mn)) continue;
            long calls = 0;
            for (AbstractInsnNode in : mn.instructions) if (in instanceof MethodInsnNode) calls++;
            String ma = ClassInspector.annotations(mn).stream().map(ClassInspector::renderAnnotation).collect(Collectors.joining(" "));
            sb.append("| `").append(Names.methodSignature(mn.name, mn.desc)).append("` | ").append(ClassInspector.access(mn.access))
                    .append(" | ").append(ClassInspector.complexity(mn)).append(" | ").append(calls).append(" | ").append(ma.replace("|", "\\|")).append(" |\n");
            if (mn.exceptions != null && !mn.exceptions.isEmpty()) {
                sb.append("| ↳ throws ").append(mn.exceptions.stream().map(Names::simple).collect(Collectors.joining(", "))).append(" | | | | |\n");
            }
        }
        List<String> lambdas = cn.methods.stream().filter(m -> m.name.startsWith("lambda$")).map(m -> m.name).toList();
        if (!lambdas.isEmpty()) sb.append("\n_").append(lambdas.size()).append(" lambda bodies omitted (").append(lambdas.get(0)).append(", …)._\n");

        if (cn.innerClasses != null && !cn.innerClasses.isEmpty()) {
            List<String> nested = cn.innerClasses.stream().filter(ic -> cn.name.equals(ic.outerName)).map(ic -> ic.innerName).toList();
            if (!nested.isEmpty()) sb.append("\nNested types: ").append(String.join(", ", nested)).append("\n");
        }
        return sb.toString();
    }

    // ---- disassemble ----------------------------------------------------------------------------

    public static String disassemble(ClassRepository repo, ClassNode cn, List<MethodNode> methods, int maxChars) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (methods == null) {
            byte[] bytes = repo.entry(cn.name).orElseThrow().bytes();
            new ClassReader(bytes).accept(new TraceClassVisitor(pw), ClassReader.SKIP_FRAMES);
        } else {
            for (MethodNode mn : methods) {
                Textifier t = new Textifier();
                TraceMethodVisitor tmv = new TraceMethodVisitor(t);
                mn.accept(tmv);
                pw.println("// " + ClassInspector.access(mn.access) + " " + mn.name + mn.desc
                        + "   maxStack=" + mn.maxStack + " maxLocals=" + mn.maxLocals + " complexity=" + ClassInspector.complexity(mn));
                t.print(pw);
                pw.println();
            }
        }
        pw.flush();
        String s = sw.toString();
        if (s.length() > maxChars) s = s.substring(0, maxChars) + "\n... [truncated at " + maxChars + " chars; ask for a single method]";
        return "```\n" + s + "\n```";
    }

    // ---- call_graph -----------------------------------------------------------------------------

    public static String callGraph(ClassRepository repo, ClassNode start, String method, int depth, String direction, boolean includeJdk) {
        CallGraph g = new CallGraph(repo, includeJdk);
        List<String> roots = new ArrayList<>();
        for (MethodNode mn : start.methods) {
            if (ClassInspector.isSynthetic(mn) && !mn.name.startsWith("lambda$")) continue;
            if (method == null || method.isBlank() || mn.name.equals(method)) roots.add(CallGraph.key(start.name, mn.name, mn.desc));
        }
        if (roots.isEmpty()) throw new IllegalArgumentException("No method '" + method + "' in " + Names.dotted(start.name));
        Set<CallGraph.Edge> edges = "in".equalsIgnoreCase(direction) ? g.callers(roots, depth) : g.callees(roots, depth);
        return g.render(roots, edges, direction);
    }

    static String methodLabel(String key) {
        // owner#name#desc
        String[] p = key.split("#", 3);
        return Names.simple(p[0]) + "." + p[1] + "(" + String.join(",", Names.paramSimpleNames(p[2])) + ")";
    }

    static Type returnType(String key) {
        return Type.getReturnType(key.split("#", 3)[2]);
    }
}
