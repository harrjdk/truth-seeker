package dev.truthseeker.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mutation testing over the generated tests: each class that has passing {@code XGeneratedTest} tests gets small
 * bytecode changes ({@link Mutator}), one at a time, and its tests are rerun against each in a fresh JVM. A change no
 * test notices ("survives") marks behaviour the tests do not pin down.
 */
final class MutationTesting {
    private MutationTesting() {}

    /** A change that no test noticed. */
    record Survivor(String className, String method, int line, String description) {}

    record ClassScore(String className, int mutants, int killed) {}

    record Report(int mutants, int killed, int survived, int timedOut, int invalid, int skippedOverCap,
                  List<ClassScore> classes, List<Survivor> survivors) {
        int score() {
            int decided = killed + survived;
            return decided == 0 ? 0 : Math.round(100f * killed / decided);
        }
    }

    /** Launches one child JVM running the selected test classes; true when it timed out. */
    interface Launcher {
        boolean run(List<String> classpathFront, List<String> testClasses, Path results, Path log, int timeoutSeconds)
                throws IOException, InterruptedException;
    }

    /**
     * @param testClasses compiled test classes root (to find {@code *GeneratedTest} classes)
     * @param passed      tests that passed in the normal run, as {@code fully.qualified.TestClass#method}
     * @param appEntries  the application's classpath entries (directories and jars), where the subjects' bytes live
     */
    static Report run(Path testClasses, Set<String> passed, List<Path> appEntries, int maxMutants, int timeoutSeconds,
                      Path work, Launcher launcher) throws IOException, InterruptedException {
        // subject → its generated test class, with the subject's methods that have a passing test
        Map<String, String> testOf = new TreeMap<>();
        Map<String, Set<String>> testedMethods = new HashMap<>();
        for (String key : passed) {
            String testClass = key.substring(0, key.indexOf('#'));
            String method = key.substring(key.indexOf('#') + 1);
            if (!testClass.endsWith("GeneratedTest")) continue;
            String subject = testClass.substring(0, testClass.length() - "GeneratedTest".length());
            testOf.put(subject, testClass);
            int us = method.indexOf('_');
            testedMethods.computeIfAbsent(subject, k -> new HashSet<>()).add(us > 0 ? method.substring(0, us) : method);
        }
        // mutants per class, only in methods some passing test calls (generated test names start with the method name)
        Map<String, List<Mutator.Mutant>> perClass = new LinkedHashMap<>();
        for (String subject : testOf.keySet()) {
            byte[] bytes = classBytes(appEntries, subject.replace('.', '/') + ".class");
            if (bytes == null) continue;
            Set<String> tested = testedMethods.get(subject);
            List<Mutator.Mutant> ms = new ArrayList<>();
            for (Mutator.Mutant m : Mutator.mutants(bytes)) if (tested.contains(sourceMethod(m.method()))) ms.add(m);
            if (!ms.isEmpty()) perClass.put(subject, ms);
        }
        // spread the cap over classes, round robin
        List<Mutator.Mutant> chosen = new ArrayList<>();
        int total = perClass.values().stream().mapToInt(List::size).sum();
        for (int i = 0; chosen.size() < maxMutants; i++) {
            boolean any = false;
            for (List<Mutator.Mutant> ms : perClass.values()) {
                if (i < ms.size() && chosen.size() < maxMutants) {
                    chosen.add(ms.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }

        int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int k = 0; k < chosen.size(); k++) {
            Mutator.Mutant m = chosen.get(k);
            Path dir = work.resolve("mutant-" + k);
            outcomes.add(pool.submit(() -> runOne(m, dir, testOf.get(m.className().replace('/', '.')), passed, timeoutSeconds, launcher)));
        }
        pool.shutdown();

        int killed = 0, survived = 0, timedOut = 0, invalid = 0;
        Map<String, int[]> scores = new TreeMap<>();
        List<Survivor> survivors = new ArrayList<>();
        for (int k = 0; k < chosen.size(); k++) {
            Mutator.Mutant m = chosen.get(k);
            String outcome;
            try {
                outcome = outcomes.get(k).get();
            } catch (ExecutionException e) {
                outcome = "invalid";
            }
            String cls = m.className().replace('/', '.');
            int[] s = scores.computeIfAbsent(cls, x -> new int[2]);
            switch (outcome) {
                case "killed" -> { killed++; s[0]++; s[1]++; }
                case "timeout" -> { killed++; timedOut++; s[0]++; s[1]++; }
                case "survived" -> { survived++; s[0]++; survivors.add(new Survivor(cls, sourceMethod(m.method()), m.line(), m.description())); }
                default -> invalid++;
            }
        }
        List<ClassScore> classes = new ArrayList<>();
        scores.forEach((c, s) -> classes.add(new ClassScore(c, s[0], s[1])));
        return new Report(killed + survived + invalid, killed, survived, timedOut, invalid, total - chosen.size(), classes, survivors);
    }

    /** killed / survived / timeout / invalid. */
    private static String runOne(Mutator.Mutant m, Path dir, String testClass, Set<String> passed, int timeoutSeconds, Launcher launcher)
            throws IOException, InterruptedException {
        Path classFile = dir.resolve("classes").resolve(m.className() + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, m.bytes());
        Path results = dir.resolve("results.tsv"), log = dir.resolve("child.log");
        boolean timedOut = launcher.run(List.of(dir.resolve("classes").toString()), List.of(testClass), results, log, timeoutSeconds);
        if (timedOut) return "timeout"; // e.g. a negated loop condition that never ends: noticed
        boolean anyResult = false;
        if (Files.exists(results)) {
            for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 3) continue;
                anyResult = true;
                if ((f[0].equals("FAIL") || f[0].equals("CFAIL")) && (f[0].equals("CFAIL") || passed.contains(f[1] + "#" + f[2]))) {
                    if (f.length > 3 && f[3].contains("VerifyError")) return "invalid";
                    return "killed";
                }
            }
        }
        if (!anyResult) {
            String out = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
            return out.contains("VerifyError") || out.contains("ClassFormatError") ? "invalid" : "killed";
        }
        return "survived";
    }

    /** {@code lambda$getSubTotal$0} belongs to {@code getSubTotal}. */
    static String sourceMethod(String name) {
        if (name.startsWith("lambda$")) {
            String rest = name.substring("lambda$".length());
            int d = rest.indexOf('$');
            return d > 0 ? rest.substring(0, d) : rest;
        }
        return name;
    }

    static byte[] classBytes(List<Path> entries, String resource) throws IOException {
        for (Path e : entries) {
            if (Files.isDirectory(e)) {
                Path f = e.resolve(resource);
                if (Files.isRegularFile(f)) return Files.readAllBytes(f);
            } else if (Files.isRegularFile(e) && e.toString().endsWith(".jar")) {
                try (ZipFile z = new ZipFile(e.toFile())) {
                    ZipEntry ze = z.getEntry(resource);
                    if (ze != null) {
                        try (InputStream in = z.getInputStream(ze)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        return null;
    }
}
