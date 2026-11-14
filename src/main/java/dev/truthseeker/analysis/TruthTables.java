package dev.truthseeker.analysis;

import dev.truthseeker.archive.Names;
import dev.truthseeker.symbolic.Cond;
import dev.truthseeker.symbolic.Effect;
import dev.truthseeker.symbolic.Path;
import dev.truthseeker.symbolic.PathExplorer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns explored paths into a decision table (one row per path, "-" = don't care), an optional fully
 * expanded truth table, and a recovered boolean formula per outcome.
 */
public final class TruthTables {
    private TruthTables() {}

    public static String markdown(PathExplorer.Result r, boolean expand) {
        return markdown(r, expand, true);
    }

    /**
     * @param full true: one decision-table row per path. false (summary): one row per outcome with path counts and
     *             side effects listed once — usually all an LLM needs, at a fraction of the tokens.
     */
    public static String markdown(PathExplorer.Result r, boolean expand, boolean full) {
        StringBuilder sb = new StringBuilder();
        String sig = Names.methodSignature(r.method().name, r.method().desc);
        List<String> cols = columns(r.paths());
        long outcomes = r.paths().stream().map(p -> p.outcome().describe()).distinct().count();
        sb.append("## Truth table: `").append(Names.simple(r.owner())).append(".").append(sig.substring(sig.indexOf(' ') + 1)).append("`\n\n");
        sb.append(r.paths().size()).append(" paths").append(r.truncated() ? " (limit reached)" : "").append(", ")
                .append(cols.size()).append(" conditions, ").append(outcomes).append(" distinct outcomes\n\n");
        if (r.paths().isEmpty()) {
            sb.append("_No paths explored._\n");
            appendNotes(sb, r);
            return sb.toString();
        }
        if (cols.isEmpty()) {
            sb.append("_Straight-line method: no decisions._ Outcome: `").append(r.paths().get(0).outcome().describe()).append("`\n");
            appendEffects(sb, r.paths().get(0));
            appendNotes(sb, r);
            return sb.toString();
        }
        sb.append("**Conditions**\n\n");
        for (int i = 0; i < cols.size(); i++) sb.append("- **C").append(i + 1).append("**: `").append(cols.get(i)).append("`\n");
        Map<String, String> aliases = aliases(r.paths());
        if (!aliases.isEmpty()) {
            sb.append("\nwhere (locals from debug info):\n\n");
            aliases.forEach((k, v) -> sb.append("- `").append(k).append("` = `").append(v).append("`\n"));
        }
        if (full) {
            sb.append("\n**Decision table** (one row per feasible path; `-` = not evaluated on that path)\n\n|Path|");
            for (int i = 0; i < cols.size(); i++) sb.append("C").append(i + 1).append("|");
            sb.append("Outcome|Side effects|\n|---|");
            sb.append("---|".repeat(cols.size())).append("---|---|\n");
            for (Path p : r.paths()) {
                sb.append("|P").append(p.id()).append("|");
                for (String c : cols) sb.append(escape(p.cond(c).map(Cond::value).orElse("-"))).append("|");
                sb.append("`").append(escape(p.outcome().describe())).append("`|").append(escape(effectSummary(p))).append("|\n");
            }
        } else {
            appendOutcomeSummary(sb, r);
        }

        if (expand) appendExpanded(sb, r, cols);
        appendFormulas(sb, r, cols);
        appendNotes(sb, r);
        if (!full) sb.append("\n_Pass `detail: \"full\"` for the per-path decision table._\n");
        return sb.toString();
    }

    /** One row per outcome: which paths lead there and the side effects, each listed once. */
    private static void appendOutcomeSummary(StringBuilder sb, PathExplorer.Result r) {
        Map<String, List<Path>> byOutcome = new LinkedHashMap<>();
        for (Path p : r.paths()) byOutcome.computeIfAbsent(p.outcome().describe(), k -> new ArrayList<>()).add(p);
        sb.append("\n**Outcomes**\n\n| Outcome | Paths | Side effects (all paths / *some paths*) |\n|---|---|---|\n");
        for (var e : byOutcome.entrySet()) {
            List<Path> ps = e.getValue();
            List<Set<String>> perPath = ps.stream()
                    .map(p -> (Set<String>) p.effects().stream().map(Effect::text).collect(Collectors.toCollection(LinkedHashSet::new)))
                    .toList();
            LinkedHashSet<String> union = new LinkedHashSet<>();
            perPath.forEach(union::addAll);
            List<String> cells = new ArrayList<>();
            for (String ef : union) {
                String t = ef.length() > 90 ? ef.substring(0, 87) + "..." : ef;
                boolean always = perPath.stream().allMatch(s -> s.contains(ef));
                cells.add(always ? "`" + t + "`" : "*`" + t + "`*");
            }
            String ids = ps.size() <= 6 ? ps.stream().map(p -> "P" + p.id()).collect(Collectors.joining(", ")) : ps.size() + " paths";
            sb.append("| `").append(escape(e.getKey())).append("` | ").append(ids).append(" | ").append(escape(String.join("; ", cells))).append(" |\n");
        }
    }

    /** Local-variable aliases appearing in conditions or outcomes, with the expression they stand for. */
    static Map<String, String> aliases(List<Path> paths) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.function.Predicate<dev.truthseeker.symbolic.Expr> collect = x -> {
            if (x.expansion() != null) out.putIfAbsent(x.text, x.expansion());
            return false;
        };
        for (Path p : paths) {
            for (Cond c : p.conds()) {
                c.left().mentions(collect);
                if (c.right() != null) c.right().mentions(collect);
            }
            if (p.outcome().value() != null) p.outcome().value().mentions(collect);
        }
        return out;
    }

    public static List<String> columns(List<Path> paths) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (Path p : paths) for (Cond c : p.conds()) cols.add(c.key());
        return new ArrayList<>(cols);
    }

    private static void appendExpanded(StringBuilder sb, PathExplorer.Result r, List<String> cols) {
        boolean binary = r.paths().stream().flatMap(p -> p.conds().stream()).allMatch(c -> c.kind() != Cond.Kind.SWITCH);
        if (!binary) {
            sb.append("\n_Expanded table skipped: method contains switch decisions (see decision table)._\n");
            return;
        }
        if (cols.size() > 10) {
            sb.append("\n_Expanded table skipped: ").append(cols.size()).append(" conditions would produce ")
                    .append(1L << Math.min(cols.size(), 62)).append(" rows._\n");
            return;
        }
        sb.append("\n**Expanded truth table** (all 2^").append(cols.size()).append(" combinations)\n\n|");
        for (int i = 0; i < cols.size(); i++) sb.append("C").append(i + 1).append("|");
        sb.append("Outcome|Path|\n|").append("---|".repeat(cols.size() + 2)).append("\n");
        int n = cols.size();
        for (int mask = 0; mask < (1 << n); mask++) {
            Map<String, String> assign = new HashMap<>();
            sb.append("|");
            for (int i = 0; i < n; i++) {
                String v = ((mask >> (n - 1 - i)) & 1) == 1 ? "T" : "F";
                assign.put(cols.get(i), v);
                sb.append(v).append("|");
            }
            Path match = null;
            for (Path p : r.paths()) {
                if (p.conds().stream().allMatch(c -> c.value().equals(assign.get(c.key())))) {
                    match = p;
                    break;
                }
            }
            if (match == null) sb.append("_infeasible / unexplored_|-|\n");
            else sb.append("`").append(escape(match.outcome().describe())).append("`|P").append(match.id()).append("|\n");
        }
    }

    /** For each distinct outcome, the disjunction of path conditions that lead to it. */
    private static void appendFormulas(StringBuilder sb, PathExplorer.Result r, List<String> cols) {
        Map<String, List<Path>> byOutcome = new LinkedHashMap<>();
        for (Path p : r.paths()) byOutcome.computeIfAbsent(p.outcome().describe(), k -> new ArrayList<>()).add(p);
        sb.append("\n**Recovered decision logic** (simplified, by outcome)\n\n");
        if (byOutcome.size() == 1) {
            sb.append("- `").append(byOutcome.keySet().iterator().next()).append("` on every path — the decisions only affect side effects.\n");
            return;
        }
        for (var e : byOutcome.entrySet()) {
            sb.append("- `").append(e.getKey()).append("` when:\n  ```java\n  ").append(formula(e.getValue())).append("\n  ```\n");
        }
    }

    static String formula(List<Path> paths) {
        List<Simplifier.Term> terms = Simplifier.simplify(paths.stream().map(Path::conds).toList());
        if (terms.stream().anyMatch(t -> t.lits().isEmpty())) return "always";
        return terms.stream()
                .map(t -> t.lits().values().stream().map(Cond::describe).collect(Collectors.joining(" && ")))
                .map(s -> terms.size() > 1 && s.contains("&&") ? "(" + s + ")" : s)
                .collect(Collectors.joining("\n    || "));
    }

    private static void appendEffects(StringBuilder sb, Path p) {
        String s = effectSummary(p);
        if (!s.isEmpty()) sb.append("\nSide effects: ").append(s).append("\n");
    }

    private static void appendNotes(StringBuilder sb, PathExplorer.Result r) {
        if (r.notes().isEmpty()) return;
        sb.append("\n**Analysis notes**\n\n");
        for (String n : r.notes()) sb.append("- ").append(n).append("\n");
    }

    static String effectSummary(Path p) {
        List<String> parts = new ArrayList<>();
        for (Effect e : p.effects()) {
            String t = e.text();
            if (t.length() > 90) t = t.substring(0, 87) + "...";
            if (!parts.contains(t)) parts.add(t);
        }
        if (parts.size() > 8) {
            int extra = parts.size() - 8;
            parts = new ArrayList<>(parts.subList(0, 8));
            parts.add("… +" + extra);
        }
        return parts.stream().map(s -> "`" + s + "`").collect(Collectors.joining("; "));
    }

    private static String escape(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    // ---- JSON-friendly structure ----------------------------------------------------------------

    public static Map<String, Object> structured(PathExplorer.Result r) {
        List<String> cols = columns(r.paths());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", Names.dotted(r.owner()));
        m.put("method", r.method().name);
        m.put("descriptor", r.method().desc);
        m.put("conditions", cols);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path p : r.paths()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", p.id());
            Map<String, String> vals = new LinkedHashMap<>();
            for (Cond c : p.conds()) vals.put(c.key(), c.value());
            row.put("values", vals);
            row.put("outcome", p.outcome().describe());
            row.put("outcomeKind", p.outcome().kind().name());
            row.put("effects", p.effects().stream().map(Effect::text).toList());
            rows.add(row);
        }
        m.put("rows", rows);
        m.put("truncated", r.truncated());
        m.put("notes", r.notes());
        return m;
    }
}
