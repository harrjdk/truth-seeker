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
 * <p>Deliberate simplifications (documented in tool output): only typed catch handlers are entered (via `call throws X` decisions),
 * loops are unrolled at most {@code loopBound} times. Calls on the class's own methods
 * ({@code this.helper(x)}, private/static helpers, getters) are inlined up to {@link #MAX_INLINE_DEPTH}
 * levels so their decisions become part of the caller's paths; all other calls stay symbolic
 * ({@code this.repo.findById(id)}).
 */
public final class PathExplorer {

    /**
     * @param ignoreLogging treat {@code log.isDebugEnabled()}-style guards as false and drop logger calls
     *                      from side effects — they multiply paths without carrying business meaning
     * @param inlineSelfCalls follow calls on the class's own methods (see {@link #MAX_INLINE_DEPTH})
     */
    public record Options(int maxPaths, int loopBound, int maxStepsPerPath, int maxExprLength, boolean ignoreLogging,
                          boolean inlineSelfCalls) {
        public static Options defaults() {
            return new Options(256, 1, 20_000, 140, true, true);
        }

        public Options withMaxPaths(int n) {
            return new Options(n, loopBound, maxStepsPerPath, maxExprLength, ignoreLogging, inlineSelfCalls);
        }

        public Options withInlining(boolean on) {
            return new Options(maxPaths, loopBound, maxStepsPerPath, maxExprLength, ignoreLogging, on);
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
    /** Method whose instructions are currently executing: the analysed method or an inlined self-call. */
    private MethodNode curMn;
    private InsnList insns;
    private final boolean isStatic;
    private final Type[] argTypes;
    static final int MAX_INLINE_DEPTH = 2;
    private static final int MAX_INLINE_INSNS = 1500;
    private final Set<String> notes = new LinkedHashSet<>();
    private final Map<String, String> canonicalOps = new HashMap<>();
    private final List<Path> paths = new ArrayList<>();
    private final List<Expr> params = new ArrayList<>();
    private int pathIds;
    /** Field values of {@code this} known up front (an enum constant evaluated by {@link #enumRule}). */
    private Map<String, Expr> seedFields = Map.of();

    private PathExplorer(ClassRepository repo, ClassNode cn, MethodNode mn, Options opt) {
        this.repo = repo;
        this.cn = cn;
        this.mn = mn;
        this.opt = opt;
        this.curMn = mn;
        this.insns = mn.instructions;
        this.isStatic = (mn.access & ACC_STATIC) != 0;
        this.argTypes = Type.getArgumentTypes(mn.desc);
    }

    public static Result explore(ClassRepository repo, ClassNode cn, MethodNode mn, Options opt) {
        return new PathExplorer(repo, cn, mn, opt).run();
    }

    // ---------------------------------------------------------------------------------------------

    /** Caller state saved while an inlined self-call executes; immutable, shared between path copies. */
    /**
     * @param constructing for an inlined constructor of another class: the object being built (its field writes stay
     *                     inside the call); null otherwise
     * @param effectMark   for an inlined constructor: effects recorded before it, the rest are dropped on return; else -1
     */
    private record Caller(MethodNode method, int pc, List<Expr> stack, Expr[] locals, int[] visits, Caller parent,
                          Expr constructing, int effectMark) {
        int depth() {
            return parent == null ? 1 : parent.depth() + 1;
        }

        boolean contains(MethodNode m) {
            return method == m || (parent != null && parent.contains(m));
        }
    }

    private final class Frame {
        MethodNode method;
        Caller caller;
        int pc;
        ArrayList<Expr> stack;
        Expr[] locals;
        HashMap<String, Expr> thisFields;
        /** Values dereferenced on this path (call receivers, field targets, checked non-null): cannot be null afterwards. */
        HashSet<String> nonNull;
        /** Local-variable display names bound on this path: name → (method that bound it, full expression). */
        HashMap<String, Binding> aliases;
        ArrayList<Cond> conds;
        ArrayList<Effect> effects;
        int[] visits;
        int steps;
        /** Loop iterations re-entered on this path, and how many decisions were made before the latest one. */
        int iteration;
        int barrier;

        Frame copy() {
            Frame f = new Frame();
            f.method = method;
            f.caller = caller;
            f.iteration = iteration;
            f.barrier = barrier;
            f.pc = pc;
            f.stack = new ArrayList<>(stack);
            f.locals = locals.clone();
            f.thisFields = new HashMap<>(thisFields);
            f.nonNull = new HashSet<>(nonNull);
            f.aliases = new HashMap<>(aliases);
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
        if (mn.tryCatchBlocks.stream().anyMatch(t -> t.type == null)) {
            notes.add("finally/catch-all handlers are not entered; only typed catch blocks are explored.");
        }
        Frame init = new Frame();
        init.method = mn;
        init.pc = 0;
        init.stack = new ArrayList<>();
        init.locals = new Expr[Math.max(mn.maxLocals, 1 + argTypes.length * 2)];
        init.thisFields = new HashMap<>(seedFields);
        init.nonNull = new HashSet<>();
        init.aliases = new HashMap<>();
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
        if (curMn.localVariables != null) {
            for (LocalVariableNode lv : curMn.localVariables) {
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
        if (curMn.localVariables != null) {
            for (LocalVariableNode lv : curMn.localVariables) {
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
            curMn = f.method;
            insns = curMn.instructions;
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
                if (v.kind == Expr.Kind.NEW && !v.isUninitialized() && lvName != null) {
                    // keep constructor arguments visible behind the name (needed to see what must be non-null)
                    f.locals[slot] = v.aliased(scopedName(f, lvName, v));
                } else if (v.kind == Expr.Kind.NEW || v.kind == Expr.Kind.ARRAY_NEW || v.kind == Expr.Kind.LAMBDA) {
                    f.locals[slot] = Expr.local(slot, localName(slot, f.pc + 1), v.desc != null ? v.desc : lvDesc);
                } else if (!trivial && lvName != null) {
                    f.locals[slot] = v.aliased(scopedName(f, lvName, v)); // readable name, structure kept
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
                int target = insns.indexOf(((JumpInsnNode) in).label);
                if (target < f.pc) f.nonNull.clear(); // back-edge: loop variables will hold the next element
                f.pc = target;
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
                Expr v = coerce(f.pop(), Type.getReturnType(curMn.desc).getDescriptor());
                if (f.caller != null) {
                    returnToCaller(f, v);
                    return true;
                }
                finish(f, new Path.Outcome(Path.OutcomeKind.RETURN, v, null, null));
                return false;
            }
            case RETURN -> {
                if (f.caller != null) {
                    returnToCaller(f, null);
                    return true;
                }
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
                if (raise(f, v, type, 0)) return true;
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
                dereferenced(f, target);
                String built = constructedKey(f, target, fi.name);
                if (built != null && f.thisFields.containsKey(built)) f.push(f.thisFields.get(built));
                else if (target.kind == Expr.Kind.THIS && f.thisFields.containsKey(fi.name)) f.push(f.thisFields.get(fi.name));
                else f.push(Expr.field(target, fi.owner, fi.name, fi.desc));
            }
            case PUTFIELD -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                Expr v = coerce(f.pop(), fi.desc);
                Expr target = f.pop();
                dereferenced(f, target);
                String built = constructedKey(f, target, fi.name);
                if (built != null) {
                    f.thisFields.put(built, v); // initialising the object an inlined constructor builds
                } else {
                    if (target.kind == Expr.Kind.THIS) f.thisFields.put(fi.name, v);
                    f.effects.add(new Effect(Effect.Kind.FIELD_WRITE, target, fi.owner, fi.name, fi.desc, List.of(), v));
                }
            }
            case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE -> {
                if (!invoke(f, (MethodInsnNode) in, work)) return false;
            }
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
            String suffix = " [iteration " + (evaluations(f, taken) + 1) + "]";
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
            if (target < f.pc) g.nonNull.clear();
            work.push(g);
            add(f, fall);
            f.pc++;
        } else if (canTake) {
            if (target < f.pc) f.nonNull.clear();
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

    /** How many times this same decision was already made on the path (across loop iterations). */
    private static int evaluations(Frame f, Cond c) {
        String base = c.left().text;
        int n = 0;
        for (Cond e : f.conds) {
            if (e.kind() != c.kind() || !Objects.equals(e.op(), c.op())) continue;
            if (!Objects.equals(e.right() == null ? null : e.right().text, c.right() == null ? null : c.right().text)) continue;
            String t = e.left().text;
            if (t.equals(base) || t.startsWith(base + " [iteration ")) n++;
        }
        return n;
    }

    private static boolean isMutable(Expr e) {
        return e.mentions(x -> x.kind == Expr.Kind.CALL || x.kind == Expr.Kind.FIELD || x.kind == Expr.Kind.ARRAY_ELEM
                || x.kind == Expr.Kind.LOCAL || x.kind == Expr.Kind.UNKNOWN);
    }

    private static Cond rekey(Cond c, String suffix) {
        return new Cond(c.kind(), c.left().withText(c.left().text + suffix), c.op(), c.right(), c.value(), c.valueExpr(), c.otherCases());
    }

    private void add(Frame f, Cond c) {
        if (known(f, c) != null) return; // statically decided, not interesting
        for (Cond e : f.conds) if (e.key().equals(c.key())) return;
        f.conds.add(c);
    }

    private boolean feasible(Frame f, Cond c) {
        Boolean k = known(f, c);
        if (k != null) return k;
        for (Cond e : f.conds) {
            if (e.key().equals(c.key())) return e.value().equals(c.value());
        }
        return true;
    }

    private record Binding(MethodNode method, String expansion) {}

    /**
     * The display name for a local: its own name, or {@code method.name} when an inlined method's local of the
     * same name already stands for a different value on this path (two {@code existingPet}s must not merge).
     */
    private String scopedName(Frame f, String name, Expr v) {
        String exp = valueKey(v);
        Binding prev = f.aliases.get(name);
        if (prev == null || prev.method() == curMn || prev.expansion().equals(exp)) {
            f.aliases.put(name, new Binding(curMn, exp));
            return name;
        }
        String scoped = curMn.name + "." + name;
        f.aliases.put(scoped, new Binding(curMn, exp));
        return scoped;
    }

    private static String valueKey(Expr e) {
        return e.expansion() != null ? e.expansion() : e.text;
    }

    private static void dereferenced(Frame f, Expr e) {
        if (e.kind != Expr.Kind.THIS && e.kind != Expr.Kind.STATIC) f.nonNull.add(valueKey(e));
    }

    /** Statically known outcome, including null checks on values this path already dereferenced; else null. */
    private Boolean known(Frame f, Cond c) {
        Boolean k = isConstant(c);
        if (k != null) return k;
        if (c.kind() == Cond.Kind.CMP && c.right().kind == Expr.Kind.NULL && f.nonNull.contains(valueKey(c.left()))) {
            return "!=".equals(c.op()) == c.isTrue();
        }
        return null;
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
            if (l.kind == Expr.Kind.NULL) return eval(c.op(), 0, 0, null) == want;
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
        boolean exhaustive = coversAllEnumConstants(explicitCases);
        List<Frame> branches = new ArrayList<>();
        for (var e : byTarget.entrySet()) {
            List<String> labels = e.getValue();
            if (exhaustive && labels.contains("default")) {
                notes.add("Default branch of a switch covering every enum constant omitted (reachable only if constants are added later).");
                labels = labels.stream().filter(l -> !l.equals("default")).toList();
                if (labels.isEmpty()) continue;
            }
            String label = String.join(" | ", labels);
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

    /** Executes a call. Returns false when the path ended inside it (a precondition helper that always throws). */
    private boolean invoke(Frame f, MethodInsnNode m, Deque<Frame> work) {
        Type[] ats = Type.getArgumentTypes(m.desc);
        Expr[] args = new Expr[ats.length];
        for (int i = ats.length - 1; i >= 0; i--) args[i] = coerce(f.pop(), ats[i].getDescriptor());
        List<Expr> argList = Arrays.asList(args);
        Expr recv = m.getOpcode() == INVOKESTATIC ? null : f.pop();
        if (recv != null && !m.name.equals("<init>")) dereferenced(f, recv);
        Type ret = Type.getReturnType(m.desc);

        if (m.name.equals("<init>")) {
            if (recv != null && recv.isUninitialized()) {
                Expr created = Expr.newObject(m.owner, m.desc, argList);
                if (isStringBuilder(m.owner)) {
                    created = Expr.unknown(args.length == 1 ? args[0].text : "", "L" + m.owner + ";");
                    created = markConcat(created, args.length == 1 ? List.of(args[0]) : List.of());
                }
                replace(f, recv, created);
                if (inlineConstructor(f, m, created, args)) return true;
                if (repo != null && !repo.entry(m.owner).map(ClassRepository.ClassEntry::library).orElse(true)) {
                    forkThrows(f, created, m, work);
                }
            } else if (recv != null && recv.kind == Expr.Kind.THIS && !m.owner.equals("java/lang/Object")) {
                f.effects.add(new Effect(Effect.Kind.CALL, recv, m.owner, m.owner.equals(cn.name) ? "this" : "super", m.desc, argList, null));
            }
            return true;
        }
        // transparent boxing / unboxing
        if (isBoxing(m) && args.length == 1) {
            f.push(args[0].withDesc(ret.getDescriptor()));
            return true;
        }
        if (isUnboxing(m) && recv != null) {
            f.push(recv.withDesc(ret.getDescriptor()));
            return true;
        }
        // legacy (pre Java 9) string concatenation via StringBuilder
        if (recv != null && isStringBuilder(m.owner) && "concat".equals(concatMarker(recv))) {
            if (m.name.equals("append") && args.length == 1) {
                List<Expr> parts = new ArrayList<>(concatParts(recv));
                parts.add(args[0]);
                f.push(markConcat(Expr.unknown(joinConcat(parts), "L" + m.owner + ";"), parts));
                return true;
            }
            if (m.name.equals("toString")) {
                f.push(Expr.unknown(joinConcat(concatParts(recv)), "Ljava/lang/String;"));
                return true;
            }
        }
        if (m.name.startsWith("$SWITCH_TABLE$")) { // ECJ enum switch table
            f.push(Expr.call(null, m.owner, m.name, m.desc, argList));
            return true;
        }
        // javac wraps object operands of string concatenation in String.valueOf(..): keep the operand text
        if (m.getOpcode() == INVOKESTATIC && m.owner.equals("java/lang/String") && m.name.equals("valueOf")
                && m.desc.equals("(Ljava/lang/Object;)Ljava/lang/String;")) {
            f.push(Expr.unknown(args[0].text, "Ljava/lang/String;"));
            return true;
        }
        if (inline(f, m, recv, args)) return true;
        Boolean guarded = guard(f, m, args, work);
        if (guarded != null) return guarded;
        if (enumRule(f, m, recv, args, work)) return true;
        Expr call = Expr.call(recv, m.owner, m.name, m.desc, argList);
        boolean loggerCall = opt.ignoreLogging() && LOGGER_TYPES.contains(m.owner);
        if (!loggerCall && !isPureLibraryCall(m) && !(ret.getSort() != Type.VOID && isQuery(m.name))) {
            f.effects.add(new Effect(Effect.Kind.CALL, recv, m.owner, m.name, m.desc, argList, null));
        }
        if (!loggerCall) forkThrows(f, call, m, work);
        if (ret.getSort() != Type.VOID) f.push(call);
        return true;
    }

    /**
     * Precondition helpers ({@code Objects.requireNonNull}, Spring {@code Assert}, Guava {@code Preconditions},
     * commons-lang {@code Validate}) throw instead of returning: modelled as a branch to the exception they raise.
     * Returns null when {@code m} is not one of them, otherwise whether the path continues.
     */
    private Boolean guard(Frame f, MethodInsnNode m, Expr[] args, Deque<Frame> work) {
        if (m.getOpcode() != INVOKESTATIC || args.length == 0) return null;
        String check = null, exception = null;
        boolean returnsArg = false;
        String owner = m.owner, first = Type.getArgumentTypes(m.desc)[0].getDescriptor();
        boolean objectArg = first.startsWith("L") || first.startsWith("[");
        switch (owner) {
            case "java/util/Objects" -> {
                if (m.name.equals("requireNonNull") && objectArg) { check = "null"; exception = "java/lang/NullPointerException"; returnsArg = true; }
            }
            case "org/springframework/util/Assert" -> {
                switch (m.name) {
                    case "notNull" -> { if (objectArg) { check = "null"; exception = "java/lang/IllegalArgumentException"; } }
                    case "isTrue" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalArgumentException"; } }
                    case "state" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalStateException"; } }
                    case "hasText", "hasLength" -> { if (first.equals("Ljava/lang/String;")) { check = m.name; exception = "java/lang/IllegalArgumentException"; } }
                    default -> {}
                }
            }
            case "com/google/common/base/Preconditions" -> {
                switch (m.name) {
                    case "checkNotNull" -> { if (objectArg) { check = "null"; exception = "java/lang/NullPointerException"; returnsArg = true; } }
                    case "checkArgument" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalArgumentException"; } }
                    case "checkState" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalStateException"; } }
                    default -> {}
                }
            }
            case "org/apache/commons/lang3/Validate", "org/apache/commons/lang/Validate" -> {
                boolean lang3 = owner.contains("lang3");
                switch (m.name) {
                    case "notNull" -> { if (objectArg) { check = "null"; exception = lang3 ? "java/lang/NullPointerException" : "java/lang/IllegalArgumentException"; returnsArg = lang3; } }
                    case "isTrue" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalArgumentException"; } }
                    case "validState" -> { if (first.equals("Z")) { check = "true"; exception = "java/lang/IllegalStateException"; } }
                    default -> {}
                }
            }
            default -> {}
        }
        if (check == null) return null;
        Expr a = args[0];
        if (check.equals("null") && args.length == 1 && isSyntheticNullCheck(f.pc)) {
            // javac's own null checks (pattern switches, qualified new, method refs): DUP; requireNonNull; POP
            dereferenced(f, a);
            f.push(a);
            return true;
        }
        Cond fail, pass;
        switch (check) {
            case "null" -> {
                Cond[] cs = cmpConds(a, "==", Expr.nul());
                fail = cs[0];
                pass = cs[1];
            }
            case "true" -> {
                if (a.isConst()) {
                    boolean holds = a.value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(a.value);
                    fail = holds ? null : Cond.bool(Expr.constant(1, "Z"), true);
                    pass = holds ? Cond.bool(Expr.constant(1, "Z"), true) : null;
                } else {
                    pass = Cond.bool(a, true);
                    fail = Cond.bool(a, false);
                }
            }
            default -> { // hasText / hasLength: same predicate as Spring's StringUtils, which the test generator understands
                Expr p = Expr.call(null, "org/springframework/util/StringUtils", check, "(Ljava/lang/String;)Z", List.of(a));
                pass = Cond.bool(p, true);
                fail = Cond.bool(p, false);
            }
        }
        boolean canFail = fail != null && feasible(f, fail);
        boolean canPass = pass != null && feasible(f, pass);
        Expr thrown = args.length >= 2 && "Ljava/lang/String;".equals(args[1].desc)
                ? Expr.newObject(exception, "(Ljava/lang/String;)V", List.of(args[1]))
                : Expr.newObject(exception, "()V", List.of());
        if (canFail && !canPass) {
            add(f, fail);
            if (raise(f, thrown, exception, -1)) return true;
            finish(f, new Path.Outcome(Path.OutcomeKind.THROW, thrown, exception, null));
            return false;
        }
        if (canFail) {
            Frame g = f.copy();
            add(g, fail);
            if (raise(g, thrown, exception, 0)) work.push(g);
            else finish(g, new Path.Outcome(Path.OutcomeKind.THROW, thrown, exception, null));
        }
        if (!canPass) return false; // contradictory path
        add(f, pass);
        if (check.equals("null")) dereferenced(f, a);
        if (Type.getReturnType(m.desc).getSort() != Type.VOID) f.push(returnsArg ? a : Expr.unknown("?", Type.getReturnType(m.desc).getDescriptor()));
        return true;
    }

    // ---- exceptions and catch handlers -----------------------------------------------------------

    /** A typed catch handler covering an instruction: in the current method (depth 0) or an inlining caller. */
    private record Handler(int depth, String type, int target) {}

    /** Typed handlers that would see an exception thrown at the current instruction, innermost first, in table order. */
    private List<Handler> handlers(Frame f) {
        List<Handler> out = new ArrayList<>();
        MethodNode m = f.method;
        int pc = f.pc;
        Caller c = f.caller;
        for (int depth = 0; m != null; depth++) {
            InsnList il = m.instructions;
            for (TryCatchBlockNode t : m.tryCatchBlocks) {
                if (t.type == null) continue; // finally / catch-all: not entered
                if (il.indexOf(t.start) <= pc && pc < il.indexOf(t.end)) out.add(new Handler(depth, t.type, il.indexOf(t.handler)));
            }
            if (c == null) break;
            m = c.method();
            pc = c.pc();
            c = c.parent();
        }
        return out;
    }

    /**
     * Moves {@code f} into the handler that catches {@code type} thrown at its current instruction, unwinding
     * inlined calls as needed. {@code adjust} compensates for the pc increment that follows a call instruction.
     */
    private boolean raise(Frame f, Expr exception, String type, int adjust) {
        for (Handler h : handlers(f)) {
            if (!isSubtype(type, h.type())) continue;
            enterHandler(f, h, exception, adjust);
            return true;
        }
        return false;
    }

    private void enterHandler(Frame f, Handler h, Expr exception, int adjust) {
        for (int i = 0; i < h.depth(); i++) {
            Caller c = f.caller;
            f.caller = c.parent();
            f.method = c.method();
            f.locals = c.locals().clone();
            f.visits = c.visits().clone();
        }
        f.stack = new ArrayList<>();
        f.push(exception);
        f.pc = h.target() + adjust;
        notes.add("Exceptions thrown inside try blocks are followed into their catch handlers (`<call> throws <Type>` decisions).");
    }

    /** How the exception caught from a {@code call throws X} decision appears in the handler's expressions. */
    public static String thrownText(String exceptionType, Expr call) {
        return Names.simple(exceptionType) + " thrown by " + call.text;
    }

    private static final Set<String> GENERIC_EXCEPTIONS = Set.of("java/lang/Exception", "java/lang/Throwable", "java/lang/RuntimeException", "java/lang/Error");

    /**
     * For a call inside try blocks: one extra path per handler that could catch what the call throws, carrying a
     * {@code call throws X} decision. Checked exceptions only when the callee declares them; JDK calls only for
     * specific unchecked types (NumberFormatException around Integer.parseInt, ...), never for catch (Exception).
     */
    private void forkThrows(Frame f, Expr call, MethodInsnNode m, Deque<Frame> work) {
        List<Handler> hs = handlers(f);
        if (hs.isEmpty()) return;
        boolean jdk = m.owner.startsWith("java/") || m.owner.startsWith("javax/") || m.owner.startsWith("jdk/");
        if (!m.name.equals("<init>") && Type.getArgumentTypes(m.desc).length == 0 && isQuery(m.name) && !jdk && declaredExceptions(m).isEmpty()) return;
        List<String> declared = declaredExceptions(m);
        Set<Integer> seen = new HashSet<>();
        for (Handler h : hs) {
            String thrown = null;
            for (String d : declared) {
                if (isSubtype(d, h.type())) { thrown = d; break; }
                if (isSubtype(h.type(), d)) { thrown = h.type(); break; }
            }
            if (thrown == null && isUnchecked(h.type())) {
                if (jdk && GENERIC_EXCEPTIONS.contains(h.type())) continue;
                boolean parser = m.name.startsWith("parse") || m.name.equals("valueOf");
                if (jdk && isPureLibraryCall(m) && !parser) continue;
                // value types (dates, decimals, UUIDs) only fail on bad input when parsed or converted
                if (jdk && isJdkValueType(m.owner) && !parser && !m.name.equals("of") && !m.name.startsWith("from")) continue;
                thrown = h.type();
            }
            if (thrown == null && !jdk && (h.type().equals("java/lang/Exception") || h.type().equals("java/lang/Throwable"))) {
                thrown = "java/lang/RuntimeException";
            }
            if (thrown == null) continue;
            // the first handler in table order that accepts the type is the one that runs
            Handler catching = null;
            for (Handler o : hs) if (isSubtype(thrown, o.type())) { catching = o; break; }
            if (catching == null || !seen.add(catching.depth() * 100_000 + catching.target())) continue;
            Frame g = f.copy();
            g.conds.add(Cond.throwing(call, thrown));
            enterHandler(g, catching, Expr.unknown(thrownText(thrown, call), "L" + thrown + ";"), 0);
            work.push(g);
            // a JDK parser fails or not depending on its input: the normal path must say it did not throw
            if (jdk) add(f, Cond.throwing(call, thrown, false));
        }
    }

    /** Exceptions the called method declares (application or library bytecode in the archive, else the JDK). */
    private List<String> declaredExceptions(MethodInsnNode m) {
        if (repo != null) {
            for (String c = m.owner; c != null; ) {
                ClassNode node = repo.node(c);
                if (node == null) break;
                for (MethodNode mm : node.methods) if (mm.name.equals(m.name) && mm.desc.equals(m.desc)) return mm.exceptions;
                c = node.superName;
            }
        }
        Class<?> k = jdkClass(m.owner);
        if (k != null) {
            try {
                for (java.lang.reflect.Executable e : m.name.equals("<init>") ? k.getDeclaredConstructors() : k.getMethods()) {
                    boolean ctor = e instanceof java.lang.reflect.Constructor;
                    if (!ctor && !e.getName().equals(m.name)) continue;
                    String desc = ctor ? Type.getConstructorDescriptor((java.lang.reflect.Constructor<?>) e)
                            : Type.getMethodDescriptor((java.lang.reflect.Method) e);
                    if (!desc.equals(m.desc)) continue;
                    List<String> out = new ArrayList<>();
                    for (Class<?> x : e.getExceptionTypes()) out.add(Type.getInternalName(x));
                    return out;
                }
            } catch (LinkageError | SecurityException ignored) {
                // not resolvable here
            }
        }
        return List.of();
    }

    private static Class<?> jdkClass(String internal) {
        if (!(internal.startsWith("java/") || internal.startsWith("javax/"))) return null;
        try {
            return Class.forName(internal.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Is {@code sub} the same as or a subclass of {@code sup}? Resolved through the archive, then the JDK. */
    private boolean isSubtype(String sub, String sup) {
        if (sup.equals("java/lang/Throwable") || sub.equals(sup)) return true;
        for (String c = sub; c != null; ) {
            if (c.equals(sup)) return true;
            ClassNode node = repo == null ? null : repo.node(c);
            if (node != null) {
                c = node.superName;
                continue;
            }
            Class<?> k = jdkClass(c), s = jdkClass(sup);
            return k != null && s != null && s.isAssignableFrom(k);
        }
        return false;
    }

    private boolean isUnchecked(String type) {
        return !type.equals("java/lang/Throwable") && (isSubtype(type, "java/lang/RuntimeException") || isSubtype(type, "java/lang/Error"));
    }

    private boolean isSyntheticNullCheck(int pc) {
        AbstractInsnNode prev = insns.get(pc).getPrevious(), next = insns.get(pc).getNext();
        while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return prev != null && prev.getOpcode() == DUP && next != null && next.getOpcode() == POP;
    }

    /**
     * Inlines a call on the class's own code: {@code this.m(..)} (virtual calls resolve against the class
     * under analysis, which is what a unit test instantiates), {@code super.m(..)} and static helpers.
     * The callee's decisions and effects become part of the caller's path.
     */
    private boolean inline(Frame f, MethodInsnNode m, Expr recv, Expr[] args) {
        boolean selfStatic = m.getOpcode() == INVOKESTATIC && m.owner.equals(cn.name);
        boolean onThis = recv != null && recv.kind == Expr.Kind.THIS && m.getOpcode() != INVOKESTATIC;
        if (!opt.inlineSelfCalls() || (!selfStatic && !onThis)) return false;
        if (f.caller != null && f.caller.depth() >= MAX_INLINE_DEPTH) {
            notes.add("Calls on the class's own methods are inlined up to " + MAX_INLINE_DEPTH + " levels deep; deeper ones stay symbolic.");
            return false;
        }
        MethodNode target = resolveOwn(m.getOpcode() == INVOKESPECIAL || selfStatic ? m.owner : cn.name, m.name, m.desc);
        if (target == null || target.instructions.size() == 0 || target.instructions.size() > MAX_INLINE_INSNS
                || (target.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) return false;
        if (target == f.method || (f.caller != null && f.caller.contains(target))) return false; // recursion
        f.caller = new Caller(f.method, f.pc, List.copyOf(f.stack), f.locals, f.visits, f.caller, null, -1);
        Expr[] locals = new Expr[Math.max(target.maxLocals, 1 + args.length * 2)];
        int slot = 0;
        if ((target.access & ACC_STATIC) == 0) locals[slot++] = recv;
        Type[] ats = Type.getArgumentTypes(target.desc);
        for (int i = 0; i < args.length; i++) {
            locals[slot] = args[i];
            slot += ats[i].getSize();
        }
        f.method = target;
        f.stack = new ArrayList<>();
        f.locals = locals;
        f.visits = new int[target.instructions.size()];
        f.pc = -1; // step() advances to the callee's first instruction
        notes.add("Calls on the class's own methods are inlined (up to " + MAX_INLINE_DEPTH + " levels); their decisions appear in these paths. Pass inlineSelfCalls=false for the method-local view.");
        return true;
    }

    private void returnToCaller(Frame f, Expr value) {
        Caller c = f.caller;
        if (c.effectMark() >= 0) {
            while (f.effects.size() > c.effectMark()) f.effects.remove(f.effects.size() - 1);
            String prefix = constructedPrefix(c.constructing());
            f.thisFields.keySet().removeIf(k -> k.startsWith(prefix));
        }
        f.caller = c.parent();
        f.method = c.method();
        f.stack = new ArrayList<>(c.stack());
        f.locals = c.locals().clone();
        f.visits = c.visits().clone();
        f.pc = c.pc() + 1;
        if (value != null) f.push(value);
    }

    // ---- constructors of other application classes ---------------------------------------------

    /**
     * Inlines the constructor of an application class ({@code new HandlingEvent(..)}) so its validation becomes part
     * of the path: guards, {@code throw}s and decisions on the arguments. Its field writes and calls stay inside it.
     */
    private boolean inlineConstructor(Frame f, MethodInsnNode m, Expr created, Expr[] args) {
        if (!opt.inlineSelfCalls() || repo == null) return false;
        if (repo.entry(m.owner).map(ClassRepository.ClassEntry::library).orElse(true)) return false;
        ClassNode node = repo.node(m.owner);
        if (node == null || (node.access & (ACC_ENUM | ACC_ABSTRACT | ACC_INTERFACE)) != 0) return false;
        if (f.caller != null && f.caller.depth() >= MAX_INLINE_DEPTH) return false;
        MethodNode target = null;
        for (MethodNode c : node.methods) if (c.name.equals("<init>") && c.desc.equals(m.desc)) target = c;
        if (target == null || target.instructions.size() == 0 || target.instructions.size() > MAX_INLINE_INSNS) return false;
        if (target == f.method || (f.caller != null && f.caller.contains(target))) return false;
        f.caller = new Caller(f.method, f.pc, List.copyOf(f.stack), f.locals, f.visits, f.caller, created, f.effects.size());
        Expr[] locals = new Expr[Math.max(target.maxLocals, 1 + args.length * 2)];
        int slot = 0;
        locals[slot++] = created;
        Type[] ats = Type.getArgumentTypes(target.desc);
        for (int i = 0; i < args.length; i++) {
            locals[slot] = args[i];
            slot += ats[i].getSize();
        }
        f.method = target;
        f.stack = new ArrayList<>();
        f.locals = locals;
        f.visits = new int[target.instructions.size()];
        f.pc = -1;
        notes.add("Constructors of other application classes are inlined: their validation appears as decisions on the arguments.");
        return true;
    }

    private static String constructedPrefix(Expr built) {
        return "\u0000" + System.identityHashCode(built) + ".";
    }

    /** thisFields key for a field of the object the current inlined constructor builds, or null. */
    private static String constructedKey(Frame f, Expr target, String field) {
        Expr built = f.caller == null ? null : f.caller.constructing();
        return built != null && target == built ? constructedPrefix(built) + field : null;
    }

    // ---- rules held by enum constants -----------------------------------------------------------

    /** One constant of an application enum: its name, the class holding its methods, its final field values. */
    private record EnumConst(String name, String type, Map<String, Expr> fields) {}

    private static final Map<String, List<EnumConst>> ENUM_CONSTS = new java.util.concurrent.ConcurrentHashMap<>();
    private int nesting;

    /**
     * A no-argument query on an application enum value ({@code type.requiresVoyage()}): evaluated for every constant
     * from the enum's own bytecode. When the answer differs between constants, the path forks into groups of
     * constants ({@code type in {LOAD, UNLOAD}}); a constant receiver just yields its answer.
     */
    private boolean enumRule(Frame f, MethodInsnNode m, Expr recv, Expr[] args, Deque<Frame> work) {
        if (!opt.inlineSelfCalls() || repo == null || recv == null || args.length != 0 || nesting > 1) return false;
        if (m.getOpcode() != INVOKEVIRTUAL && m.getOpcode() != INVOKEINTERFACE) return false;
        String enumType = recv.desc != null && recv.desc.startsWith("L") ? Type.getType(recv.desc).getInternalName() : m.owner;
        ClassNode en = repo.node(enumType);
        if (en == null || (en.access & ACC_ENUM) == 0) return false;
        if (repo.entry(enumType).map(ClassRepository.ClassEntry::library).orElse(true)) return false;
        List<EnumConst> all = enumConstants(en);
        if (all.isEmpty()) return false;

        List<EnumConst> candidates = new ArrayList<>();
        Cond prior = null;
        int priorAt = -1;
        if (recv.kind == Expr.Kind.STATIC && enumType.equals(recv.owner)) {
            for (EnumConst c : all) if (c.name().equals(recv.name)) candidates.add(c);
        } else {
            for (int i = 0; i < f.conds.size(); i++) {
                Cond c = f.conds.get(i);
                if (c.kind() == Cond.Kind.SWITCH && c.left().equals(recv)) { prior = c; priorAt = i; }
            }
            for (EnumConst c : all) {
                String label = Expr.staticField(enumType, c.name(), "L" + enumType + ";").text;
                if (prior != null) {
                    List<String> taken = Arrays.asList(prior.value().split(" \\| "));
                    if (taken.contains("default")) {
                        if (prior.otherCases().stream().anyMatch(o -> o.text.equals(label))) continue;
                    } else if (!taken.contains(label)) continue;
                }
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) return false;
        Map<String, List<EnumConst>> byValue = new LinkedHashMap<>();
        Map<String, Expr> values = new HashMap<>();
        for (EnumConst c : candidates) {
            Expr v = evaluateOn(c, m);
            if (v == null) return false;
            byValue.computeIfAbsent(v.text, k -> new ArrayList<>()).add(c);
            values.put(v.text, v);
        }
        notes.add("Queries on enum values are evaluated per constant from the enum's bytecode (`type in {..}` decisions).");
        if (byValue.size() == 1) {
            f.push(values.values().iterator().next());
            return true;
        }
        List<Frame> branches = new ArrayList<>();
        for (int i = 0; i < byValue.size(); i++) {
            Frame g = i == 0 ? f : f.copy();
            if (i > 0) g.pc++; // resumes after the call (the current frame's pc advances when the step returns)
            branches.add(g);
        }
        int b = 0;
        for (var e : byValue.entrySet()) {
            Frame g = branches.get(b++);
            List<String> labels = e.getValue().stream().map(c -> Expr.staticField(enumType, c.name(), "L" + enumType + ";").text).toList();
            EnumConst first = e.getValue().get(0);
            Cond c = Cond.switchCase(recv, String.join(" | ", labels), Expr.staticField(enumType, first.name(), "L" + enumType + ";"), List.of());
            if (prior != null) g.conds.set(priorAt, c);
            else g.conds.add(c);
            g.push(values.get(e.getKey()));
        }
        for (int i = branches.size() - 1; i >= 1; i--) work.push(branches.get(i));
        return true;
    }

    /** The constant result of the enum method {@code m} on constant {@code c}, or null when it is not one. */
    private Expr evaluateOn(EnumConst c, MethodInsnNode m) {
        MethodNode target = null;
        ClassNode owner = null;
        for (String t = c.type(); t != null && target == null; ) {
            ClassNode node = repo.node(t);
            if (node == null) break;
            for (MethodNode mm : node.methods) if (mm.name.equals(m.name) && mm.desc.equals(m.desc)) { target = mm; owner = node; }
            t = node.superName;
        }
        if (target == null || (target.access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) != 0 || target.instructions.size() > 200) return null;
        PathExplorer sub = new PathExplorer(repo, owner, target, new Options(16, 1, 2_000, opt.maxExprLength(), true, true));
        sub.nesting = nesting + 1;
        sub.seedFields = c.fields();
        Result r = sub.run();
        Expr value = null;
        for (Path p : r.paths()) {
            Path.Outcome o = p.outcome();
            if (o.kind() != Path.OutcomeKind.RETURN || o.value() == null) return null;
            Expr v = o.value();
            boolean constant = v.isConst() || v.kind == Expr.Kind.NULL || (v.kind == Expr.Kind.STATIC && v.desc != null && v.desc.equals("L" + v.owner + ";"));
            if (!constant || (value != null && !value.text.equals(v.text))) return null;
            value = v;
        }
        return value;
    }

    /**
     * Constants of an application enum with the final field values its {@code <clinit>} passes to the constructor,
     * e.g. {@code LOAD(true)} with {@code this.voyageRequired = voyageRequired}. Non-constant arguments are left out.
     */
    private List<EnumConst> enumConstants(ClassNode en) {
        return ENUM_CONSTS.computeIfAbsent(en.name, k -> {
            List<EnumConst> out = new ArrayList<>();
            MethodNode clinit = null;
            for (MethodNode mm : en.methods) if (mm.name.equals("<clinit>")) clinit = mm;
            if (clinit == null) return out;
            List<Object> pending = null;
            String newType = null;
            int depth = 0;
            for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
                int op = in.getOpcode();
                if (op < 0) continue;
                if (pending == null) {
                    if (op == NEW) {
                        String t = ((TypeInsnNode) in).desc;
                        ClassNode tn = repo.node(t);
                        if (t.equals(en.name) || (tn != null && en.name.equals(tn.superName))) { pending = new ArrayList<>(); newType = t; depth = 0; }
                    }
                    continue;
                }
                if (op == DUP && pending.isEmpty() && depth == 0) continue;
                if (op == NEW) { depth++; pending.add(UNKNOWN_ARG); continue; }
                if (op == INVOKESPECIAL && ((MethodInsnNode) in).name.equals("<init>")) {
                    MethodInsnNode init = (MethodInsnNode) in;
                    if (depth > 0) { depth--; continue; }
                    AbstractInsnNode next = init.getNext();
                    while (next != null && next.getOpcode() < 0) next = next.getNext();
                    if (next != null && next.getOpcode() == PUTSTATIC && ((FieldInsnNode) next).owner.equals(en.name)) {
                        String name = ((FieldInsnNode) next).name;
                        out.add(new EnumConst(name, newType, constructorFields(newType.equals(en.name) ? en : repo.node(newType), init.desc, pending)));
                    }
                    pending = null;
                    continue;
                }
                if (depth > 0) continue;
                Object v = constantPushed(in);
                pending.add(v != null ? v : UNKNOWN_ARG);
            }
            return out;
        });
    }

    private static final Object UNKNOWN_ARG = new Object();
    private static final Object NULL_ARG = new Object();

    private static Object constantPushed(AbstractInsnNode in) {
        int op = in.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == LCONST_0 || op == LCONST_1) return (long) (op - LCONST_0);
        if (op == BIPUSH || op == SIPUSH) return ((IntInsnNode) in).operand;
        if (op == LDC) return ((LdcInsnNode) in).cst instanceof Type ? null : ((LdcInsnNode) in).cst;
        if (op == ACONST_NULL) return NULL_ARG;
        if (op == GETSTATIC) {
            FieldInsnNode fi = (FieldInsnNode) in;
            if (fi.desc.equals("L" + fi.owner + ";")) return Expr.staticField(fi.owner, fi.name, fi.desc);
        }
        return null;
    }

    /** Fields the enum constructor sets straight from its arguments or constants ({@code this.f = arg}). */
    private static Map<String, Expr> constructorFields(ClassNode owner, String desc, List<Object> argValues) {
        Map<String, Expr> fields = new HashMap<>();
        if (owner == null) return fields;
        MethodNode ctor = null;
        for (MethodNode mm : owner.methods) if (mm.name.equals("<init>") && mm.desc.equals(desc)) ctor = mm;
        if (ctor == null) return fields;
        Type[] ats = Type.getArgumentTypes(desc);
        Map<Integer, Integer> argAtSlot = new HashMap<>();
        for (int i = 0, slot = 1; i < ats.length; slot += ats[i].getSize(), i++) argAtSlot.put(slot, i);
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) if (in.getOpcode() >= 0) real.add(in);
        for (int i = 2; i < real.size(); i++) {
            AbstractInsnNode a = real.get(i - 2), b = real.get(i - 1), put = real.get(i);
            if (put.getOpcode() != PUTFIELD || a.getOpcode() != ALOAD || ((VarInsnNode) a).var != 0) continue;
            FieldInsnNode fi = (FieldInsnNode) put;
            Object v = null;
            if (b instanceof VarInsnNode load && load.getOpcode() >= ILOAD && load.getOpcode() <= ALOAD && argAtSlot.containsKey(load.var)) {
                int idx = argAtSlot.get(load.var);
                if (idx < argValues.size()) v = argValues.get(idx);
            } else v = constantPushed(b);
            if (v == null || v == UNKNOWN_ARG) continue;
            if (v == NULL_ARG) fields.put(fi.name, Expr.nul());
            else if (v instanceof Expr e) fields.put(fi.name, e);
            else fields.put(fi.name, Expr.constant(v, fi.desc));
        }
        return fields;
    }

    /** Method declared by {@code owner} or its application superclasses (library classes are never inlined). */
    private MethodNode resolveOwn(String owner, String name, String desc) {
        for (String c = owner; c != null; ) {
            if (repo.entry(c).map(ClassRepository.ClassEntry::library).orElse(true)) return null;
            ClassNode node = repo.node(c);
            for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
            c = node.superName;
        }
        return null;
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
    private static boolean isJdkValueType(String owner) {
        return owner.startsWith("java/time/") || owner.startsWith("java/math/") || owner.equals("java/util/UUID")
                || owner.equals("java/lang/Double") || owner.equals("java/lang/Float") || owner.equals("java/lang/Short")
                || owner.equals("java/lang/Byte") || owner.equals("java/lang/Character") || owner.equals("java/util/Date");
    }

    private static boolean isPureLibraryCall(MethodInsnNode m) {
        String o = m.owner;
        if (o.equals("java/lang/String") || o.equals("java/lang/Math") || o.equals("java/lang/Integer")
                || o.equals("java/lang/Long") || o.equals("java/lang/Boolean") || o.equals("java/lang/Enum")) return true;
        if (o.equals("java/util/Objects")) return true;
        return m.name.equals("ordinal") || m.name.equals("hashCode") || m.name.equals("getClass");
    }

    /** True when the explicit case constants are every constant the enum declares. */
    private boolean coversAllEnumConstants(List<Expr> cases) {
        if (repo == null || cases.isEmpty() || cases.stream().anyMatch(c -> c.kind != Expr.Kind.STATIC)) return false;
        String owner = cases.get(0).owner;
        ClassNode en = repo.node(owner);
        if (en == null || (en.access & ACC_ENUM) == 0) return false;
        Set<String> declared = new HashSet<>();
        for (FieldNode fn : en.fields) if ((fn.access & ACC_ENUM) != 0) declared.add(fn.name);
        Set<String> covered = new HashSet<>();
        for (Expr c : cases) if (owner.equals(c.owner)) covered.add(c.name);
        return !declared.isEmpty() && covered.containsAll(declared);
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
