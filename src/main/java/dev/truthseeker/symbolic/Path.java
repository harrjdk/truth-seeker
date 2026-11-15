package dev.truthseeker.symbolic;

import java.util.List;
import java.util.Optional;

/** One feasible (as far as we can tell) execution path through a method. */
/**
 * @param dereferenced collaborator results this path calls methods on or reads fields of (inside inlined code too):
 *                     a test reaching the path must make them non-null
 */
public record Path(int id, List<Cond> conds, List<Effect> effects, Outcome outcome, List<Expr> dereferenced) {

    public Path(int id, List<Cond> conds, List<Effect> effects, Outcome outcome) {
        this(id, conds, effects, outcome, List.of());
    }

    public enum OutcomeKind { RETURN, RETURN_VOID, THROW, TRUNCATED }

    public record Outcome(OutcomeKind kind, Expr value, String exceptionType, String note) {
        public String describe() {
            return switch (kind) {
                case RETURN -> "return " + value.text;
                case RETURN_VOID -> "return";
                case THROW -> "throw " + (value == null ? exceptionType : value.text);
                case TRUNCATED -> "… (" + note + ")";
            };
        }
    }

    public Optional<Cond> cond(String key) {
        return conds.stream().filter(c -> c.key().equals(key)).findFirst();
    }
}
