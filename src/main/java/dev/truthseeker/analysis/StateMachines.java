package dev.truthseeker.analysis;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import dev.truthseeker.symbolic.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Infers state machines from enum-valued state. A transition is recorded on any path that
 * <em>guards</em> on a state value (==, equals, switch) and/or <em>writes</em> a new state
 * (field write or {@code setXxx(...)} setter call) — which covers both rich domain models
 * ({@code this.status = SHIPPED}) and the anemic-model + service-layer style common in legacy
 * Spring/EJB code ({@code if (order.getStatus() == PAID) order.setStatus(SHIPPED)}).
 * Methods that return a state from a switch over a state ({@code next()} on an enum) count too.
 */
public final class StateMachines {
    private StateMachines() {}

    public record Transition(String slot, String from, String to, String trigger, String guard, boolean unguarded) {}

    public record Rejection(String slot, Set<String> states, String trigger, String outcome) {}

    public static final class Machine {
        final String enumType;
        final Set<String> states = new LinkedHashSet<>();
        final Set<String> initial = new LinkedHashSet<>();
        final Set<String> slots = new LinkedHashSet<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<Rejection> rejections = new ArrayList<>();
        final Set<String> computedWrites = new LinkedHashSet<>();

        Machine(String enumType) {
            this.enumType = enumType;
        }
    }

    public record Options(String enumFilter, String stateField, String packagePrefix, int maxPathsPerMethod) {}

    // ---------------------------------------------------------------------------------------------

    public static Map<String, Machine> analyze(ClassRepository repo, List<ClassNode> classes, Options opt) {
        Map<String, Machine> machines = new LinkedHashMap<>();
        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) continue;
                if (mn.name.equals("<clinit>") || mn.name.equals("values") || mn.name.equals("valueOf")) continue;
                if (!mentionsStateLikeConstant(mn, opt)) continue;
                PathExplorer.Result r = PathExplorer.explore(repo, cn, mn,
                        PathExplorer.Options.defaults().withMaxPaths(opt.maxPathsPerMethod()));
                for (Path p : r.paths()) analyzePath(repo, cn, mn, p, opt, machines);
            }
        }
        for (Machine m : machines.values()) {
            ClassNode enumNode = repo.node(m.enumType);
            List<String> declared = ClassInspector.enumConstants(enumNode);
            if (!declared.isEmpty()) {
                Set<String> all = new LinkedHashSet<>(declared);
                all.addAll(m.states);
                m.states.clear();
                m.states.addAll(all);
            }
            expandWildcards(m);
        }
        return machines;
    }

    /** Cheap pre-filter: only explore methods that touch an enum constant or a switch map. */
    private static boolean mentionsStateLikeConstant(MethodNode mn, Options opt) {
        for (AbstractInsnNode in : mn.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                    && (f.desc.equals("L" + f.owner + ";") || f.name.startsWith("$SwitchMap$"))) return true;
            if (in instanceof MethodInsnNode m && m.name.startsWith("$SWITCH_TABLE$")) return true;
            if (in instanceof InvokeDynamicInsnNode indy && indy.bsm.getName().equals("enumSwitch")) return true;
            if (opt.stateField() != null && in instanceof FieldInsnNode f && f.name.equals(opt.stateField())) return true;
            if (opt.stateField() != null && in instanceof MethodInsnNode m && m.name.equalsIgnoreCase("set" + opt.stateField())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------

    private record StateRef(String enumType, String name) {}

    private static void analyzePath(ClassRepository repo, ClassNode cn, MethodNode mn, Path p, Options opt, Map<String, Machine> machines) {
        // slot -> positive states / negative states
        Map<String, Set<String>> pos = new LinkedHashMap<>();
        Map<String, Set<String>> neg = new LinkedHashMap<>();
        Map<String, String> slotEnum = new HashMap<>();
        List<String> otherGuards = new ArrayList<>();
        boolean lastDecisionWasState = false;

        for (Cond c : p.conds()) {
            boolean used = false;
            if (c.kind() == Cond.Kind.CMP && (c.op().equals("==") || c.op().equals("!="))) {
                boolean eq = c.op().equals("==") == c.isTrue();
                used = guard(repo, cn, c.left(), c.right(), eq, opt, pos, neg, slotEnum)
                        || guard(repo, cn, c.right(), c.left(), eq, opt, pos, neg, slotEnum);
            } else if (c.kind() == Cond.Kind.BOOL && c.left().kind == Expr.Kind.CALL && "equals".equals(c.left().name)
                    && c.left().target != null && c.left().args.size() == 1) {
                Expr a = c.left().target, b = c.left().args.get(0);
                used = guard(repo, cn, a, b, c.isTrue(), opt, pos, neg, slotEnum) || guard(repo, cn, b, a, c.isTrue(), opt, pos, neg, slotEnum);
            } else if (c.kind() == Cond.Kind.SWITCH) {
                String slot = slotOf(cn, c.left());
                String enumType = enumTypeOf(c.left(), c.valueExpr());
                if (slot != null && enumType != null && accepts(enumType, opt) && !c.value().contains("default")) {
                    for (String label : c.value().split(" \\| ")) {
                        pos.computeIfAbsent(slot, k -> new LinkedHashSet<>()).add(constName(label));
                    }
                    slotEnum.put(slot, enumType);
                    used = true;
                } else if (slot != null && enumType != null && accepts(enumType, opt)) {
                    used = true; // default branch: matches none of the explicit cases
                    slotEnum.put(slot, enumType);
                    for (Expr other : c.otherCases()) {
                        if (other.name != null) neg.computeIfAbsent(slot, k -> new LinkedHashSet<>()).add(other.name);
                    }
                }
            }
            if (!used) otherGuards.add(c.describe());
            lastDecisionWasState = used;
        }

        // writes: last write per slot wins
        Map<String, StateRef> writes = new LinkedHashMap<>();
        for (Effect e : p.effects()) {
            String slot = null;
            Expr value = null;
            if (e.kind() == Effect.Kind.FIELD_WRITE) {
                slot = Names.simple(e.owner()) + "." + e.name();
                value = e.value();
            } else if (e.kind() == Effect.Kind.CALL && e.name().startsWith("set") && e.name().length() > 3 && e.args().size() == 1 && e.target() != null) {
                slot = Names.simple(e.owner()) + "." + decap(e.name().substring(3));
                value = e.args().get(0);
            }
            if (slot == null) continue;
            StateRef s = stateConst(repo, value, opt, slot);
            if (s != null && accepts(s.enumType, opt)) writes.put(slot, s);
            else if (value != null && isEnumTyped(value) && accepts(Type.getType(value.desc).getInternalName(), opt)) {
                Machine m = machines.computeIfAbsent(Type.getType(value.desc).getInternalName(), Machine::new);
                m.computedWrites.add(Names.simple(cn.name) + "." + mn.name + "(): " + slot + " = " + value.text);
            }
        }
        // transition functions: return a state constant
        if (writes.isEmpty() && p.outcome().kind() == Path.OutcomeKind.RETURN && !slotEnum.isEmpty()) {
            StateRef s = stateConst(repo, p.outcome().value(), opt, null);
            if (s != null) {
                for (var e : slotEnum.entrySet()) if (e.getValue().equals(s.enumType)) writes.put(e.getKey(), s);
            }
        }

        String trigger = Names.simple(cn.name) + "." + mn.name + "()";
        boolean ctor = mn.name.equals("<init>");
        String guardText = otherGuards.isEmpty() ? "" : String.join(" && ", otherGuards);
        if (guardText.length() > 70) guardText = guardText.substring(0, 67) + "...";

        for (var w : writes.entrySet()) {
            String slot = w.getKey();
            StateRef to = w.getValue();
            Machine m = machines.computeIfAbsent(to.enumType, Machine::new);
            m.slots.add(slot);
            m.states.add(to.name);
            if (ctor && !pos.containsKey(slot)) {
                m.initial.add(to.name);
                continue;
            }
            Set<String> from = pos.get(slot);
            if (from != null) {
                for (String f : from) {
                    m.states.add(f);
                    m.transitions.add(new Transition(slot, f, to.name, trigger, guardText, false));
                }
            } else {
                Set<String> excluded = neg.getOrDefault(slot, Set.of());
                m.states.addAll(excluded);
                String fromSpec = excluded.isEmpty() ? "*" : "*-" + String.join(",", excluded);
                m.transitions.add(new Transition(slot, fromSpec, to.name, trigger, guardText, excluded.isEmpty()));
            }
        }
        // rejected operations: the state check itself was the last decision before the throw
        if (writes.isEmpty() && p.outcome().kind() == Path.OutcomeKind.THROW && lastDecisionWasState) {
            for (var e : pos.entrySet()) {
                Machine m = machines.computeIfAbsent(slotEnum.get(e.getKey()), Machine::new);
                m.slots.add(e.getKey());
                m.rejections.add(new Rejection(e.getKey(), e.getValue(), trigger, p.outcome().describe()));
            }
            for (var e : neg.entrySet()) {
                if (pos.containsKey(e.getKey())) continue;
                Machine m = machines.computeIfAbsent(slotEnum.get(e.getKey()), Machine::new);
                m.slots.add(e.getKey());
                m.rejections.add(new Rejection(e.getKey(), Set.of("any except " + String.join(",", e.getValue())), trigger, p.outcome().describe()));
            }
        }
        // record states seen in guards even when nothing transitions
        for (var e : pos.entrySet()) if (slotEnum.containsKey(e.getKey()) && machines.containsKey(slotEnum.get(e.getKey()))) machines.get(slotEnum.get(e.getKey())).states.addAll(e.getValue());
    }

    private static boolean guard(ClassRepository repo, ClassNode cn, Expr subject, Expr constant, boolean equal, Options opt,
                                 Map<String, Set<String>> pos, Map<String, Set<String>> neg, Map<String, String> slotEnum) {
        String slot = slotOf(cn, subject);
        if (slot == null) return false;
        StateRef s = stateConst(repo, constant, opt, slot);
        if (s == null || !accepts(s.enumType, opt)) return false;
        (equal ? pos : neg).computeIfAbsent(slot, k -> new LinkedHashSet<>()).add(s.name);
        slotEnum.put(slot, s.enumType);
        return true;
    }

    /** Where a state lives: {@code Order.status} for {@code this.status}, {@code order.getStatus()} or {@code order.status}. */
    static String slotOf(ClassNode cn, Expr e) {
        if (e == null) return null;
        switch (e.kind) {
            case FIELD:
                return Names.simple(e.owner) + "." + e.name;
            case CALL:
                if (e.args.isEmpty() && e.target != null) {
                    if (e.name.startsWith("get") && e.name.length() > 3) return Names.simple(e.owner) + "." + decap(e.name.substring(3));
                    if (e.name.startsWith("is") && e.name.length() > 2) return Names.simple(e.owner) + "." + decap(e.name.substring(2));
                }
                return null;
            case THIS:
                return Names.simple(cn.name) + " (this)";
            case PARAM:
                return "param " + e.name;
            default:
                return null;
        }
    }

    private static StateRef stateConst(ClassRepository repo, Expr e, Options opt, String slot) {
        if (e == null) return null;
        if (e.kind == Expr.Kind.STATIC && e.desc != null && e.desc.equals("L" + e.owner + ";")) {
            ClassNode en = repo.node(e.owner);
            if (en == null || (en.access & Opcodes.ACC_ENUM) != 0) return new StateRef(e.owner, e.name);
        }
        // String / int constants for an explicitly named state field
        if (opt.stateField() != null && slot != null && slot.endsWith("." + opt.stateField()) && e.kind == Expr.Kind.CONST) {
            return new StateRef("(" + opt.stateField() + ")", e.text.replace("\"", ""));
        }
        return null;
    }

    private static String enumTypeOf(Expr selector, Expr label) {
        if (label != null && label.owner != null) return label.owner;
        if (isEnumTyped(selector)) return Type.getType(selector.desc).getInternalName();
        return null;
    }

    private static boolean isEnumTyped(Expr e) {
        return e != null && e.desc != null && e.desc.startsWith("L") && !e.desc.startsWith("Ljava/");
    }

    private static boolean accepts(String enumType, Options opt) {
        if (opt.enumFilter() == null || opt.enumFilter().isBlank()) return true;
        String f = opt.enumFilter().trim().replace('.', '/');
        return enumType.equals(f) || enumType.endsWith("/" + f) || enumType.endsWith("$" + f) || Names.simple(enumType).equals(f);
    }

    private static String constName(String label) {
        String l = label.trim();
        int dot = l.lastIndexOf('.');
        return dot >= 0 ? l.substring(dot + 1) : l;
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** "*" (any state) and "*-X,Y" (any except) become concrete edges once all states are known. */
    private static void expandWildcards(Machine m) {
        List<Transition> out = new ArrayList<>();
        for (Transition t : m.transitions) {
            if (!t.from().startsWith("*")) {
                out.add(t);
                continue;
            }
            Set<String> excluded = t.from().length() > 2 ? Set.of(t.from().substring(2).split(",")) : Set.of();
            for (String s : m.states) {
                // a bare "*" means no guard at all: skip self-loops to keep diagrams readable
                if (excluded.contains(s) || (excluded.isEmpty() && s.equals(t.to()))) continue;
                out.add(new Transition(t.slot(), s, t.to(), t.trigger(), t.guard(), t.unguarded()));
            }
        }
        // dedupe on (slot, from, to, trigger), merging guard text
        Map<String, Transition> dedup = new LinkedHashMap<>();
        for (Transition t : out) {
            String k = t.slot() + "|" + t.from() + "|" + t.to() + "|" + t.trigger();
            dedup.merge(k, t, (a, b) -> a.guard().equals(b.guard()) ? a
                    : new Transition(a.slot(), a.from(), a.to(), a.trigger(), a.guard().isEmpty() || b.guard().isEmpty() ? "" : "(" + a.guard() + ") || (" + b.guard() + ")", a.unguarded() && b.unguarded()));
        }
        m.transitions.clear();
        m.transitions.addAll(dedup.values());
    }

    // ---- rendering ------------------------------------------------------------------------------

    public static String render(Map<String, Machine> machines, String format) {
        StringBuilder sb = new StringBuilder();
        if (machines.values().stream().allMatch(m -> m.transitions.isEmpty() && m.initial.isEmpty() && m.rejections.isEmpty())) {
            return "_No state machines inferred: no enum-valued state was compared and assigned in the analyzed code._\n"
                    + "Tip: pass `stateField` for String/int status fields, or widen `packagePrefix` / omit `className` to analyze the whole archive.\n";
        }
        boolean mermaid = !"plantuml".equalsIgnoreCase(format);
        boolean plant = "plantuml".equalsIgnoreCase(format) || "both".equalsIgnoreCase(format);
        for (Machine m : machines.values()) {
            if (m.transitions.isEmpty() && m.initial.isEmpty() && m.rejections.isEmpty()) continue;
            sb.append("## State machine: `").append(Names.simple(m.enumType)).append("`\n\n");
            sb.append("- Enum: `").append(Names.dotted(m.enumType)).append("`\n");
            sb.append("- State held in: ").append(m.slots.stream().map(s -> "`" + s + "`").collect(Collectors.joining(", "))).append("\n");
            sb.append("- States: ").append(String.join(", ", m.states)).append("\n");
            if (!m.initial.isEmpty()) sb.append("- Initial (set in constructor): ").append(String.join(", ", m.initial)).append("\n");

            Set<String> hasIn = m.transitions.stream().map(Transition::to).collect(Collectors.toSet());
            hasIn.addAll(m.initial);
            Set<String> hasOut = m.transitions.stream().map(Transition::from).collect(Collectors.toSet());
            List<String> unreachable = m.states.stream().filter(s -> !hasIn.contains(s)).toList();
            List<String> terminal = m.states.stream().filter(s -> hasIn.contains(s) && !hasOut.contains(s)).toList();
            if (!terminal.isEmpty()) sb.append("- Terminal states (no outgoing transition): ").append(String.join(", ", terminal)).append("\n");
            if (!unreachable.isEmpty() && !m.initial.isEmpty()) {
                sb.append("- ⚠ Never entered by analyzed code: ").append(String.join(", ", unreachable)).append("\n");
            } else if (!unreachable.isEmpty()) {
                sb.append("- States with no incoming transition (initial state set elsewhere?): ").append(String.join(", ", unreachable)).append("\n");
            }
            long unguarded = m.transitions.stream().filter(Transition::unguarded).map(Transition::trigger).distinct().count();
            if (unguarded > 0) {
                sb.append("- ⚠ Unguarded transitions (no state check before the write — allowed from every state): ")
                        .append(m.transitions.stream().filter(Transition::unguarded).map(Transition::trigger).distinct().collect(Collectors.joining(", "))).append("\n");
            }

            if (mermaid) {
                sb.append("\n```mermaid\nstateDiagram-v2\n");
                for (String i : m.initial) sb.append("    [*] --> ").append(id(i)).append("\n");
                for (Transition t : m.transitions) {
                    sb.append("    ").append(id(t.from())).append(" --> ").append(id(t.to())).append(" : ").append(label(t, m)).append("\n");
                }
                for (String s : terminal) sb.append("    ").append(id(s)).append(" --> [*]\n");
                sb.append("```\n");
            }
            if (plant) {
                sb.append("\n```plantuml\n@startuml\nhide empty description\n");
                for (String i : m.initial) sb.append("[*] --> ").append(id(i)).append("\n");
                for (Transition t : m.transitions) {
                    sb.append(id(t.from())).append(" --> ").append(id(t.to())).append(" : ").append(label(t, m)).append("\n");
                }
                for (String s : terminal) sb.append(id(s)).append(" --> [*]\n");
                sb.append("@enduml\n```\n");
            }

            sb.append("\n| From | To | Trigger | Extra guard | Held in |\n|---|---|---|---|---|\n");
            for (Transition t : m.transitions) {
                sb.append("| ").append(t.from()).append(" | ").append(t.to()).append(" | `").append(t.trigger()).append("` | ")
                        .append(t.guard().isEmpty() ? (t.unguarded() ? "_none (unguarded)_" : "") : "`" + t.guard().replace("|", "\\|") + "`")
                        .append(" | ").append(t.slot()).append(" |\n");
            }
            if (!m.rejections.isEmpty()) {
                sb.append("\n**Rejected operations** (state guard → exception, no transition)\n\n");
                Set<String> seen = new LinkedHashSet<>();
                for (Rejection r : m.rejections) {
                    seen.add("- `" + r.trigger() + "` in " + String.join(", ", r.states()) + " → `" + r.outcome() + "`");
                }
                seen.forEach(s -> sb.append(s).append("\n"));
            }
            if (!m.computedWrites.isEmpty()) {
                sb.append("\n**Writes with computed (non-constant) state** — not drawn:\n\n");
                m.computedWrites.forEach(s -> sb.append("- `").append(s).append("`\n"));
            }
            sb.append("\n");
        }
        sb.append("_Heuristic: state reads and writes are matched by holder type + property name (e.g. `Order.status`), "
                + "not by object identity; catch blocks and callee bodies are not followed._\n");
        return sb.toString();
    }

    private static String label(Transition t, Machine m) {
        String l = t.trigger();
        if (m.slots.size() > 1) l += " [" + t.slot() + "]";
        if (!t.guard().isEmpty()) l += " [" + t.guard() + "]";
        return l.replace(":", "∶").replace(";", ",").replace("{", "(").replace("}", ")").replace("\"", "'");
    }

    private static String id(String state) {
        return state.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static List<Map<String, Object>> structured(Map<String, Machine> machines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Machine m : machines.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("enum", Names.dotted(m.enumType));
            o.put("slots", m.slots);
            o.put("states", m.states);
            o.put("initial", m.initial);
            o.put("transitions", m.transitions.stream().map(t -> Map.of("from", t.from(), "to", t.to(), "trigger", t.trigger(),
                    "guard", t.guard(), "slot", t.slot(), "unguarded", t.unguarded())).toList());
            o.put("rejections", m.rejections.stream().map(r -> Map.of("trigger", r.trigger(), "states", r.states(), "outcome", r.outcome())).toList());
            out.add(o);
        }
        return out;
    }
}
