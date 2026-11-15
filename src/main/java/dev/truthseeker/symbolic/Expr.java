package dev.truthseeker.symbolic;

import dev.truthseeker.archive.Names;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A symbolic value on the operand stack or in a local variable, reconstructed from bytecode.
 * Immutable; structure is kept so later stages (state machines, test generation) can reason about it.
 */
public final class Expr {

    public enum Kind { CONST, NULL, THIS, PARAM, LOCAL, FIELD, STATIC, CALL, NEW, BINARY, UNARY, CMP, INSTANCEOF, ARRAY_ELEM, ARRAY_NEW, LAMBDA, SWITCH_INDEX, UNKNOWN }

    public final Kind kind;
    public final String text;
    /** JVM descriptor of the value's type when known (e.g. {@code I}, {@code Ljava/lang/String;}), else null. */
    public final String desc;
    public final Object value;      // CONST
    public final String owner;      // FIELD / STATIC / CALL / NEW: internal name
    public final String name;       // FIELD / STATIC / CALL / PARAM / LOCAL
    public final String memberDesc; // CALL: method descriptor, FIELD: field descriptor
    public final Expr target;       // receiver / operand
    public final List<Expr> args;   // call args, binary operands
    public final int index;         // PARAM index / LOCAL slot
    public final Object extra;      // SWITCH_INDEX labels, etc.

    private Expr(Kind kind, String text, String desc, Object value, String owner, String name, String memberDesc,
                 Expr target, List<Expr> args, int index, Object extra) {
        this.kind = kind;
        this.text = text;
        this.desc = desc;
        this.value = value;
        this.owner = owner;
        this.name = name;
        this.memberDesc = memberDesc;
        this.target = target;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.index = index;
        this.extra = extra;
    }

    // ---- factories ------------------------------------------------------------------------------

    public static Expr constant(Object v, String desc) {
        String text;
        if (v instanceof String s) text = '"' + escape(s) + '"';
        else if (v instanceof Long l) text = l + "L";
        else if (v instanceof Float f) text = f + "f";
        else if (v instanceof Character c) text = "'" + c + "'";
        else if (v instanceof org.objectweb.asm.Type t) text = Names.typeName(t) + ".class";
        else if ("Z".equals(desc) && v instanceof Integer i) text = i != 0 ? "true" : "false";
        else text = String.valueOf(v);
        return new Expr(Kind.CONST, text, desc, v, null, null, null, null, null, -1, null);
    }

    public static Expr nul() {
        return new Expr(Kind.NULL, "null", null, null, null, null, null, null, null, -1, null);
    }

    public static Expr self(String ownerInternal) {
        return new Expr(Kind.THIS, "this", "L" + ownerInternal + ";", null, ownerInternal, "this", null, null, null, 0, null);
    }

    public static Expr param(int paramIndex, int slot, String name, String desc) {
        return new Expr(Kind.PARAM, name, desc, null, null, name, null, null, null, paramIndex, slot);
    }

    public static Expr local(int slot, String name, String desc) {
        return new Expr(Kind.LOCAL, name, desc, null, null, name, null, null, null, slot, null);
    }

    public static Expr field(Expr target, String owner, String name, String desc) {
        String text = (target.kind == Kind.THIS ? "this." : target.paren() + ".") + name;
        return new Expr(Kind.FIELD, text, desc, null, owner, name, desc, target, null, -1, null);
    }

    public static Expr staticField(String owner, String name, String desc) {
        return new Expr(Kind.STATIC, Names.simple(owner) + "." + name, desc, null, owner, name, desc, null, null, -1, null);
    }

    public static Expr call(Expr target, String owner, String name, String methodDesc, List<Expr> args) {
        String argText = args.stream().map(a -> a.text).collect(Collectors.joining(", "));
        String recv = target == null ? Names.simple(owner) : (target.kind == Kind.THIS ? "this" : target.paren());
        String text = recv + "." + name + "(" + argText + ")";
        String ret = org.objectweb.asm.Type.getReturnType(methodDesc).getDescriptor();
        return new Expr(Kind.CALL, text, ret, null, owner, name, methodDesc, target, args, -1, null);
    }

    public static Expr newObject(String owner, String ctorDesc, List<Expr> args) {
        String argText = args.stream().map(a -> a.text).collect(Collectors.joining(", "));
        return new Expr(Kind.NEW, "new " + Names.simple(owner) + "(" + argText + ")", "L" + owner + ";", null, owner,
                "<init>", ctorDesc, null, args, -1, null);
    }

    /** Placeholder for the result of NEW before its constructor runs. */
    public static Expr uninitialized(String owner, int insnIndex) {
        return new Expr(Kind.NEW, "new " + Names.simple(owner), "L" + owner + ";", null, owner, null, null, null, null,
                insnIndex, "uninit");
    }

    public static Expr binary(String op, Expr a, Expr b, String desc) {
        return new Expr(Kind.BINARY, a.paren() + " " + op + " " + b.paren(), desc, null, null, op, null, null, List.of(a, b), -1, null);
    }

    public static Expr unary(String op, Expr a, String desc) {
        String text = op.startsWith("(") ? op + a.paren() : op + a.paren();
        return new Expr(Kind.UNARY, text, desc, null, null, op, null, a, null, -1, null);
    }

    /** Result of lcmp/fcmpl/dcmpl: compared to zero by the following IFxx. */
    public static Expr cmp(Expr a, Expr b) {
        return new Expr(Kind.CMP, "compare(" + a.text + ", " + b.text + ")", "I", null, null, "cmp", null, null, List.of(a, b), -1, null);
    }

    public static Expr instanceOf(Expr a, String type) {
        return new Expr(Kind.INSTANCEOF, a.paren() + " instanceof " + Names.simple(type), "Z", null, type, "instanceof", null, a, null, -1, null);
    }

    public static Expr arrayElem(Expr arr, Expr idx, String desc) {
        return new Expr(Kind.ARRAY_ELEM, arr.paren() + "[" + idx.text + "]", desc, null, null, null, null, arr, List.of(arr, idx), -1, null);
    }

    public static Expr newArray(String elemType, Expr size) {
        return new Expr(Kind.ARRAY_NEW, "new " + elemType + "[" + size.text + "]", null, null, null, null, null, null, List.of(size), -1, null);
    }

    public static Expr lambda(String text, String desc) {
        return new Expr(Kind.LAMBDA, text, desc, null, null, null, null, null, null, -1, null);
    }

    public static Expr switchIndex(Expr selector, List<String> labels, String enumOwner) {
        return new Expr(Kind.SWITCH_INDEX, "switchIndex(" + selector.text + ")", "I", null, enumOwner, null, null, selector, null, -1, labels);
    }

    /** A string concatenation: displayed as its source text, with the non-constant parts kept as {@link #args}. */
    public static Expr concat(String text, List<Expr> parts) {
        List<Expr> vars = parts.stream().filter(x -> !x.isConst()).toList();
        return new Expr(Kind.UNKNOWN, text, "Ljava/lang/String;", null, null, null, null, null, vars, -1, null);
    }

    public static Expr unknown(String text, String desc) {
        return new Expr(Kind.UNKNOWN, text, desc, null, null, null, null, null, null, -1, null);
    }

    public Expr withText(String newText) {
        return new Expr(kind, newText, desc, value, owner, name, memberDesc, target, args, index, extra);
    }

    public Expr withDesc(String newDesc) {
        Expr e = new Expr(kind, text, newDesc, value, owner, name, memberDesc, target, args, index, extra);
        e.expansion = expansion;
        return e;
    }

    /**
     * Same value, displayed under a local variable name (from debug info) instead of its full expression.
     * The structure is kept, so later stages can still see that {@code order} is {@code repo.findById(id).get()}.
     */
    public Expr aliased(String localName) {
        Expr e = new Expr(kind, localName, desc, value, owner, name, memberDesc, target, args, index, extra);
        e.expansion = expansion != null ? expansion : text;
        return e;
    }

    /** Full expression behind an alias, or null when this expression is not aliased. */
    public String expansion() {
        return expansion;
    }

    private String expansion;

    // ---- queries --------------------------------------------------------------------------------

    public boolean isWide() {
        return "J".equals(desc) || "D".equals(desc);
    }

    public boolean isBoolean() {
        return "Z".equals(desc);
    }

    public boolean isConst() {
        return kind == Kind.CONST || kind == Kind.NULL;
    }

    public boolean isUninitialized() {
        return kind == Kind.NEW && "uninit".equals(extra);
    }

    /** Does this expression read the given parameter (directly or nested)? */
    public boolean mentions(java.util.function.Predicate<Expr> p) {
        if (p.test(this)) return true;
        if (target != null && target.mentions(p)) return true;
        for (Expr a : args) if (a.mentions(p)) return true;
        return false;
    }

    /**
     * Copy of this expression with every matching sub-expression replaced; call and field chains above a
     * replacement are rebuilt (so their text changes), anything else is kept as is.
     */
    public Expr substitute(java.util.function.Predicate<Expr> match, Expr replacement) {
        if (match.test(this)) return replacement;
        if (kind != Kind.CALL && kind != Kind.FIELD) return this;
        Expr t = target == null ? null : target.substitute(match, replacement);
        List<Expr> as = args.stream().map(a -> a.substitute(match, replacement)).toList();
        boolean same = t == target;
        for (int i = 0; same && i < as.size(); i++) same = as.get(i) == args.get(i);
        if (same) return this;
        Expr e = kind == Kind.CALL ? call(t, owner, name, memberDesc, as) : field(t, owner, name, desc);
        return java.util.Objects.equals(e.desc, desc) ? e : e.withDesc(desc);
    }

    /** The root receiver of a chain: {@code this.repo.find(x).isPresent()} → {@code this}. */
    public Expr root() {
        Expr e = this;
        while (e.target != null && (e.kind == Kind.CALL || e.kind == Kind.FIELD)) e = e.target;
        return e;
    }

    String paren() {
        return switch (kind) {
            case BINARY, INSTANCEOF -> "(" + text + ")";
            default -> text;
        };
    }

    private static String escape(String s) {
        String r = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return r.length() > 80 ? r.substring(0, 77) + "..." : r;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Expr e && e.kind == kind && e.text.equals(text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, text);
    }
}
