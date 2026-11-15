package dev.truthseeker.verify;

import dev.truthseeker.testgen.SuiteReport;
import dev.truthseeker.testgen.TestGenerator;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles test sources against an application archive and runs them in a separate JVM, then reports
 * results grouped by cause — the feedback loop that makes generated tests trustworthy.
 *
 * <p>Note: this executes the application's code (the tests call it), in a child JVM.
 */
public final class TestVerifier {

    public record Options(String archive, String testDir, String extraClasspath, int timeoutSeconds) {}

    public record Failure(String test, String cause, String at) {}

    public record Result(int compiledFiles, List<String> notCompiled, Map<String, List<String>> compileErrors, int total,
                         int passed, int failed, int disabled, List<Failure> failures, List<TestGenerator.Issue> disabledTests,
                         List<String> containerFailures, List<String> notes, boolean timedOut) {}

    private TestVerifier() {}

    public static Result verify(Options opt) throws IOException, InterruptedException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalArgumentException("verify_tests needs a JDK (no Java compiler in " + System.getProperty("java.home")
                    + "). Run truth-seeker with a JDK 17+ 'java'.");
        }
        Path testDir = Path.of(opt.testDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(testDir)) throw new IllegalArgumentException("testDir is not a directory: " + testDir);
        List<Path> sources;
        try (Stream<Path> s = Files.walk(testDir)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("No .java files under " + testDir);

        List<String> notes = new ArrayList<>();
        AppClasspath.Resolved app = AppClasspath.resolve(opt.archive());
        notes.addAll(app.notes());
        List<Path> kit = TestKit.jars();
        List<String> cp = new ArrayList<>();
        kit.forEach(p -> cp.add(p.toString()));
        app.entries().forEach(p -> cp.add(p.toString()));
        if (opt.extraClasspath() != null && !opt.extraClasspath().isBlank()) {
            for (String e : opt.extraClasspath().split(File.pathSeparator)) if (!e.isBlank()) cp.add(e.trim());
        }

        Path work = Files.createTempDirectory(Cache.root(), "verify-");
        Path classes = Files.createDirectories(work.resolve("test-classes"));

        // ---- compile: all at once; on errors, file by file so the good ones still run ----
        Map<String, List<String>> compileErrors = new LinkedHashMap<>();
        List<String> notCompiled = new ArrayList<>();
        List<String> errors = compile(javac, sources, classes, cp);
        Set<String> missingApis = ProvidedApis.missingPackages(errors);
        if (!missingApis.isEmpty()) {
            // `provided` container APIs are never inside a WAR/EAR: borrow them from the local Maven repository
            List<Path> apis = ProvidedApis.find(missingApis);
            if (!apis.isEmpty()) {
                apis.forEach(p -> cp.add(p.toString()));
                notes.add("Added `provided` API jar(s) from the local Maven repository for " + String.join(", ", missingApis) + ": "
                        + apis.stream().map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.joining(", ")) + ".");
                errors = compile(javac, sources, classes, cp);
            }
        }
        int compiled = sources.size();
        if (!errors.isEmpty()) {
            compiled = 0;
            for (Path src : sources) {
                List<String> errs = compile(javac, List.of(src), classes, cp);
                if (errs.isEmpty()) compiled++;
                else {
                    notCompiled.add(testDir.relativize(src).toString());
                    for (String e : errs) compileErrors.computeIfAbsent(normalizeCompileError(e), k -> new ArrayList<>()).add(src.getFileName().toString());
                }
            }
            long missing = compileErrors.keySet().stream().filter(k -> k.contains("does not exist") || k.contains("cannot access")
                    || k.contains("class file for")).count();
            if (missing > 0) {
                notes.add("Some types are missing from the classpath (often `provided` APIs such as jakarta.* / javax.* or container libraries). "
                        + "Pass them via extraClasspath, e.g. from `mvn dependency:build-classpath -Dmdep.includeScope=compile`.");
            }
        }

        // ---- run in a child JVM ----
        int total = 0, passed = 0, failed = 0, disabled = 0;
        List<Failure> failures = new ArrayList<>();
        List<String> containerFailures = new ArrayList<>();
        Map<String, String> skippedTests = new LinkedHashMap<>();
        boolean timedOut = false;
        if (compiled > 0) {
            Path runnerClasses = Files.createDirectories(work.resolve("runner"));
            Path runnerSrc = work.resolve("TruthSeekerTestRunner.java");
            Files.writeString(runnerSrc, RUNNER, StandardCharsets.UTF_8);
            List<String> runnerErrors = compile(javac, List.of(runnerSrc), runnerClasses, kit.stream().map(Path::toString).toList());
            if (!runnerErrors.isEmpty()) throw new IllegalStateException("Internal: test runner did not compile: " + runnerErrors);

            Path results = work.resolve("results.tsv");
            Path log = work.resolve("child.log");
            List<String> runCp = new ArrayList<>();
            runCp.add(classes.toString());
            runCp.add(runnerClasses.toString());
            runCp.addAll(cp);
            Path argFile = work.resolve("java.args");
            List<String> jvm = new ArrayList<>();
            jvm.add("-cp");
            jvm.add(quote(String.join(File.pathSeparator, runCp.stream().map(s -> s.replace('\\', '/')).toList())));
            jvm.add("-Dfile.encoding=UTF-8");
            jvm.add("-Xshare:off");
            if (Runtime.version().feature() >= 21) jvm.add("-XX:+EnableDynamicAgentLoading"); // Mockito's inline mock maker
            jvm.add("TruthSeekerTestRunner");
            jvm.add(quote(classes.toString().replace('\\', '/')));
            jvm.add(quote(results.toString().replace('\\', '/')));
            Files.write(argFile, jvm, StandardCharsets.UTF_8);

            String javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
            Process proc = new ProcessBuilder(javaExe, "@" + argFile).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!proc.waitFor(opt.timeoutSeconds(), TimeUnit.SECONDS)) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                timedOut = true;
                notes.add("Test run timed out after " + opt.timeoutSeconds() + "s (an infinite loop, or a test waiting on I/O). Partial results below.");
            }
            if (Files.exists(results)) {
                for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 5) continue;
                    String test = simple(f[1]) + "." + f[2];
                    switch (f[0]) {
                        case "PASS" -> { total++; passed++; }
                        case "FAIL" -> { total++; failed++; failures.add(new Failure(test, f[3], f[4])); }
                        case "SKIP" -> { total++; disabled++; skippedTests.put(test, f[3]); }
                        case "CFAIL" -> containerFailures.add(simple(f[1]) + ": " + f[3]);
                        default -> {}
                    }
                }
            } else if (!timedOut) {
                String tail = Files.exists(log) ? tail(Files.readString(log, StandardCharsets.UTF_8), 1500) : "";
                containerFailures.add("Test JVM produced no results (exit " + proc.exitValue() + "): " + tail);
            }
        }
        List<TestGenerator.Issue> disabledTests = disabledReasons(sources, skippedTests.keySet());
        return new Result(compiled, notCompiled, compileErrors, total, passed, failed, disabled, failures, disabledTests,
                containerFailures, notes, timedOut);
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> compile(JavaCompiler javac, List<Path> sources, Path out, List<String> cp) throws IOException {
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-d", out.toString(), "-cp", String.join(File.pathSeparator, cp),
                    "-proc:none", "-Xlint:none", "-nowarn", "-encoding", "UTF-8");
            Boolean ok = javac.getTask(new StringWriter(), fm, diags, options, null,
                    fm.getJavaFileObjectsFromPaths(sources)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d.getMessage(Locale.ROOT));
            }
            if (!Boolean.TRUE.equals(ok) && errors.isEmpty()) errors.add("compilation failed");
            return errors;
        }
    }

    private static String normalizeCompileError(String msg) {
        String first = msg.lines().findFirst().orElse(msg).trim();
        Matcher sym = Pattern.compile("symbol:\\s+(.*)").matcher(msg);
        if (first.startsWith("cannot find symbol") && sym.find()) first = "cannot find symbol: " + sym.group(1).trim();
        return first.length() > 160 ? first.substring(0, 157) + "..." : first;
    }

    /** TODO lines inside each disabled test method, read back from the generated sources. */
    private static List<TestGenerator.Issue> disabledReasons(List<Path> sources, Set<String> disabledMethods) throws IOException {
        List<TestGenerator.Issue> out = new ArrayList<>();
        if (disabledMethods.isEmpty()) return out;
        Pattern method = Pattern.compile("void (\\w+)\\(\\)");
        for (Path src : sources) {
            String cls = src.getFileName().toString().replaceFirst("\\.java$", "");
            String current = null;
            List<String> todos = new ArrayList<>();
            for (String line : Files.readAllLines(src, StandardCharsets.UTF_8)) {
                Matcher m = method.matcher(line);
                if (m.find() && line.contains("throws Exception")) {
                    if (current != null && disabledMethods.contains(current)) out.add(new TestGenerator.Issue(current, List.copyOf(todos)));
                    current = cls + "." + m.group(1);
                    todos.clear();
                } else if (current != null && line.trim().startsWith("// TODO: ") && !line.contains("assert result")) {
                    todos.add(line.trim().substring("// TODO: ".length()));
                }
            }
            if (current != null && disabledMethods.contains(current)) out.add(new TestGenerator.Issue(current, List.copyOf(todos)));
        }
        return out;
    }

    // ---- reporting ----------------------------------------------------------------------------

    public static String markdown(Options opt, Result r) {
        StringBuilder sb = new StringBuilder("# Test verification\n\n");
        int files = r.compiledFiles() + r.notCompiled().size();
        sb.append("- Sources: ").append(files).append(" file(s) in `").append(opt.testDir()).append("` — ")
                .append(r.compiledFiles()).append(" compiled").append(r.notCompiled().isEmpty() ? "" : ", " + r.notCompiled().size() + " did not").append("\n");
        sb.append("- Result: **").append(r.passed()).append(" passed**, ").append(r.failed()).append(" failed, ")
                .append(r.disabled()).append(" disabled (of ").append(r.total()).append(" tests)").append(r.timedOut() ? " — TIMED OUT" : "").append("\n");
        for (String n : r.notes()) sb.append("- ").append(n).append("\n");

        if (!r.notCompiled().isEmpty()) {
            sb.append("\n## Did not compile\n\n");
            for (var e : r.compileErrors().entrySet()) {
                List<String> fs = e.getValue().stream().distinct().toList();
                sb.append("- ").append(e.getValue().size()).append("× `").append(e.getKey()).append("` — ")
                        .append(String.join(", ", fs.subList(0, Math.min(4, fs.size())))).append(fs.size() > 4 ? ", …" : "").append("\n");
            }
        }
        if (!r.failures().isEmpty()) {
            sb.append("\n## Failures (grouped by cause)\n\n");
            Map<String, List<Failure>> byCause = new LinkedHashMap<>();
            for (Failure f : r.failures()) byCause.computeIfAbsent(normalizeCause(f.cause()), k -> new ArrayList<>()).add(f);
            byCause.entrySet().stream().sorted(Comparator.comparingInt(e -> -e.getValue().size())).forEach(e -> {
                List<Failure> fs = e.getValue();
                sb.append("- ").append(fs.size()).append("× `").append(e.getKey()).append("` — ")
                        .append(fs.stream().limit(4).map(Failure::test).collect(Collectors.joining(", "))).append(fs.size() > 4 ? ", …" : "").append("\n");
                if (!fs.get(0).at().isBlank()) sb.append("  - at `").append(fs.get(0).at()).append("`\n");
            });
        }
        if (!r.containerFailures().isEmpty()) {
            sb.append("\n## Class-level failures (setup / construction)\n\n");
            r.containerFailures().stream().distinct().limit(10).forEach(c -> sb.append("- ").append(c).append("\n"));
        }
        if (!r.disabledTests().isEmpty()) {
            sb.append("\n## Disabled: unresolved preconditions (grouped)\n\n");
            Map<String, List<String>> groups = SuiteReport.group(r.disabledTests());
            int shown = 0;
            for (var e : groups.entrySet()) {
                if (shown++ >= 10) {
                    sb.append("- … ").append(groups.size() - 10).append(" more kinds\n");
                    break;
                }
                List<String> ts = e.getValue().stream().distinct().toList();
                sb.append("- ").append(ts.size()).append("× ").append(e.getKey()).append("\n");
            }
        }
        if (r.failed() == 0 && r.notCompiled().isEmpty() && r.containerFailures().isEmpty()) {
            sb.append("\nAll active tests pass. They characterize current behaviour; review expected values before treating them as a spec.\n");
        }
        return sb.toString();
    }

    public static Map<String, Object> structured(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", r.total());
        m.put("passed", r.passed());
        m.put("failed", r.failed());
        m.put("disabled", r.disabled());
        m.put("compiledFiles", r.compiledFiles());
        m.put("notCompiled", r.notCompiled());
        m.put("timedOut", r.timedOut());
        m.put("failures", r.failures().stream().map(f -> Map.of("test", f.test(), "cause", f.cause(), "at", f.at())).toList());
        m.put("containerFailures", r.containerFailures());
        return m;
    }

    /** Groups failures that differ only in object identities / numbers. */
    static String normalizeCause(String cause) {
        String c = cause.replaceAll("hashCode: \\d+", "hashCode: #").replaceAll("@[0-9a-f]{4,}", "@#");
        return c.length() > 180 ? c.substring(0, 177) + "..." : c;
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /** Runs every test under a classes root on the JUnit Platform; writes one TSV line per result. Compiled at runtime. */
    private static final String RUNNER = """
            import org.junit.platform.engine.TestExecutionResult;
            import org.junit.platform.engine.TestSource;
            import org.junit.platform.engine.support.descriptor.ClassSource;
            import org.junit.platform.engine.support.descriptor.MethodSource;
            import org.junit.platform.launcher.Launcher;
            import org.junit.platform.launcher.LauncherDiscoveryRequest;
            import org.junit.platform.launcher.TestExecutionListener;
            import org.junit.platform.launcher.TestIdentifier;
            import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
            import org.junit.platform.launcher.core.LauncherFactory;

            import java.io.FileOutputStream;
            import java.io.PrintStream;
            import java.nio.file.Paths;
            import java.util.Collections;

            import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

            public class TruthSeekerTestRunner {
                public static void main(String[] args) throws Exception {
                    PrintStream out = new PrintStream(new FileOutputStream(args[1]), true, "UTF-8");
                    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                            .selectors(selectClasspathRoots(Collections.singleton(Paths.get(args[0])))).build();
                    Launcher launcher = LauncherFactory.create();
                    launcher.execute(request, new TestExecutionListener() {
                        @Override
                        public void executionSkipped(TestIdentifier id, String reason) {
                            line(out, id.isTest() ? "SKIP" : "SKIPC", id, reason, "");
                        }

                        @Override
                        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                            if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
                                if (id.isTest()) line(out, "PASS", id, "", "");
                                return;
                            }
                            Throwable t = result.getThrowable().orElse(null);
                            String cause = t == null ? result.getStatus().toString()
                                    : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                            String at = "";
                            if (t != null) {
                                for (StackTraceElement e : t.getStackTrace()) {
                                    String c = e.getClassName();
                                    if (c.startsWith("org.junit") || c.startsWith("org.mockito") || c.startsWith("org.opentest4j")
                                            || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")
                                            || c.endsWith("GeneratedTest") || c.contains("GeneratedTest$")) continue;
                                    at = e.toString();
                                    break;
                                }
                            }
                            line(out, id.isTest() ? "FAIL" : "CFAIL", id, cause, at);
                        }
                    });
                    out.close();
                }

                static void line(PrintStream out, String kind, TestIdentifier id, String a, String b) {
                    String cls = "";
                    String method = id.getDisplayName();
                    TestSource s = id.getSource().orElse(null);
                    if (s instanceof MethodSource) {
                        cls = ((MethodSource) s).getClassName();
                        method = ((MethodSource) s).getMethodName();
                    } else if (s instanceof ClassSource) {
                        cls = ((ClassSource) s).getClassName();
                    }
                    if (!id.isTest() && cls.isEmpty()) return; // engine roots
                    out.println(kind + "\\t" + esc(cls) + "\\t" + esc(method) + "\\t" + esc(a) + "\\t" + esc(b));
                }

                static String esc(String s) {
                    if (s == null) return "";
                    String r = s.replace('\\t', ' ').replace('\\r', ' ').replace('\\n', ' ');
                    return r.length() > 400 ? r.substring(0, 397) + "..." : r;
                }
            }
            """;
}
