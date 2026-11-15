package dev.truthseeker.symbolic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One feasible (as far as we can tell) execution path through a method.
 *
 * @param dereferenced collaborator results this path calls methods on or reads fields of (inside inlined code too):
 *                     a test reaching the path must make them non-null
 * @param returnedFields when the path returns an object it built itself with every constructor followed
 *                       ({@code return new Delivery(..)}): the values its fields hold, by field name; fields absent
 *                       from the map keep their default. Null otherwise.
 * @param handedFields   the same for objects the path built with every constructor followed and handed to a call
 *                       ({@code repository.save(new Visit(..))}, {@code itemMap.put(id, cartItem)}), by object identity
 */
public record Path(int id, List<Cond> conds, List<Effect> effects, Outcome outcome, List<Expr> dereferenced,
                   Map<String, Expr> returnedFields, Map<Object, Map<String, Expr>> handedFields) {

    public Path(int id, List<Cond> conds, List<Effect> effects, Outcome outcome) {
        this(id, conds, effects, outcome, List.of(), null, Map.of());
    }

    public Path(int id, List<Cond> conds, List<Effect> effects, Outcome outcome, List<Expr> dereferenced) {
        this(id, conds, effects, outcome, dereferenced, null, Map.of());
    }

    /** Field values of an object this path built and passed on (to a collaborator, into a collection), or null. */
    public Map<String, Expr> fieldsOf(Expr built) {
        return built == null || built.extra == null ? null : handedFields.get(built.extra);
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
