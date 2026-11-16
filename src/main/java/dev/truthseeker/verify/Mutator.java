package dev.truthseeker.verify;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small, stack-neutral changes to one class's bytecode ("mutants"), each a plausible slip a refactoring could make:
 * a negated or off-by-one condition, swapped arithmetic, a changed return value, a dropped call. A generated test that
 * still passes against a mutant does not pin that behaviour down.
 */
final class Mutator {
    private Mutator() {}

    /** One change: where it is and what it does. {@code bytes} is the whole mutated class. */
    record Mutant(String className, String method, int line, String description, byte[] bytes) {}

    private static final Map<Integer, Integer> NEGATE = Map.ofEntries(
            Map.entry(Opcodes.IFEQ, Opcodes.IFNE), Map.entry(Opcodes.IFNE, Opcodes.IFEQ),
            Map.entry(Opcodes.IFLT, Opcodes.IFGE), Map.entry(Opcodes.IFGE, Opcodes.IFLT),
            Map.entry(Opcodes.IFGT, Opcodes.IFLE), Map.entry(Opcodes.IFLE, Opcodes.IFGT),
            Map.entry(Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE), Map.entry(Opcodes.IF_ICMPNE, Opcodes.IF_ICMPEQ),
            Map.entry(Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE), Map.entry(Opcodes.IF_ICMPGE, Opcodes.IF_ICMPLT),
            Map.entry(Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE), Map.entry(Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT),
            Map.entry(Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE), Map.entry(Opcodes.IF_ACMPNE, Opcodes.IF_ACMPEQ),
            Map.entry(Opcodes.IFNULL, Opcodes.IFNONNULL), Map.entry(Opcodes.IFNONNULL, Opcodes.IFNULL));

    /** {@code <} ↔ {@code <=} and {@code >} ↔ {@code >=}: the classic off-by-one. */
    private static final Map<Integer, Integer> BOUNDARY = Map.of(
            Opcodes.IFLT, Opcodes.IFLE, Opcodes.IFLE, Opcodes.IFLT, Opcodes.IFGT, Opcodes.IFGE, Opcodes.IFGE, Opcodes.IFGT,
            Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT);

    private static final Map<Integer, Integer> MATH = Map.ofEntries(
            Map.entry(Opcodes.IADD, Opcodes.ISUB), Map.entry(Opcodes.ISUB, Opcodes.IADD),
            Map.entry(Opcodes.IMUL, Opcodes.IDIV), Map.entry(Opcodes.IDIV, Opcodes.IMUL),
            Map.entry(Opcodes.LADD, Opcodes.LSUB), Map.entry(Opcodes.LSUB, Opcodes.LADD),
            Map.entry(Opcodes.LMUL, Opcodes.LDIV), Map.entry(Opcodes.LDIV, Opcodes.LMUL),
            Map.entry(Opcodes.DADD, Opcodes.DSUB), Map.entry(Opcodes.DSUB, Opcodes.DADD),
            Map.entry(Opcodes.DMUL, Opcodes.DDIV), Map.entry(Opcodes.DDIV, Opcodes.DMUL),
            Map.entry(Opcodes.IREM, Opcodes.IMUL));

    private static final Map<Integer, String> OPS = Map.ofEntries(
            Map.entry(Opcodes.IFEQ, "== 0"), Map.entry(Opcodes.IFNE, "!= 0"), Map.entry(Opcodes.IFLT, "< 0"), Map.entry(Opcodes.IFGE, ">= 0"),
            Map.entry(Opcodes.IFGT, "> 0"), Map.entry(Opcodes.IFLE, "<= 0"), Map.entry(Opcodes.IF_ICMPEQ, "=="), Map.entry(Opcodes.IF_ICMPNE, "!="),
            Map.entry(Opcodes.IF_ICMPLT, "<"), Map.entry(Opcodes.IF_ICMPGE, ">="), Map.entry(Opcodes.IF_ICMPGT, ">"), Map.entry(Opcodes.IF_ICMPLE, "<="),
            Map.entry(Opcodes.IF_ACMPEQ, "== (same object)"), Map.entry(Opcodes.IF_ACMPNE, "!= (same object)"),
            Map.entry(Opcodes.IFNULL, "== null"), Map.entry(Opcodes.IFNONNULL, "!= null"),
            Map.entry(Opcodes.IADD, "+"), Map.entry(Opcodes.ISUB, "-"), Map.entry(Opcodes.IMUL, "*"), Map.entry(Opcodes.IDIV, "/"),
            Map.entry(Opcodes.IREM, "%"), Map.entry(Opcodes.LADD, "+"), Map.entry(Opcodes.LSUB, "-"), Map.entry(Opcodes.LMUL, "*"),
            Map.entry(Opcodes.LDIV, "/"), Map.entry(Opcodes.DADD, "+"), Map.entry(Opcodes.DSUB, "-"), Map.entry(Opcodes.DMUL, "*"),
            Map.entry(Opcodes.DDIV, "/"));

    /** Every mutant of a class, in method order. */
    static List<Mutant> mutants(byte[] classBytes) {
        ClassNode probe = read(classBytes);
        List<Mutant> out = new ArrayList<>();
        for (int m = 0; m < probe.methods.size(); m++) {
            MethodNode mn = probe.methods.get(m);
            if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) continue;
            // synthetic code is the compiler's, except lambda bodies: those hold the method's logic
            if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0 && !mn.name.startsWith("lambda$")) continue;
            if (mn.name.equals("<clinit>") || mn.name.equals("<init>") || mn.instructions.size() == 0) continue;
            for (int i = 0; i < mn.instructions.size(); i++) {
                AbstractInsnNode in = mn.instructions.get(i);
                int op = in.getOpcode();
                if (op < 0) continue;
                if (in instanceof JumpInsnNode && NEGATE.containsKey(op)) {
                    add(out, classBytes, m, i, "negated condition: " + OPS.get(op) + " became " + OPS.get(NEGATE.get(op)),
                            (node, insn) -> ((JumpInsnNode) insn).setOpcode(NEGATE.get(op)));
                    if (BOUNDARY.containsKey(op)) {
                        add(out, classBytes, m, i, "changed boundary: " + OPS.get(op) + " became " + OPS.get(BOUNDARY.get(op)),
                                (node, insn) -> ((JumpInsnNode) insn).setOpcode(BOUNDARY.get(op)));
                    }
                } else if (in instanceof InsnNode && MATH.containsKey(op)) {
                    add(out, classBytes, m, i, "changed arithmetic: " + OPS.get(op) + " became " + OPS.get(MATH.get(op)),
                            (node, insn) -> node.instructions.set(insn, new InsnNode(MATH.get(op))));
                } else if (op == Opcodes.IRETURN && !mn.name.equals("hashCode")) {
                    Type rt = Type.getReturnType(mn.desc);
                    boolean bool = rt.getSort() == Type.BOOLEAN;
                    add(out, classBytes, m, i, bool ? "returns the opposite boolean" : "returns the value + 1", (node, insn) -> {
                        InsnList extra = new InsnList();
                        extra.add(new InsnNode(Opcodes.ICONST_1));
                        extra.add(new InsnNode(bool ? Opcodes.IXOR : Opcodes.IADD));
                        node.instructions.insertBefore(insn, extra);
                    });
                } else if (op == Opcodes.ARETURN && !mn.name.equals("toString")) {
                    if (in.getPrevious() != null && in.getPrevious().getOpcode() == Opcodes.ACONST_NULL) continue;
                    String[] empty = EMPTY.get(Type.getReturnType(mn.desc).getDescriptor());
                    if (empty != null) {
                        add(out, classBytes, m, i, "returns " + empty[3], (node, insn) -> {
                            InsnList extra = new InsnList();
                            extra.add(new InsnNode(Opcodes.POP));
                            if (empty[0].equals("ldc")) extra.add(new LdcInsnNode(""));
                            else extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, empty[0], empty[1], empty[2], false));
                            node.instructions.insertBefore(insn, extra);
                        });
                    }
                    add(out, classBytes, m, i, "returns null", (node, insn) -> {
                        InsnList extra = new InsnList();
                        extra.add(new InsnNode(Opcodes.POP));
                        extra.add(new InsnNode(Opcodes.ACONST_NULL));
                        node.instructions.insertBefore(insn, extra);
                    });
                } else if (op == Opcodes.PUTFIELD) {
                    FieldInsnNode fi = (FieldInsnNode) in;
                    add(out, classBytes, m, i, "removed write of field " + fi.name, (node, insn) -> {
                        InsnList pops = new InsnList();
                        pops.add(new InsnNode(Type.getType(((FieldInsnNode) insn).desc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                        pops.add(new InsnNode(Opcodes.POP));
                        node.instructions.insertBefore(insn, pops);
                        node.instructions.remove(insn);
                    });
                } else if (in instanceof LdcInsnNode ldc && ldc.cst instanceof String str && constantMatters(mn, in)) {
                    String other = str.isEmpty() ? "mutated" : "";
                    add(out, classBytes, m, i, "changed constant " + quote(str) + " to " + quote(other),
                            (node, insn) -> node.instructions.set(insn, new LdcInsnNode(other)));
                } else if (intConstant(in) != null && constantMatters(mn, in)) {
                    int v = intConstant(in);
                    int other = v == 0 ? 1 : v == 1 ? 0 : v + 1;
                    add(out, classBytes, m, i, "changed constant " + v + " to " + other,
                            (node, insn) -> node.instructions.set(insn, new LdcInsnNode(other)));
                } else if (in instanceof MethodInsnNode call && droppable(probe, call)) {
                    add(out, classBytes, m, i, "removed call to " + simple(call.owner) + "." + call.name + "(..)", (node, insn) -> {
                        MethodInsnNode c = (MethodInsnNode) insn;
                        InsnList pops = new InsnList();
                        Type[] args = Type.getArgumentTypes(c.desc);
                        for (int a = args.length - 1; a >= 0; a--) pops.add(new InsnNode(args[a].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                        if (c.getOpcode() != Opcodes.INVOKESTATIC) pops.add(new InsnNode(Opcodes.POP));
                        node.instructions.insertBefore(insn, pops);
                        node.instructions.remove(insn);
                    });
                }
            }
        }
        return out;
    }

    /** Empty values for "returns empty" mutants: {owner, name, desc, description} or {"ldc", .., .., ..} for "". */
    private static final Map<String, String[]> EMPTY = Map.of(
            "Ljava/util/List;", new String[]{"java/util/Collections", "emptyList", "()Ljava/util/List;", "an empty list"},
            "Ljava/util/Collection;", new String[]{"java/util/Collections", "emptyList", "()Ljava/util/List;", "an empty list"},
            "Ljava/util/Set;", new String[]{"java/util/Collections", "emptySet", "()Ljava/util/Set;", "an empty set"},
            "Ljava/util/Map;", new String[]{"java/util/Collections", "emptyMap", "()Ljava/util/Map;", "an empty map"},
            "Ljava/util/Optional;", new String[]{"java/util/Optional", "empty", "()Ljava/util/Optional;", "Optional.empty()"},
            "Ljava/lang/String;", new String[]{"ldc", "", "", "\"\""});

    /** The value of an int constant instruction (iconst, bipush, sipush, ldc int), or null. */
    private static Integer intConstant(AbstractInsnNode in) {
        int op = in.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if ((op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) && in instanceof IntInsnNode ii) return ii.operand;
        if (in instanceof LdcInsnNode l && l.cst instanceof Integer n) return n;
        return null;
    }

    /**
     * A constant whose value is behaviour: not a varargs array's size or index, not a logged message, not inside
     * hashCode/toString.
     */
    private static boolean constantMatters(MethodNode mn, AbstractInsnNode in) {
        if (mn.name.equals("hashCode") || mn.name.equals("toString")) return false;
        AbstractInsnNode prev = in.getPrevious(), next = in.getNext();
        while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        if (next != null && (next.getOpcode() == Opcodes.ANEWARRAY || next.getOpcode() == Opcodes.NEWARRAY)) return false;
        if (prev != null && prev.getOpcode() == Opcodes.DUP && intConstant(in) != null) return false; // arr[i] = .. in an array literal
        // a message handed to a logger, possibly after its arguments are built (log(Level.INFO, "..", new Object[]{..}))
        int seen = 0;
        for (AbstractInsnNode n = next; n != null && seen < 24; n = n.getNext()) {
            if (n.getOpcode() < 0) continue;
            seen++;
            if (n instanceof MethodInsnNode c) {
                String o = c.owner.toLowerCase();
                if (o.contains("log") || o.startsWith("java/io/printstream")) return false;
            }
            if (n.getOpcode() >= Opcodes.IRETURN && n.getOpcode() <= Opcodes.RETURN || n instanceof JumpInsnNode
                    || n.getOpcode() == Opcodes.ATHROW || n.getOpcode() == Opcodes.PUTFIELD) break;
        }
        return true;
    }

    private static String quote(String s) {
        String t = s.length() > 30 ? s.substring(0, 27) + "..." : s;
        return "\"" + t.replace("\n", "\\n") + "\"";
    }

    /** A void call on something other than the object itself (a collaborator, a logger is skipped): its effect can vanish. */
    private static boolean droppable(ClassNode owner, MethodInsnNode call) {
        if (!Type.getReturnType(call.desc).equals(Type.VOID_TYPE) || call.name.equals("<init>") || call.name.equals("close")) return false;
        if (call.owner.equals(owner.name) || call.owner.startsWith("java/lang/") || call.owner.startsWith("java/io/PrintStream")) return false;
        String o = call.owner.toLowerCase();
        return !(o.contains("log") || o.startsWith("org/slf4j/") || o.startsWith("org/apache/commons/logging/"));
    }

    private interface Edit {
        void apply(MethodNode method, AbstractInsnNode insn);
    }

    private static void add(List<Mutant> out, byte[] original, int methodIndex, int insnIndex, String description, Edit edit) {
        ClassNode cn = read(original);
        MethodNode mn = cn.methods.get(methodIndex);
        AbstractInsnNode insn = mn.instructions.get(insnIndex);
        int line = lineOf(insn);
        edit.apply(mn, insn);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); // stack-neutral edits keep the existing frames valid
        try {
            cn.accept(cw);
        } catch (RuntimeException e) {
            return; // not expressible: skip this one
        }
        out.add(new Mutant(cn.name, mn.name, line, description, cw.toByteArray()));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static int lineOf(AbstractInsnNode insn) {
        for (AbstractInsnNode p = insn; p != null; p = p.getPrevious()) if (p instanceof LineNumberNode ln) return ln.line;
        return -1;
    }

    private static String simple(String internal) {
        String n = internal.substring(internal.lastIndexOf('/') + 1);
        return n.substring(n.lastIndexOf('$') + 1);
    }
}
