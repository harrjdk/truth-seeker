package dev.truthseeker.symbolic;

/**
 * One decision taken along a path. {@link #key()} identifies the decision (a truth-table column);
 * {@link #value()} is the outcome taken ("T"/"F" for boolean decisions, a case label for switches).
 */
public record Cond(Kind kind, Expr left, String op, Expr right, String value, Expr valueExpr, java.util.List<Expr> otherCases) {

    public enum Kind { BOOL, CMP, SWITCH }

    public static Cond bool(Expr e, boolean v) {
        return new Cond(Kind.BOOL, e, null, null, v ? "T" : "F", null, java.util.List.of());
    }

    public static Cond cmp(Expr l, String op, Expr r, boolean v) {
        return new Cond(Kind.CMP, l, op, r, v ? "T" : "F", null, java.util.List.of());
    }

    /** @param otherCases for the default branch: the constants of the explicit cases (it matches none of them) */
    public static Cond switchCase(Expr selector, String label, Expr labelExpr, java.util.List<Expr> otherCases) {
        return new Cond(Kind.SWITCH, selector, null, null, label, labelExpr, otherCases);
    }

    public String key() {
        return switch (kind) {
            case BOOL -> left.text;
            case CMP -> left.text + " " + op + " " + right.text;
            case SWITCH -> "switch (" + left.text + ")";
        };
    }

    public boolean isTrue() {
        return "T".equals(value);
    }

    /** Human readable form of the decision as taken, e.g. {@code amount <= 1000} or {@code !valid}. */
    public String describe() {
        return switch (kind) {
            case BOOL -> isTrue() ? left.text : "!" + left.paren();
            case CMP -> isTrue() ? key() : left.text + " " + negate(op) + " " + right.text;
            case SWITCH -> value.equals("default") ? left.text + " matches no case"
                    : value.startsWith("instanceof ") ? left.text + " " + value
                    : value.contains(" | ") ? left.text + " in {" + value.replace(" | ", ", ").replace(", default", "") + "}"
                    : left.text + " == " + value;
        };
    }

    public static String negate(String op) {
        return switch (op) {
            case "==" -> "!=";
            case "!=" -> "==";
            case "<" -> ">=";
            case ">=" -> "<";
            case ">" -> "<=";
            case "<=" -> ">";
            default -> "!" + op;
        };
    }
}
