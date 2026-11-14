package dev.truthseeker.testgen;

import java.util.*;
import java.util.stream.Collectors;

/** Compact, token-friendly summaries of generated test suites. */
public final class SuiteReport {
    private SuiteReport() {}

    /** Summary of one or more generated suites: counts, disabled tests grouped by cause, notes. */
    public static String markdown(List<TestGenerator.Suite> suites, List<String> writtenTo) {
        StringBuilder sb = new StringBuilder("# Generated tests\n\n");
        int tests = suites.stream().mapToInt(TestGenerator.Suite::tests).sum();
        int disabled = suites.stream().mapToInt(s -> s.disabled().size()).sum();
        sb.append("- Test classes: ").append(suites.size()).append(", tests: ").append(tests)
                .append(" (").append(tests - disabled).append(" active, ").append(disabled).append(" @Disabled with TODOs)\n");
        for (String w : writtenTo) sb.append("- ").append(w).append("\n");
        long privateSkips = suites.stream().flatMap(s -> s.skipped().stream()).filter(x -> x.contains("(private")).count();
        if (privateSkips > 0) sb.append("- Private methods not tested directly (covered through their callers): ").append(privateSkips).append("\n");
        for (TestGenerator.Suite s : suites) {
            List<String> other = s.skipped().stream().filter(x -> !x.contains("(private")).toList();
            if (!other.isEmpty()) sb.append("- Skipped in `").append(simple(s.className())).append("`: ").append(String.join("; ", other)).append("\n");
        }

        List<TestGenerator.Issue> allDisabled = suites.stream().flatMap(s -> s.disabled().stream()).toList();
        if (!allDisabled.isEmpty()) {
            sb.append("\n## Disabled: unresolved preconditions (grouped)\n\n");
            appendGrouped(sb, allDisabled, 10);
            sb.append("\nArrange these by hand (or extend the code under test's seams), then remove `@Disabled`.\n");
        }
        List<TestGenerator.Issue> notes = suites.stream().flatMap(s -> s.notes().stream()).toList();
        if (!notes.isEmpty()) {
            sb.append("\n## Notes (paths depending on calls on the subject that were not inlined)\n\n");
            appendGrouped(sb, notes, 5);
        }
        sb.append("\nNext: run `verify_tests` on the output directory to compile and execute these tests against the archive.\n");
        return sb.toString();
    }

    /** Groups issues by normalized detail text: "25× arrange so that `x.hasNext()` = <v> — tests a, b, c…". */
    static void appendGrouped(StringBuilder sb, List<TestGenerator.Issue> issues, int limit) {
        Map<String, List<String>> groups = group(issues);
        int shown = 0;
        for (var e : groups.entrySet()) {
            if (shown++ >= limit) {
                sb.append("- … ").append(groups.size() - limit).append(" more kinds\n");
                break;
            }
            List<String> tests = e.getValue().stream().distinct().toList();
            sb.append("- ").append(tests.size()).append("× ").append(e.getKey()).append(" — ")
                    .append(tests.stream().limit(4).collect(Collectors.joining(", ")))
                    .append(tests.size() > 4 ? ", …" : "").append("\n");
        }
    }

    /** detail (normalized) → tests, most frequent first. */
    public static Map<String, List<String>> group(List<TestGenerator.Issue> issues) {
        Map<String, List<String>> groups = new HashMap<>();
        for (TestGenerator.Issue i : issues) {
            for (String d : i.details()) groups.computeIfAbsent(normalize(d), k -> new ArrayList<>()).add(i.test());
        }
        return groups.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** Strips per-test specifics so similar issues group together. */
    public static String normalize(String detail) {
        return detail.trim()
                .replaceAll("= (true|false|null|\"[^\"]*\"|-?[0-9.]+L?)$", "= <value>")
                .replaceAll(" is (T|F)$", "")
                .replaceAll(" \\[iteration [0-9]+]", " [later iteration]");
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }
}
