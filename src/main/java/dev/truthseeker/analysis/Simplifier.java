package dev.truthseeker.analysis;

import dev.truthseeker.symbolic.Cond;
import dev.truthseeker.symbolic.Expr;

import java.util.*;

/**
 * Simplifies a disjunction of path conditions (each path = conjunction of decisions) into a shorter,
 * equivalent formula. Rules, applied to a fixpoint:
 * <ul>
 *   <li>adjacency: {@code (A && x) || (A && !x)  →  A}</li>
 *   <li>reduction: {@code A || (B && !a)}, where every literal of A except {@code a} is in B → drop {@code !a} from the second term</li>
 *   <li>absorption: {@code A || (A && B)  →  A}</li>
 *   <li>numeric implication inside a term: {@code x <= 10 && x <= 100  →  x <= 10}</li>
 * </ul>
 * Switch decisions are kept as opaque literals.
 */
final class Simplifier {
    private Simplifier() {}

    /** A term maps decision key → value ("T"/"F"/switch label); the Cond is kept for rendering. */
    record Term(LinkedHashMap<String, Cond> lits) {
        boolean binary(String key) {
            return lits.get(key).kind() != Cond.Kind.SWITCH;
        }
    }

    static List<Term> simplify(List<List<Cond>> paths) {
        List<Term> terms = new ArrayList<>();
        for (List<Cond> p : paths) {
            LinkedHashMap<String, Cond> m = new LinkedHashMap<>();
            for (Cond c : p) m.put(c.key(), c);
            terms.add(new Term(m));
        }
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 200) {
            changed = false;
            for (Term t : terms) changed |= removeImplied(t);
            changed |= dedupe(terms);
            // adjacency
            outer:
            for (int i = 0; i < terms.size(); i++) {
                for (int j = i + 1; j < terms.size(); j++) {
                    String k = differsInOneBinary(terms.get(i), terms.get(j));
                    if (k != null) {
                        LinkedHashMap<String, Cond> m = new LinkedHashMap<>(terms.get(i).lits());
                        m.remove(k);
                        terms.set(i, new Term(m));
                        terms.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
            // reduction
            for (Term a : terms) {
                for (int j = 0; j < terms.size(); j++) {
                    Term b = terms.get(j);
                    if (a == b) continue;
                    String drop = reducible(a, b);
                    if (drop != null) {
                        LinkedHashMap<String, Cond> m = new LinkedHashMap<>(b.lits());
                        m.remove(drop);
                        terms.set(j, new Term(m));
                        changed = true;
                    }
                }
            }
            // absorption
            for (int i = 0; i < terms.size(); i++) {
                for (int j = 0; j < terms.size(); j++) {
                    if (i != j && i < terms.size() && j < terms.size() && subsumes(terms.get(i), terms.get(j))) {
                        terms.remove(j);
                        changed = true;
                        j--;
                    }
                }
            }
        }
        return terms;
    }

    private static boolean dedupe(List<Term> terms) {
        Set<Map<String, String>> seen = new HashSet<>();
        boolean changed = false;
        for (Iterator<Term> it = terms.iterator(); it.hasNext(); ) {
            if (!seen.add(values(it.next()))) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static Map<String, String> values(Term t) {
        Map<String, String> m = new HashMap<>();
        t.lits().forEach((k, c) -> m.put(k, c.value()));
        return m;
    }

    private static String differsInOneBinary(Term a, Term b) {
        if (!a.lits().keySet().equals(b.lits().keySet())) return null;
        String diff = null;
        for (String k : a.lits().keySet()) {
            if (a.lits().get(k).value().equals(b.lits().get(k).value())) continue;
            if (diff != null || !a.binary(k)) return null;
            diff = k;
        }
        return diff;
    }

    /** If a = A' ∪ {L} and b ⊇ A' ∪ {¬L}, returns L's key (to drop ¬L from b). */
    private static String reducible(Term a, Term b) {
        String candidate = null;
        for (var e : a.lits().entrySet()) {
            Cond inB = b.lits().get(e.getKey());
            if (inB == null) return null;
            if (inB.value().equals(e.getValue().value())) continue;
            if (candidate != null || !a.binary(e.getKey())) return null;
            candidate = e.getKey();
        }
        return candidate;
    }

    /** a subsumes b when every literal of a appears identically in b (b is redundant). */
    private static boolean subsumes(Term a, Term b) {
        if (a.lits().size() > b.lits().size()) return false;
        for (var e : a.lits().entrySet()) {
            Cond inB = b.lits().get(e.getKey());
            if (inB == null || !inB.value().equals(e.getValue().value())) return false;
        }
        return true;
    }

    // ---- numeric implication -----------------------------------------------------------------

    private record Bound(String left, String op, double c) {}

    private static Bound bound(Cond c) {
        if (c.kind() != Cond.Kind.CMP || c.right() == null || c.right().kind != Expr.Kind.CONST || !(c.right().value instanceof Number n)) return null;
        String op = c.isTrue() ? c.op() : Cond.negate(c.op());
        return new Bound(c.left().text, op, n.doubleValue());
    }

    private static boolean implies(Bound s, Bound w) {
        if (!s.left().equals(w.left())) return false;
        double a = s.c(), b = w.c();
        return switch (s.op()) {
            case "==" -> switch (w.op()) {
                case "==" -> a == b; case "!=" -> a != b; case "<" -> a < b; case "<=" -> a <= b; case ">" -> a > b; default -> a >= b;
            };
            case "<" -> (w.op().equals("<") || w.op().equals("<=")) && a <= b || w.op().equals("!=") && a <= b;
            case "<=" -> w.op().equals("<=") && a <= b || w.op().equals("<") && a < b || w.op().equals("!=") && a < b;
            case ">" -> (w.op().equals(">") || w.op().equals(">=")) && a >= b || w.op().equals("!=") && a >= b;
            case ">=" -> w.op().equals(">=") && a >= b || w.op().equals(">") && a > b || w.op().equals("!=") && a > b;
            default -> false;
        };
    }

    private static boolean removeImplied(Term t) {
        List<String> keys = new ArrayList<>(t.lits().keySet());
        for (String weakKey : keys) {
            Bound w = bound(t.lits().get(weakKey));
            if (w == null) continue;
            for (String strongKey : keys) {
                if (strongKey.equals(weakKey) || !t.lits().containsKey(strongKey)) continue;
                Bound s = bound(t.lits().get(strongKey));
                if (s != null && implies(s, w) && !(implies(w, s) && keys.indexOf(strongKey) > keys.indexOf(weakKey))) {
                    t.lits().remove(weakKey);
                    return true;
                }
            }
        }
        return false;
    }
}
