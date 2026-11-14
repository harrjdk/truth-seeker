package dev.truthseeker.symbolic;

import dev.truthseeker.archive.ClassRepository;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recovers the enum constant behind each case of an enum {@code switch}.
 * <ul>
 *   <li>javac: {@code Outer$1.$SwitchMap$pkg$Enum[e.ordinal()]}, filled in {@code Outer$1.<clinit>}</li>
 *   <li>ECJ (Eclipse, common in legacy builds): {@code Outer.$SWITCH_TABLE$pkg$Enum()[e.ordinal()]}</li>
 * </ul>
 */
final class SwitchMaps {
    private static final Map<String, Map<Integer, Expr>> CACHE = new ConcurrentHashMap<>();

    private SwitchMaps() {}

    /** @param member field name ($SwitchMap$...) or method name ($SWITCH_TABLE$...) */
    static Map<Integer, Expr> resolve(ClassRepository repo, String owner, String member) {
        if (repo == null) return Map.of();
        String key = System.identityHashCode(repo) + ":" + owner + "." + member;
        return CACHE.computeIfAbsent(key, k -> scan(repo, owner, member));
    }

    private static Map<Integer, Expr> scan(ClassRepository repo, String owner, String member) {
        ClassNode cn = repo.node(owner);
        Map<Integer, Expr> out = new HashMap<>();
        if (cn == null) return out;
        boolean ecj = member.startsWith("$SWITCH_TABLE$");
        for (MethodNode mn : cn.methods) {
            if (ecj ? !mn.name.equals(member) : !mn.name.equals("<clinit>")) continue;
            String currentArray = ecj ? member : null;
            FieldInsnNode lastStatic = null;
            FieldInsnNode candidate = null;
            Integer k = null;
            for (AbstractInsnNode in : mn.instructions) {
                if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
                    if (f.name.startsWith("$SwitchMap$")) currentArray = f.name;
                    else lastStatic = f;
                } else if (in instanceof MethodInsnNode m && m.name.equals("ordinal") && m.desc.equals("()I")) {
                    candidate = lastStatic;
                    k = null;
                } else if (candidate != null && k == null && intConst(in) != null) {
                    k = intConst(in);
                } else if (in.getOpcode() == Opcodes.IASTORE && candidate != null && k != null) {
                    if (member.equals(currentArray)) {
                        out.put(k, Expr.staticField(candidate.owner, candidate.name, candidate.desc));
                    }
                    candidate = null;
                    k = null;
                }
            }
        }
        return out;
    }

    static Integer intConst(AbstractInsnNode in) {
        int op = in.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (in instanceof IntInsnNode i && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return i.operand;
        if (in instanceof LdcInsnNode l && l.cst instanceof Integer v) return v;
        return null;
    }
}
