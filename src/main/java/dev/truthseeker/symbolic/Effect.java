package dev.truthseeker.symbolic;

import dev.truthseeker.archive.Names;

import java.util.List;
import java.util.stream.Collectors;

/** An observable side effect on a path: a method call, field write or array store. */
public record Effect(Kind kind, Expr target, String owner, String name, String desc, List<Expr> args, Expr value) {

    public enum Kind { CALL, FIELD_WRITE, STATIC_WRITE, ARRAY_WRITE }

    public String text() {
        return switch (kind) {
            case CALL -> (target == null ? Names.simple(owner) : target.text) + "." + name + "("
                    + args.stream().map(a -> a.text).collect(Collectors.joining(", ")) + ")";
            case FIELD_WRITE -> (target.kind == Expr.Kind.THIS ? "this" : target.text) + "." + name + " = " + value.text;
            case STATIC_WRITE -> Names.simple(owner) + "." + name + " = " + value.text;
            case ARRAY_WRITE -> target.text + "[" + args.get(0).text + "] = " + value.text;
        };
    }
}
