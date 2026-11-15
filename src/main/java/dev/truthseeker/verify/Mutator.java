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
                    add(out, classBytes, m, i, "returns null", (node, insn) -> {
                        InsnList extra = new InsnList();
                        extra.add(new InsnNode(Opcodes.POP));
                        extra.add(new InsnNode(Opcodes.ACONST_NULL));
                        node.instructions.insertBefore(insn, extra);
                    });
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
