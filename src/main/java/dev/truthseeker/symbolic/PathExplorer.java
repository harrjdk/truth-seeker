package dev.truthseeker.symbolic;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Bounded symbolic execution over a single method's bytecode. Each feasible path records the
 * decisions taken ({@link Cond}), the side effects ({@link Effect}) and how the method exits.
 *
 * <p>Deliberate simplifications (documented in tool output): catch handlers are not entered,
 * loops are unrolled at most {@code loopBound} times, and callee bodies are not inlined —
 * calls stay symbolic ({@code this.repo.findById(id)}).
 */
public final class PathExplorer {

    /**
     * @param ignoreLogging treat {@code log.isDebugEnabled()}-style guards as false and drop logger calls
     *                      from side effects — they multiply paths without carrying business meaning
     */
    public record Options(int maxPaths, int loopBound, int maxStepsPerPath, int maxExprLength, boolean ignoreLogging) {
        public static Options defaults() {
            return new Options(256, 1, 20_000, 140, true);
        }

        public Options withMaxPaths(int n) {
            return new Options(n, loopBound, maxStepsPerPath, maxExprLength, ignoreLogging);
        }
    }

    private static final Set<String> LOGGER_TYPES = Set.of(
            "org/slf4j/Logger", "org/apache/commons/logging/Log", "org/apache/log4j/Logger", "org/apache/log4j/Category",
            "org/apache/logging/log4j/Logger", "java/util/logging/Logger", "java/lang/System$Logger", "org/jboss/logging/Logger");

    private static boolean isLoggingGuard(Expr e) {
        return e.kind == Expr.Kind.CALL && e.args.size() <= 1 && e.name != null
                && (e.name.equals("isLoggable") || (e.name.startsWith("is") && e.name.endsWith("Enabled")))
                && (LOGGER_TYPES.contains(e.owner) || e.owner.toLowerCase(Locale.ROOT).contains("log"));
    }

    public record Result(String owner, MethodNode method, List<Expr> params, List<Path> paths, boolean truncated,
                         List<String> notes) {}

    private final ClassRepository repo;
    private final ClassNode cn;
    private final MethodNode mn;
    private final Options opt;
    private final InsnList insns;
    private final boolean isStatic;
    private final Type[] argTypes;
    private final Type returnType;
    private final Set<String> notes = new LinkedHashSet<>();
    private final Map<String, String> canonicalOps = new HashMap<>();
    private final List<Path> paths = new ArrayList<>();
    private final List<Expr> params = new ArrayList<>();
    private int pathIds;

    private PathExplorer(ClassRepository repo, ClassNode cn, MethodNode mn, Options opt) {
        this.repo = repo;
        this.cn = cn;
        this.mn = mn;
        this.opt = opt;
        this.insns = mn.instructions;
        this.isStatic = (mn.access & ACC_STATIC) != 0;
        this.argTypes = Type.getArgumentTypes(mn.desc);
        this.returnType = Type.getReturnType(mn.desc);
    }

    public static Result explore(ClassRepository repo, ClassNode cn, MethodNode mn, Options opt) {
        return new PathExplorer(repo, cn, mn, opt).run();
    }

    // ---------------------------------------------------------------------------------------------

    private final class Frame {
        int pc;
        ArrayList<Expr> stack;
        Expr[] locals;
        HashMap<String, Expr> thisFields;
        ArrayList<Cond> conds;
        ArrayList<Effect> effects;
        int[] visits;
        int steps;
        /** Loop iterations re-entered on this path, and how many decisions were made before the latest one. */
        int iteration;
        int barrier;

        Frame copy() {
            Frame f = new Frame();
            f.iteration = iteration;
            f.barrier = barrier;
            f.pc = pc;
            f.stack = new ArrayList<>(stack);
            f.locals = locals.clone();
            f.thisFields = new HashMap<>(thisFields);
            f.conds = new ArrayList<>(conds);
            f.effects = new ArrayList<>(effects);
            f.visits = visits.clone();
            f.steps = steps;
            return f;
        }

        void push(Expr e) {
            stack.add(e);
        }

        Expr pop() {
            if (stack.isEmpty()) return Expr.unknown("?", null);
            return stack.remove(stack.size() - 1);
        }

        Expr peek() {
            return stack.isEmpty() ? Expr.unknown("?", null) : stack.get(stack.size() - 1);
        }
    }

    private Result run() {
        if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || insns.size() == 0) {
            notes.add("Method has no bytecode (abstract, interface or native).");
            return new Result(cn.name, mn, params, paths, false, List.copyOf(notes));
        }
        if (!mn.tryCatchBlocks.isEmpty()) {
            notes.add("Catch/finally handlers are not explored; exceptions thrown inside try blocks are reported as method exits.");
        }
        Frame init = new Frame();
        init.pc = 0;
        init.stack = new ArrayList<>();
        init.locals = new Expr[Math.max(mn.maxLocals, 1 + argTypes.length * 2)];
        init.thisFields = new HashMap<>();
        init.conds = new ArrayList<>();
        init.effects = new ArrayList<>();
        init.visits = new int[insns.size()];
        int slot = 0;
        if (!isStatic) init.locals[slot++] = Expr.self(cn.name);
        for (int p = 0; p < argTypes.length; p++) {
            String name = paramName(p, slot);
            Expr e = Expr.param(p, slot, name, argTypes[p].getDescriptor());
            params.add(e);
            init.locals[slot] = e;
            slot += argTypes[p].getSize();
        }

        Deque<Frame> work = new ArrayDeque<>();
        work.push(init);
        boolean truncated = false;
        while (!work.isEmpty()) {
            if (paths.size() >= opt.maxPaths()) {
                truncated = true;
                notes.add("Path limit (" + opt.maxPaths() + ") reached; remaining paths were not explored.");
                break;
            }
            Frame f = work.pop();
            try {
                execute(f, work);
            } catch (RuntimeException ex) {
                finish(f, new Path.Outcome(Path.OutcomeKind.TRUNCATED, null, null, "analysis error: " + ex));
            }
        }
        return new Result(cn.name, mn, params, paths, truncated, List.copyOf(notes));
    }

    private String paramName(int p, int slot) {
        if (mn.localVariables != null) {
            for (LocalVariableNode lv : mn.localVariables) {
                if (lv.index == slot && insns.indexOf(lv.start) <= firstRealInsn()) return lv.name;
            }
        }
        if (mn.parameters != null && p < mn.parameters.size() && mn.parameters.get(p).name != null) {
            return mn.parameters.get(p).name;
        }
        return "arg" + p;
    }

    private int firstRealInsn() {
        for (int i = 0; i < insns.size(); i++) if (insns.get(i).getOpcode() >= 0) return i;
        return 0;
    }

    private String localName(int slot, int pc) {
        if (mn.localVariables != null) {
            for (LocalVariableNode lv : mn.localVariables) {
                if (lv.index == slot && insns.indexOf(lv.start) <= pc && pc <= insns.indexOf(lv.end)) return lv.name;
            }
        }
        return "local" + slot;
    }

    private String localNameOrNull(int slot, int pc) {
        String n = localName(slot, pc);
        return n.startsWith("local") ? null : n;
    }

    private String localDesc(int slot, int pc) {
        if (mn.localVariables != null) {
            for (LocalVariableNode lv : mn.localVariables) {
                if (lv.index == slot && insns.indexOf(lv.start) <= pc && pc <= insns.indexOf(lv.end)) return lv.desc;
            }
        }
        return null;
    }

    private void finish(Frame f, Path.Outcome outcome) {
        paths.add(new Path(++pathIds, List.copyOf(f.conds), List.copyOf(f.effects), outcome));
    }

    // ---------------------------------------------------------------------------------------------

    private void execute(Frame f, Deque<Frame> work) {
        while (true) {
            if (f.pc >= insns.size()) {
                finish(f, new Path.Outcome(Path.OutcomeKind.TRUNCATED, null, null, "fell off end of method"));
                return;
            }
            if (++f.steps > opt.maxStepsPerPath()) {
                notes.add("Some paths exceeded the step limit and were truncated.");
                finish(f, new Path.Outcome(Path.OutcomeKind.TRUNCATED, null, null, "step limit"));
                return;
            }
            AbstractInsnNode in = insns.get(f.pc);
            if (in instanceof LabelNode) {
                if (++f.visits[f.pc] > opt.loopBound() + 1) {
                    notes.add("Loops are unrolled at most " + opt.loopBound() + " time(s).");
                    return; // drop: the loop exit was already explored on an earlier iteration
                }
                if (f.visits[f.pc] > 1) { // back-edge: a new loop iteration starts here
                    f.iteration++;
                    f.barrier = f.conds.size();
                }
                f.pc++;
                continue;
            }
            int op = in.getOpcode();
            if (op < 0) { // line numbers, frames
                f.pc++;
                continue;
            }
            if (!step(f, in, op, work)) return;
        }
    }

    /** Executes one instruction. Returns false when the path ended. */
    private boolean step(Frame f, AbstractInsnNode in, int op, Deque<Frame> work) {
        switch (op) {
            case NOP -> {}
            case ACONST_NULL -> f.push(Expr.nul());
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> f.push(Expr.constant(op - ICONST_0, "I"));
            case LCONST_0, LCONST_1 -> f.push(Expr.constant((long) (op - LCONST_0), "J"));
            case FCONST_0, FCONST_1, FCONST_2 -> f.push(Expr.constant((float) (op - FCONST_0), "F"));
            case DCONST_0, DCONST_1 -> f.push(Expr.constant((double) (op - DCONST_0), "D"));
            case BIPUSH, SIPUSH -> f.push(Expr.constant(((IntInsnNode) in).operand, "I"));
            case LDC -> f.push(ldc(((LdcInsnNode) in).cst));
            case ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> {
                int slot = ((VarInsnNode) in).var;
                Expr v = f.locals[slot];
                if (v == null) {
                    String d = localDesc(slot, f.pc);
                    if (d == null) d = switch (op) { case ILOAD -> "I"; case LLOAD -> "J"; case FLOAD -> "F"; case DLOAD -> "D"; default -> null; };
                    v = Expr.local(slot, localName(slot, f.pc), d);
                }
                f.push(v);
            }
            case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> {
                int slot = ((VarInsnNode) in).var;
                Expr v = f.pop();
                String lvDesc = localDesc(slot, f.pc + 1);
                if (lvDesc != null) v = coerce(v, lvDesc);
                String lvName = localNameOrNull(slot, f.pc + 1);
                boolean trivial = v.kind == Expr.Kind.CONST || v.kind == Expr.Kind.NULL || v.kind == Expr.Kind.PARAM
                        || v.kind == Expr.Kind.THIS || v.kind == Expr.Kind.STATIC || v.kind == Expr.Kind.LOCAL;
                if (v.kind == Expr.Kind.NEW || v.kind == Expr.Kind.ARRAY_NEW || v.kind == Expr.Kind.LAMBDA) {
                    f.locals[slot] = Expr.local(slot, localName(slot, f.pc + 1), v.desc != null ? v.desc : lvDesc);
                } else if (!trivial && lvName != null) {
                    f.locals[slot] = v.aliased(lvName); // readable name, structure kept
                } else if (v.text.length() <= opt.maxExprLength()) {
                    f.locals[slot] = v;
                } else {
                    f.locals[slot] = v.aliased(localName(slot, f.pc + 1));
                }
                if (op == LSTORE || op == DSTORE) {
                    if (slot + 1 < f.locals.length) f.locals[slot + 1] = null;
                }
            }
            case IINC -> {
                IincInsnNode ii = (IincInsnNode) in;
                Expr cur = f.locals[ii.var] != null ? f.locals[ii.var] : Expr.local(ii.var, localName(ii.var, f.pc), "I");
                f.locals[ii.var] = arith("+", cur, Expr.constant(ii.incr, "I"), "I");
            }
            case IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD -> {
                Expr idx = f.pop();
                Expr arr = f.pop();
                String d = switch (op) {
                    case IALOAD -> "I"; case LALOAD -> "J"; case FALOAD -> "F"; case DALOAD -> "D";
                    case BALOAD -> "B"; case CALOAD -> "C"; case SALOAD -> "S";
                    default -> arr.desc != null && arr.desc.startsWith("[") ? arr.desc.substring(1) : null;
                };
                f.push(Expr.arrayElem(arr, idx, d));
            }
            case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> {
                Expr v = f.pop();
                Expr idx = f.pop();
                Expr arr = f.pop();
                if (arr.kind != Expr.Kind.ARRAY_NEW) {
                    f.effects.add(new Effect(Effect.Kind.ARRAY_WRITE, arr, null, null, null, List.of(idx), v));
                }
            }
            case POP -> f.pop();
            case POP2 -> {
                Expr v = f.pop();
                if (!v.isWide()) f.pop();
            }
            case DUP -> f.push(f.peek());
            case DUP_X1 -> {
                Expr v1 = f.pop(), v2 = f.pop();
                f.push(v1); f.push(v2); f.push(v1);
            }
            case DUP_X2 -> {
                Expr v1 = f.pop(), v2 = f.pop();
                if (v2.isWide()) {
                    f.push(v1); f.push(v2); f.push(v1);
                } else {
                    Expr v3 = f.pop();
                    f.push(v1); f.push(v3); f.push(v2); f.push(v1);
                }
            }
            case DUP2 -> {
                Expr v1 = f.peek();
                if (v1.isWide()) f.push(v1);
                else {
                    v1 = f.pop();
                    Expr v2 = f.pop();
                    f.push(v2); f.push(v1); f.push(v2); f.push(v1);
                }
            }
            case DUP2_X1 -> {
                Expr v1 = f.pop();
                if (v1.isWide()) {
                    Expr v2 = f.pop();
                    f.push(v1); f.push(v2); f.push(v1);
                } else {
                    Expr v2 = f.pop(), v3 = f.pop();
                    f.push(v2); f.push(v1); f.push(v3); f.push(v2); f.push(v1);
                }
            }
            case DUP2_X2 -> {
                Expr v1 = f.pop();
                if (v1.isWide()) {
                    Expr v2 = f.pop();
                    if (v2.isWide()) {
                        f.push(v1); f.push(v2); f.push(v1);
                    } else {
                        Expr v3 = f.pop();
                        f.push(v1); f.push(v3); f.push(v2); f.push(v1);
                    }
                } else {
                    Expr v2 = f.pop(), v3 = f.pop();
                    if (v3.isWide()) {
                        f.push(v2); f.push(v1); f.push(v3); f.push(v2); f.push(v1);
                    } else {
                        Expr v4 = f.pop();
                        f.push(v2); f.push(v1); f.push(v4); f.push(v3); f.push(v2); f.push(v1);
                    }
                }
            }
            case SWAP -> {
                Expr v1 = f.pop(), v2 = f.pop();
                f.push(v1); f.push(v2);
            }
            case IADD, LADD, FADD, DADD -> binop(f, "+", op - IADD);
            case ISUB, LSUB, FSUB, DSUB -> binop(f, "-", op - ISUB);
            case IMUL, LMUL, FMUL, DMUL -> binop(f, "*", op - IMUL);
            case IDIV, LDIV, FDIV, DDIV -> binop(f, "/", op - IDIV);
            case IREM, LREM, FREM, DREM -> binop(f, "%", op - IREM);
            case INEG, LNEG, FNEG, DNEG -> {
                Expr a = f.pop();
                f.push(a.value instanceof Number n && a.kind == Expr.Kind.CONST
                        ? Expr.constant(negate(n), a.desc) : Expr.unary("-", a, numDesc(op - INEG)));
            }
            case ISHL, LSHL -> shift(f, "<<", op - ISHL);
            case ISHR, LSHR -> shift(f, ">>", op - ISHR);
            case IUSHR, LUSHR -> shift(f, ">>>", op - IUSHR);
            case IAND, LAND -> logic(f, "&", op - IAND);
            case IOR, LOR -> logic(f, "|", op - IOR);
            case IXOR, LXOR -> {
                Expr b = f.pop(), a = f.pop();
                if (a.isBoolean() && b.kind == Expr.Kind.CONST && Integer.valueOf(1).equals(b.value)) f.push(Expr.unary("!", a, "Z"));
                else f.push(Expr.binary("^", a, b, a.isBoolean() && b.isBoolean() ? "Z" : (op == IXOR ? "I" : "J")));
            }
            case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S -> convert(f, op);
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> {
                Expr b = f.pop(), a = f.pop();
                f.push(Expr.cmp(a, b));
            }
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                Expr v = f.pop();
                String jop = jumpOp(op);
                Cond[] cs;
                if (v.kind == Expr.Kind.CMP) {
                    cs = cmpConds(v.args.get(0), jop, v.args.get(1));
                } else if ((op == IFEQ || op == IFNE) && (v.isBoolean() || v.kind == Expr.Kind.INSTANCEOF)) {
                    boolean takenValue = op == IFNE;
                    cs = new Cond[]{Cond.bool(v, takenValue), Cond.bool(v, !takenValue)};
                } else {
                    cs = cmpConds(v, jop, Expr.constant(0, "I"));
                }
                return branch(f, (JumpInsnNode) in, cs[0], cs[1], work);
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE -> {
                Expr b = f.pop(), a = f.pop();
                String jop = switch (op) {
                    case IF_ICMPEQ, IF_ACMPEQ -> "=="; case IF_ICMPNE, IF_ACMPNE -> "!=";
                    case IF_ICMPLT -> "<"; case IF_ICMPGE -> ">="; case IF_ICMPGT -> ">"; default -> "<=";
                };
                Cond[] cs = cmpConds(a, jop, b);
                return branch(f, (JumpInsnNode) in, cs[0], cs[1], work);
            }
            case IFNULL, IFNONNULL -> {
                Expr v = f.pop();
                Cond[] cs = cmpConds(v, op == IFNULL ? "==" : "!=", Expr.nul());
                return branch(f, (JumpInsnNode) in, cs[0], cs[1], work);
            }
            case GOTO -> {
                f.pc = insns.indexOf(((JumpInsnNode) in).label);
                return true;
            }
            case JSR, RET -> {
                notes.add("JSR/RET subroutines (pre-Java 6 bytecode) are not supported; affected paths are truncated.");
                finish(f, new Path.Outcome(Path.OutcomeKind.TRUNCATED, null, null, "JSR/RET"));
                return false;
            }
            case TABLESWITCH -> {
                TableSwitchInsnNode ts = (TableSwitchInsnNode) in;
                Map<Integer, LabelNode> cases = new LinkedHashMap<>();
                for (int i = 0; i < ts.labels.size(); i++) cases.put(ts.min + i, ts.labels.get(i));
                return doSwitch(f, f.pop(), cases, ts.dflt, work);
            }
            case LOOKUPSWITCH -> {
                LookupSwitchInsnNode ls = (LookupSwitchInsnNode) in;
                Map<Integer, LabelNode> cases = new LinkedHashMap<>();
                for (int i = 0; i < ls.keys.size(); i++) cases.put(ls.keys.get(i), ls.labels.get(i));
                return doSwitch(f, f.pop(), cases, ls.dflt, work);
            }
            case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
                Expr v = coerce(f.pop(), returnType.getDescriptor());
                finish(f, new Path.Outcome(Path.OutcomeKind.RETURN, v, null, null));
                return false;
            }
            case RETURN -> {
                finish(f, new Path.Outcome(Path.OutcomeKind.RETURN_VOID, null, null, null));
                return false;
            }
            case ATHROW -> {
                Expr v = f.pop();
                String type = v.kind == Expr.Kind.NEW ? v.owner
                        : (v.desc != null && v.desc.startsWith("L") ? Type.getType(v.desc).getInternalName() : "java/lang/Throwable");
                boolean syntheticDefault = v.kind == Expr.Kind.NEW && (type.equals("java/lang/MatchException")
                        || type.equals("java/lang/IncompatibleClassChangeError"))
                        && !f.conds.isEmpty() && f.conds.get(f.conds.size() - 1).kind() == Cond.Kind.SWITCH
                        && f.conds.get(f.conds.size() - 1).value().equals("default");
                if (syntheticDefault) {
                    notes.add("Compiler-generated default of an exhaustive switch (throws " + Names.simple(type) + ") omitted.");
                    return false;
                }
                finish(f, new Path.Outcome(Path.OutcomeKind.THROW, v, type, null));
                return false;
            }
            case GETSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                if (fi.name.equals("$assertionsDisabled")) f.push(Expr.constant(1, "Z"));
                else if (fi.name.startsWith("class$") && fi.desc.equals("Ljava/lang/Class;")) {
                    // pre-Java 5 compilers cached Foo.class in a synthetic static: class$com$acme$Foo
                    f.push(Expr.constant(Type.getObjectType(fi.name.substring(6).replace('$', '/')), "Ljava/lang/Class;"));
                } else f.push(Expr.staticField(fi.owner, fi.name, fi.desc));
            }
            case PUTSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                Expr v = coerce(f.pop(), fi.desc);
                f.effects.add(new Effect(Effect.Kind.STATIC_WRITE, null, fi.owner, fi.name, fi.desc, List.of(), v));
            }
            case GETFIELD -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                Expr target = f.pop();
                if (target.kind == Expr.Kind.THIS && f.thisFields.containsKey(fi.name)) f.push(f.thisFields.get(fi.name));
                else f.push(Expr.field(target, fi.owner, fi.name, fi.desc));
            }
            case PUTFIELD -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                Expr v = coerce(f.pop(), fi.desc);
                Expr target = f.pop();
                if (target.kind == Expr.Kind.THIS) f.thisFields.put(fi.name, v);
                f.effects.add(new Effect(Effect.Kind.FIELD_WRITE, target, fi.owner, fi.name, fi.desc, List.of(), v));
            }
            case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE -> invoke(f, (MethodInsnNode) in);
            case INVOKEDYNAMIC -> invokeDynamic(f, (InvokeDynamicInsnNode) in);
            case NEW -> f.push(Expr.uninitialized(((TypeInsnNode) in).desc, f.pc));
            case NEWARRAY -> {
                Expr size = f.pop();
                String t = switch (((IntInsnNode) in).operand) {
                    case T_BOOLEAN -> "boolean"; case T_CHAR -> "char"; case T_FLOAT -> "float"; case T_DOUBLE -> "double";
                    case T_BYTE -> "byte"; case T_SHORT -> "short"; case T_INT -> "int"; default -> "long";
                };
                f.push(Expr.newArray(t, size));
            }
            case ANEWARRAY -> {
                Expr size = f.pop();
                f.push(Expr.newArray(Names.simple(((TypeInsnNode) in).desc), size));
            }
            case MULTIANEWARRAY -> {
                MultiANewArrayInsnNode m = (MultiANewArrayInsnNode) in;
                Expr first = null;
                for (int i = 0; i < m.dims; i++) first = f.pop();
                f.push(Expr.newArray(Names.typeName(m.desc), first == null ? Expr.unknown("?", "I") : first).withDesc(m.desc));
            }
            case ARRAYLENGTH -> {
                Expr a = f.pop();
                f.push(Expr.unknown(a.text + ".length", "I"));
            }
            case CHECKCAST -> {
                Expr v = f.pop();
                String t = ((TypeInsnNode) in).desc;
                f.push(v.withDesc(t.startsWith("[") ? t : "L" + t + ";"));
            }
            case INSTANCEOF -> f.push(Expr.instanceOf(f.pop(), ((TypeInsnNode) in).desc));
            case MONITORENTER, MONITOREXIT -> f.pop();
            default -> {
                notes.add("Unhandled opcode " + op + "; affected paths are truncated.");
                finish(f, new Path.Outcome(Path.OutcomeKind.TRUNCATED, null, null, "opcode " + op));
                return false;
            }
        }
        f.pc++;
        return true;
    }

    // ---- branching ------------------------------------------------------------------------------

    private boolean branch(Frame f, JumpInsnNode j, Cond taken, Cond fall, Deque<Frame> work) {
        if (repeatsInNewIteration(f, taken)) {
            String suffix = " [iteration " + (f.iteration + 1) + "]";
            taken = rekey(taken, suffix);
            fall = rekey(fall, suffix);
        }
        boolean canTake = feasible(f, taken);
        boolean canFall = feasible(f, fall);
        int target = insns.indexOf(j.label);
        if (canTake && canFall) {
            Frame g = f.copy();
            add(g, taken);
            g.pc = target;
            work.push(g);
            add(f, fall);
            f.pc++;
        } else if (canTake) {
            f.pc = target;
        } else if (canFall) {
            f.pc++;
        } else {
            return false; // contradictory path
        }
        return true;
    }

    /**
     * A decision over mutable state (a call, field, array element or local) that was already made
     * before the current loop iteration is a new decision, not the same one: {@code it.hasNext()} is
     * true on the first pass and may be false on the next. Decisions over parameters and constants
     * stay pinned so contradictory combinations are still pruned.
     */
    private boolean repeatsInNewIteration(Frame f, Cond c) {
        if (f.iteration == 0) return false;
        boolean mutable = isMutable(c.left()) || (c.right() != null && isMutable(c.right()));
        if (!mutable) return false;
        for (int i = 0; i < f.barrier && i < f.conds.size(); i++) {
            if (f.conds.get(i).key().equals(c.key())) return true;
        }
        return false;
    }

    private static boolean isMutable(Expr e) {
        return e.mentions(x -> x.kind == Expr.Kind.CALL || x.kind == Expr.Kind.FIELD || x.kind == Expr.Kind.ARRAY_ELEM
                || x.kind == Expr.Kind.LOCAL || x.kind == Expr.Kind.UNKNOWN);
    }

    private static Cond rekey(Cond c, String suffix) {
        return new Cond(c.kind(), c.left().withText(c.left().text + suffix), c.op(), c.right(), c.value(), c.valueExpr(), c.otherCases());
    }

    private void add(Frame f, Cond c) {
        if (isConstant(c) != null) return; // statically decided, not interesting
        for (Cond e : f.conds) if (e.key().equals(c.key())) return;
        f.conds.add(c);
    }

    private boolean feasible(Frame f, Cond c) {
        Boolean k = isConstant(c);
        if (k != null) return k;
        for (Cond e : f.conds) {
            if (e.key().equals(c.key())) return e.value().equals(c.value());
        }
        return true;
    }

    /** Evaluates conditions whose outcome is known statically, or null. */
    private Boolean isConstant(Cond c) {
        boolean want = c.isTrue();
        if (opt.ignoreLogging() && c.kind() == Cond.Kind.BOOL && isLoggingGuard(c.left())) {
            notes.add("Logging guards (isDebugEnabled() etc.) are assumed false; logger calls are not listed as side effects.");
            return !want;
        }
        if (c.kind() == Cond.Kind.BOOL && c.left().kind == Expr.Kind.CONST && c.left().value instanceof Integer i) {
            return (i != 0) == want;
        }
        if (c.kind() != Cond.Kind.CMP) return null;
        Expr l = c.left(), r = c.right();
        if (r.kind == Expr.Kind.NULL) {
            if (l.kind == Expr.Kind.NULL) return eval("==", 0, 0, c.op()) == want;
            if (l.kind == Expr.Kind.THIS || l.kind == Expr.Kind.NEW || l.kind == Expr.Kind.ARRAY_NEW
                    || l.kind == Expr.Kind.CONST || l.kind == Expr.Kind.LAMBDA) {
                return ("!=".equals(c.op())) == want;
            }
            return null;
        }
        if (l.kind == Expr.Kind.CONST && r.kind == Expr.Kind.CONST && l.value instanceof Number a && r.value instanceof Number b) {
            return eval(c.op(), a.doubleValue(), b.doubleValue(), c.op()) == want;
        }
        return null;
    }

    private static boolean eval(String op, double a, double b, String ignored) {
        return switch (op) {
            case "==" -> a == b; case "!=" -> a != b; case "<" -> a < b; case "<=" -> a <= b; case ">" -> a > b; default -> a >= b;
        };
    }

    /**
     * Builds the [taken, fallthrough] conditions for a comparison jump. Each comparison family
     * (==/!=, &lt;/&gt;=, &gt;/&lt;=) is given one canonical operator per method — the first one seen
     * as a fall-through, which is how javac compiles the source condition — so the same test always
     * maps to the same truth-table column.
     */
    private Cond[] cmpConds(Expr a, String jumpOp, Expr b) {
        // constants and null on the right
        if ((a.isConst() && !b.isConst())) {
            Expr t = a; a = b; b = t;
            jumpOp = mirror(jumpOp);
        }
        // e.ordinal() == k  →  e == Enum.K   (javac's direct ordinal switches on nested enums)
        if (isOrdinalCall(a) && b.kind == Expr.Kind.CONST && b.value instanceof Integer k && (jumpOp.equals("==") || jumpOp.equals("!="))) {
            Expr c = enumConstant(a.target, k);
            if (c != null) {
                a = a.target;
                b = c;
            }
        }
        if (b.isBoolean() && b.kind == Expr.Kind.CONST || (a.isBoolean() && b.kind == Expr.Kind.CONST && b.value instanceof Integer)) {
            // flag == true / flag != false
            boolean isTrueConst = !Integer.valueOf(0).equals(b.value);
            boolean takenMeansTrue = jumpOp.equals("==") == isTrueConst;
            return new Cond[]{Cond.bool(a, takenMeansTrue), Cond.bool(a, !takenMeansTrue)};
        }
        String family = a.text + "|" + family(jumpOp) + "|" + b.text;
        final String jo = jumpOp;
        String canon = canonicalOps.computeIfAbsent(family, k -> Cond.negate(jo));
        boolean takenTrue = canon.equals(jumpOp);
        return new Cond[]{Cond.cmp(a, canon, b, takenTrue), Cond.cmp(a, canon, b, !takenTrue)};
    }

    private static String family(String op) {
        return switch (op) {
            case "==", "!=" -> "eq";
            case "<", ">=" -> "lt";
            default -> "gt";
        };
    }

    private static String mirror(String op) {
        return switch (op) {
            case "<" -> ">"; case ">" -> "<"; case "<=" -> ">="; case ">=" -> "<="; default -> op;
        };
    }

    private static String jumpOp(int op) {
        return switch (op) {
            case IFEQ -> "=="; case IFNE -> "!="; case IFLT -> "<"; case IFGE -> ">="; case IFGT -> ">"; default -> "<=";
        };
    }

    private boolean doSwitch(Frame f, Expr key, Map<Integer, LabelNode> cases, LabelNode dflt, Deque<Frame> work) {
        Expr selector = key;
        Map<Integer, Expr> labelExprs = new HashMap<>();
        Map<Integer, String> labelNames = new HashMap<>();
        if (key.kind == Expr.Kind.ARRAY_ELEM && key.args.size() == 2) {
            Expr arr = key.args.get(0), idx = key.args.get(1);
            boolean javacMap = arr.kind == Expr.Kind.STATIC && arr.name.startsWith("$SwitchMap$");
            boolean ecjMap = arr.kind == Expr.Kind.CALL && arr.name.startsWith("$SWITCH_TABLE$");
            if ((javacMap || ecjMap) && idx.kind == Expr.Kind.CALL && "ordinal".equals(idx.name) && idx.target != null) {
                selector = idx.target;
                Map<Integer, Expr> m = SwitchMaps.resolve(repo, arr.owner, arr.name);
                for (var e : m.entrySet()) {
                    labelExprs.put(e.getKey(), e.getValue());
                    labelNames.put(e.getKey(), e.getValue().text);
                }
            }
        } else if (isOrdinalCall(key) && enumConstant(key.target, 0) != null) {
            selector = key.target;
            for (int k : cases.keySet()) {
                Expr c = enumConstant(key.target, k);
                if (c != null) {
                    labelExprs.put(k, c);
                    labelNames.put(k, c.text);
                }
            }
        } else if (key.kind == Expr.Kind.SWITCH_INDEX) {
            selector = key.target;
            @SuppressWarnings("unchecked") List<String> labels = (List<String>) key.extra;
            for (int i = 0; i < labels.size(); i++) {
                labelNames.put(i, labels.get(i));
                if (key.owner != null) labelExprs.put(i, Expr.staticField(key.owner, labels.get(i), "L" + key.owner + ";"));
            }
            labelNames.put(-1, "null");
        } else if ("C".equals(key.desc)) {
            for (int k : cases.keySet()) labelNames.put(k, "'" + (char) k + "'");
        }
        // group case keys that share a target
        Map<LabelNode, List<String>> byTarget = new LinkedHashMap<>();
        Map<LabelNode, Expr> exprByTarget = new HashMap<>();
        for (var e : cases.entrySet()) {
            String label = labelNames.getOrDefault(e.getKey(), String.valueOf(e.getKey()));
            byTarget.computeIfAbsent(e.getValue(), x -> new ArrayList<>()).add(label);
            if (labelExprs.containsKey(e.getKey())) exprByTarget.putIfAbsent(e.getValue(), labelExprs.get(e.getKey()));
        }
        byTarget.computeIfAbsent(dflt, x -> new ArrayList<>()).add("default");

        // already decided on this path?
        Cond prior = null;
        for (Cond c : f.conds) if (c.kind() == Cond.Kind.SWITCH && c.left().equals(selector)) prior = c;

        List<Expr> explicitCases = new ArrayList<>();
        for (var e : byTarget.entrySet()) {
            if (e.getKey() != dflt && exprByTarget.containsKey(e.getKey())) explicitCases.add(exprByTarget.get(e.getKey()));
        }
        for (var e : cases.entrySet()) if (e.getValue() != dflt && labelExprs.containsKey(e.getKey())) {
            Expr le = labelExprs.get(e.getKey());
            if (!explicitCases.contains(le)) explicitCases.add(le);
        }
        List<Frame> branches = new ArrayList<>();
        for (var e : byTarget.entrySet()) {
            String label = String.join(" | ", e.getValue());
            if (prior != null && !Arrays.asList(prior.value().split(" \\| ")).stream().anyMatch(e.getValue()::contains)) continue;
            Frame g = f.copy();
            if (prior == null) g.conds.add(Cond.switchCase(selector, label, exprByTarget.get(e.getKey()),
                    e.getKey() == dflt ? explicitCases : List.of()));
            g.pc = insns.indexOf(e.getKey());
            branches.add(g);
        }
        if (branches.isEmpty()) return false;
        for (int i = branches.size() - 1; i >= 1; i--) work.push(branches.get(i));
        Frame first = branches.get(0);
        f.pc = first.pc;
        f.conds = first.conds;
        return true;
    }

    // ---- invocations ----------------------------------------------------------------------------

    private void invoke(Frame f, MethodInsnNode m) {
        Type[] ats = Type.getArgumentTypes(m.desc);
        Expr[] args = new Expr[ats.length];
        for (int i = ats.length - 1; i >= 0; i--) args[i] = coerce(f.pop(), ats[i].getDescriptor());
        List<Expr> argList = Arrays.asList(args);
        Expr recv = m.getOpcode() == INVOKESTATIC ? null : f.pop();
        Type ret = Type.getReturnType(m.desc);

        if (m.name.equals("<init>")) {
            if (recv != null && recv.isUninitialized()) {
                Expr created = Expr.newObject(m.owner, m.desc, argList);
                if (isStringBuilder(m.owner)) {
                    created = Expr.unknown(args.length == 1 ? args[0].text : "", "L" + m.owner + ";");
                    created = markConcat(created, args.length == 1 ? List.of(args[0]) : List.of());
                }
                replace(f, recv, created);
            } else if (recv != null && recv.kind == Expr.Kind.THIS && !m.owner.equals("java/lang/Object")) {
                f.effects.add(new Effect(Effect.Kind.CALL, recv, m.owner, m.owner.equals(cn.name) ? "this" : "super", m.desc, argList, null));
            }
            return;
        }
        // transparent boxing / unboxing
        if (isBoxing(m) && args.length == 1) {
            f.push(args[0].withDesc(ret.getDescriptor()));
            return;
        }
        if (isUnboxing(m) && recv != null) {
            f.push(recv.withDesc(ret.getDescriptor()));
            return;
        }
        // legacy (pre Java 9) string concatenation via StringBuilder
        if (recv != null && isStringBuilder(m.owner) && "concat".equals(concatMarker(recv))) {
            if (m.name.equals("append") && args.length == 1) {
                List<Expr> parts = new ArrayList<>(concatParts(recv));
                parts.add(args[0]);
                f.push(markConcat(Expr.unknown(joinConcat(parts), "L" + m.owner + ";"), parts));
                return;
            }
            if (m.name.equals("toString")) {
                f.push(Expr.unknown(joinConcat(concatParts(recv)), "Ljava/lang/String;"));
                return;
            }
        }
        if (m.name.startsWith("$SWITCH_TABLE$")) { // ECJ enum switch table
            f.push(Expr.call(null, m.owner, m.name, m.desc, argList));
            return;
        }
        // javac wraps object operands of string concatenation in String.valueOf(..): keep the operand text
        if (m.getOpcode() == INVOKESTATIC && m.owner.equals("java/lang/String") && m.name.equals("valueOf")
                && m.desc.equals("(Ljava/lang/Object;)Ljava/lang/String;")) {
            f.push(Expr.unknown(args[0].text, "Ljava/lang/String;"));
            return;
        }
        Expr call = Expr.call(recv, m.owner, m.name, m.desc, argList);
        boolean loggerCall = opt.ignoreLogging() && LOGGER_TYPES.contains(m.owner);
        if (!loggerCall && !isPureLibraryCall(m) && !(ret.getSort() != Type.VOID && isQuery(m.name))) {
            f.effects.add(new Effect(Effect.Kind.CALL, recv, m.owner, m.name, m.desc, argList, null));
        }
        if (ret.getSort() != Type.VOID) f.push(call);
    }

    private void invokeDynamic(Frame f, InvokeDynamicInsnNode indy) {
        Type[] ats = Type.getArgumentTypes(indy.desc);
        Expr[] args = new Expr[ats.length];
        for (int i = ats.length - 1; i >= 0; i--) args[i] = f.pop();
        Type ret = Type.getReturnType(indy.desc);
        String bsmOwner = indy.bsm.getOwner();
        String bsmName = indy.bsm.getName();
        if (bsmOwner.equals("java/lang/invoke/StringConcatFactory")) {
            List<Expr> parts = new ArrayList<>();
            if (bsmName.equals("makeConcatWithConstants") && indy.bsmArgs.length > 0 && indy.bsmArgs[0] instanceof String recipe) {
                int argI = 0, cstI = 1;
                StringBuilder lit = new StringBuilder();
                for (char c : recipe.toCharArray()) {
                    if (c == '\u0001' || c == '\u0002') {
                        if (lit.length() > 0) { parts.add(Expr.constant(lit.toString(), "Ljava/lang/String;")); lit.setLength(0); }
                        if (c == '\u0001') parts.add(args[argI++]);
                        else parts.add(Expr.constant(String.valueOf(indy.bsmArgs[cstI++]), "Ljava/lang/String;"));
                    } else lit.append(c);
                }
                if (lit.length() > 0) parts.add(Expr.constant(lit.toString(), "Ljava/lang/String;"));
            } else parts.addAll(Arrays.asList(args));
            f.push(Expr.unknown(joinConcat(parts), "Ljava/lang/String;"));
            return;
        }
        if (bsmOwner.equals("java/lang/invoke/LambdaMetafactory") && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
            String text = h.getName().startsWith("lambda$") ? "(lambda)" : Names.simple(h.getOwner()) + "::" + h.getName();
            f.push(Expr.lambda(text, ret.getDescriptor()));
            return;
        }
        if (bsmOwner.equals("java/lang/runtime/SwitchBootstraps") && args.length >= 1) {
            List<String> labels = new ArrayList<>();
            for (Object o : indy.bsmArgs) {
                if (o instanceof Type t) labels.add("instanceof " + Names.typeName(t));
                else if (o instanceof ConstantDynamic cd) labels.add(cd.getName());
                else labels.add(String.valueOf(o));
            }
            String enumOwner = null;
            if (bsmName.equals("enumSwitch") && ats.length > 0 && ats[0].getSort() == Type.OBJECT) enumOwner = ats[0].getInternalName();
            f.push(Expr.switchIndex(args[0], labels, enumOwner));
            return;
        }
        if (ret.getSort() != Type.VOID) {
            f.push(Expr.unknown(indy.name + "(" + String.join(", ", Arrays.stream(args).map(a -> a.text).toList()) + ")", ret.getDescriptor()));
        }
    }

    private static boolean isStringBuilder(String owner) {
        return owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/StringBuffer");
    }

    private final Map<Expr, List<Expr>> concatState = new IdentityHashMap<>();

    private Expr markConcat(Expr e, List<Expr> parts) {
        concatState.put(e, parts);
        return e;
    }

    private String concatMarker(Expr e) {
        return concatState.containsKey(e) ? "concat" : null;
    }

    private List<Expr> concatParts(Expr e) {
        return concatState.getOrDefault(e, List.of());
    }

    private static String joinConcat(List<Expr> parts) {
        if (parts.isEmpty()) return "\"\"";
        return String.join(" + ", parts.stream().map(p -> p.kind == Expr.Kind.BINARY ? "(" + p.text + ")" : p.text).toList());
    }

    private static boolean isBoxing(MethodInsnNode m) {
        return m.getOpcode() == INVOKESTATIC && m.name.equals("valueOf") && m.owner.startsWith("java/lang/")
                && Type.getArgumentTypes(m.desc).length == 1 && Type.getArgumentTypes(m.desc)[0].getSort() <= Type.DOUBLE
                && Type.getArgumentTypes(m.desc)[0].getSort() >= Type.BOOLEAN;
    }

    private static boolean isUnboxing(MethodInsnNode m) {
        return m.getOpcode() == INVOKEVIRTUAL && m.owner.startsWith("java/lang/") && m.desc.startsWith("()")
                && m.name.endsWith("Value") && Type.getReturnType(m.desc).getSort() <= Type.DOUBLE
                && Type.getReturnType(m.desc).getSort() >= Type.BOOLEAN;
    }

    /** Calls we never report as side effects (pure JDK helpers). */
    private static boolean isPureLibraryCall(MethodInsnNode m) {
        String o = m.owner;
        if (o.equals("java/lang/String") || o.equals("java/lang/Math") || o.equals("java/lang/Integer")
                || o.equals("java/lang/Long") || o.equals("java/lang/Boolean") || o.equals("java/lang/Enum")) return true;
        if (o.equals("java/util/Objects")) return true;
        return m.name.equals("ordinal") || m.name.equals("hashCode") || m.name.equals("getClass");
    }

    private static boolean isOrdinalCall(Expr e) {
        return e.kind == Expr.Kind.CALL && "ordinal".equals(e.name) && "()I".equals(e.memberDesc) && e.target != null;
    }

    /** The k-th constant of the enum type of {@code e}, using declaration order in the enum's class file. */
    private Expr enumConstant(Expr e, int k) {
        if (repo == null || e.desc == null || !e.desc.startsWith("L")) return null;
        String owner = Type.getType(e.desc).getInternalName();
        ClassNode en = repo.node(owner);
        if (en == null || (en.access & ACC_ENUM) == 0) return null;
        int i = 0;
        for (FieldNode fn : en.fields) {
            if ((fn.access & ACC_ENUM) == 0) continue;
            if (i++ == k) return Expr.staticField(owner, fn.name, fn.desc);
        }
        return null;
    }

    /** Value-returning calls that read rather than change state; not reported as side effects. */
    private static boolean isQuery(String name) {
        return name.equals("get") || name.startsWith("get") || name.startsWith("is") || name.startsWith("has")
                || name.startsWith("find") || name.startsWith("count") || name.startsWith("exists") || name.startsWith("contains")
                || name.equals("equals") || name.equals("compareTo") || name.equals("size") || name.startsWith("to")
                || name.startsWith("as") || name.startsWith("orElse") || name.equals("stream") || name.equals("iterator")
                || name.equals("hasNext") || name.equals("next") || name.equals("length") || name.equals("charAt")
                || name.equals("matches") || name.equals("startsWith") || name.equals("endsWith") || name.equals("map")
                || name.equals("filter") || name.equals("collect") || name.equals("of") || name.equals("valueOf")
                || name.equals("now") || name.equals("currentTimeMillis") || name.equals("nanoTime");
    }

    private static void replace(Frame f, Expr from, Expr to) {
        for (int i = 0; i < f.stack.size(); i++) if (f.stack.get(i) == from) f.stack.set(i, to);
        for (int i = 0; i < f.locals.length; i++) if (f.locals[i] == from) f.locals[i] = to;
    }

    // ---- arithmetic helpers ---------------------------------------------------------------------

    private static String numDesc(int offset) {
        return switch (offset % 4) { case 0 -> "I"; case 1 -> "J"; case 2 -> "F"; default -> "D"; };
    }

    private void binop(Frame f, String op, int offset) {
        Expr b = f.pop(), a = f.pop();
        f.push(arith(op, a, b, numDesc(offset)));
    }

    private Expr arith(String op, Expr a, Expr b, String desc) {
        if (a.kind == Expr.Kind.CONST && b.kind == Expr.Kind.CONST && a.value instanceof Number x && b.value instanceof Number y) {
            try {
                if (desc.equals("I")) {
                    int r = switch (op) {
                        case "+" -> x.intValue() + y.intValue(); case "-" -> x.intValue() - y.intValue();
                        case "*" -> x.intValue() * y.intValue(); case "/" -> x.intValue() / y.intValue();
                        default -> x.intValue() % y.intValue();
                    };
                    return Expr.constant(r, "I");
                }
                if (desc.equals("J")) {
                    long r = switch (op) {
                        case "+" -> x.longValue() + y.longValue(); case "-" -> x.longValue() - y.longValue();
                        case "*" -> x.longValue() * y.longValue(); case "/" -> x.longValue() / y.longValue();
                        default -> x.longValue() % y.longValue();
                    };
                    return Expr.constant(r, "J");
                }
            } catch (ArithmeticException ignored) {
                // fall through to symbolic
            }
        }
        // i + 1 + 1 -> i + 2
        if (op.equals("+") && b.kind == Expr.Kind.CONST && b.value instanceof Integer bi && a.kind == Expr.Kind.BINARY
                && "+".equals(a.name) && a.args.get(1).kind == Expr.Kind.CONST && a.args.get(1).value instanceof Integer ai) {
            return Expr.binary("+", a.args.get(0), Expr.constant(ai + bi, "I"), desc);
        }
        return Expr.binary(op, a, b, desc);
    }

    private static Number negate(Number n) {
        if (n instanceof Integer i) return -i;
        if (n instanceof Long l) return -l;
        if (n instanceof Float fl) return -fl;
        return -n.doubleValue();
    }

    private void shift(Frame f, String op, int offset) {
        Expr b = f.pop(), a = f.pop();
        f.push(Expr.binary(op, a, b, offset == 0 ? "I" : "J"));
    }

    private void logic(Frame f, String op, int offset) {
        Expr b = f.pop(), a = f.pop();
        String d = a.isBoolean() && b.isBoolean() ? "Z" : (offset == 0 ? "I" : "J");
        f.push(Expr.binary(op, a, b, d));
    }

    private void convert(Frame f, int op) {
        Expr a = f.pop();
        String[] targets = {"J", "F", "D", "I", "F", "D", "I", "J", "D", "I", "J", "F", "B", "C", "S"};
        String to = targets[op - I2L];
        if (a.kind == Expr.Kind.CONST && a.value instanceof Number n) {
            Object v = switch (to) {
                case "J" -> n.longValue(); case "F" -> n.floatValue(); case "D" -> n.doubleValue();
                case "C" -> (char) n.intValue(); default -> n.intValue();
            };
            f.push(Expr.constant(v, to));
            return;
        }
        boolean narrowing = switch (op) { case L2I, F2I, D2I, I2B, I2C, I2S, F2L, D2L, D2F -> true; default -> false; };
        if (narrowing) {
            String cast = switch (to) { case "I" -> "int"; case "J" -> "long"; case "F" -> "float"; case "B" -> "byte"; case "C" -> "char"; default -> "short"; };
            f.push(Expr.unary("(" + cast + ") ", a, to));
        } else f.push(a.withDesc(to));
    }

    private static Expr ldc(Object cst) {
        if (cst instanceof Integer i) return Expr.constant(i, "I");
        if (cst instanceof Long l) return Expr.constant(l, "J");
        if (cst instanceof Float fl) return Expr.constant(fl, "F");
        if (cst instanceof Double d) return Expr.constant(d, "D");
        if (cst instanceof String s) return Expr.constant(s, "Ljava/lang/String;");
        if (cst instanceof Type t) return Expr.constant(t, "Ljava/lang/Class;");
        return Expr.unknown(String.valueOf(cst), null);
    }

    /** Re-types int constants that are really booleans/chars once the target type is known. */
    static Expr coerce(Expr v, String desc) {
        if (desc == null) return v;
        if (v.kind == Expr.Kind.CONST && v.value instanceof Integer i) {
            if (desc.equals("Z")) return Expr.constant(i, "Z");
            if (desc.equals("C")) return Expr.constant((char) i.intValue(), "C");
        }
        if (v.desc == null && desc.length() == 1) return v.withDesc(desc);
        return v;
    }
}
