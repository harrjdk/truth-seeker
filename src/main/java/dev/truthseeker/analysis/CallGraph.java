package dev.truthseeker.analysis;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.archive.Names;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Static call graph over the indexed classes. Calls through interfaces / abstract methods are
 * expanded to every implementation found in the archive (how Spring service layers are usually wired),
 * and lambda / method-reference bodies are attributed to the method that creates them.
 */
public final class CallGraph {

    public record Edge(String from, String to, String via) {}

    private final ClassRepository repo;
    private final boolean includeJdk;
    private Map<String, Set<String>> subtypes;

    public CallGraph(ClassRepository repo, boolean includeJdk) {
        this.repo = repo;
        this.includeJdk = includeJdk;
    }

    public static String key(String owner, String name, String desc) {
        return owner + "#" + name + "#" + desc;
    }

    private Map<String, Set<String>> subtypes() {
        if (subtypes == null) {
            subtypes = new HashMap<>();
            for (ClassRepository.ClassEntry e : repo.entries()) {
                if (e.library()) continue;
                ClassNode cn = repo.node(e.internalName());
                if (cn.superName != null) subtypes.computeIfAbsent(cn.superName, k -> new TreeSet<>()).add(cn.name);
                for (String i : cn.interfaces) subtypes.computeIfAbsent(i, k -> new TreeSet<>()).add(cn.name);
            }
        }
        return subtypes;
    }

    private Set<String> allSubtypes(String type) {
        Set<String> out = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(type));
        while (!todo.isEmpty()) for (String s : subtypes().getOrDefault(todo.poll(), Set.of())) if (out.add(s)) todo.add(s);
        return out;
    }

    private MethodNode method(String key) {
        String[] p = key.split("#", 3);
        ClassNode cn = repo.node(p[0]);
        if (cn == null) return null;
        for (MethodNode mn : cn.methods) if (mn.name.equals(p[1]) && mn.desc.equals(p[2])) return mn;
        return null;
    }

    /** Direct outgoing edges of one method, with virtual dispatch expanded to implementations in the archive. */
    public List<Edge> outgoing(String from) {
        MethodNode mn = method(from);
        List<Edge> out = new ArrayList<>();
        if (mn == null) return out;
        for (AbstractInsnNode in : mn.instructions) {
            if (in instanceof MethodInsnNode m) {
                if (!keep(m.owner)) continue;
                String to = key(m.owner, m.name, m.desc);
                MethodNode target = method(to);
                boolean dispatch = m.getOpcode() == Opcodes.INVOKEINTERFACE || m.getOpcode() == Opcodes.INVOKEVIRTUAL;
                if (target == null || (target.access & Opcodes.ACC_ABSTRACT) != 0 || dispatch) {
                    boolean expanded = false;
                    if (dispatch && repo.contains(m.owner)) {
                        for (String sub : allSubtypes(m.owner)) {
                            String impl = key(sub, m.name, m.desc);
                            MethodNode im = method(impl);
                            if (im != null && (im.access & Opcodes.ACC_ABSTRACT) == 0) {
                                out.add(new Edge(from, impl, "impl of " + Names.simple(m.owner)));
                                expanded = true;
                            }
                        }
                    }
                    if (!expanded || (target != null && (target.access & Opcodes.ACC_ABSTRACT) == 0)) out.add(new Edge(from, to, "call"));
                } else out.add(new Edge(from, to, "call"));
            } else if (in instanceof InvokeDynamicInsnNode indy) {
                for (Object a : indy.bsmArgs) {
                    if (a instanceof Handle h && keep(h.getOwner())) {
                        out.add(new Edge(from, key(h.getOwner(), h.getName(), h.getDesc()), h.getName().startsWith("lambda$") ? "lambda" : "method ref"));
                    }
                }
            }
        }
        return out;
    }

    private boolean keep(String owner) {
        if (includeJdk) return true;
        if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jakarta/") || owner.startsWith("jdk/")
                || owner.startsWith("sun/") || owner.startsWith("kotlin/")) return false;
        return repo.entry(owner).map(e -> !e.library()).orElse(false);
    }

    public Set<Edge> callees(List<String> roots, int depth) {
        Set<Edge> edges = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>(roots);
        List<String> frontier = new ArrayList<>(roots);
        for (int d = 0; d < depth && !frontier.isEmpty(); d++) {
            List<String> next = new ArrayList<>();
            for (String f : frontier) {
                for (Edge e : outgoing(f)) {
                    if (edges.add(e) && seen.add(e.to())) next.add(e.to());
                }
            }
            frontier = next;
        }
        return edges;
    }

    public Set<Edge> callers(List<String> roots, int depth) {
        // reverse index over application classes
        Map<String, List<Edge>> incoming = new HashMap<>();
        for (ClassRepository.ClassEntry e : repo.entries()) {
            if (e.library()) continue;
            ClassNode cn = repo.node(e.internalName());
            for (MethodNode mn : cn.methods) {
                for (Edge edge : outgoing(key(cn.name, mn.name, mn.desc))) incoming.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
            }
        }
        // a call to an interface method counts as a call to each implementation root
        Set<Edge> edges = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>(roots);
        List<String> frontier = new ArrayList<>(roots);
        for (int d = 0; d < depth && !frontier.isEmpty(); d++) {
            List<String> next = new ArrayList<>();
            for (String t : frontier) {
                for (Edge e : incoming.getOrDefault(t, List.of())) {
                    if (edges.add(e) && seen.add(e.from())) next.add(e.from());
                }
            }
            frontier = next;
        }
        return edges;
    }

    public String render(List<String> roots, Set<Edge> edges, String direction) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String r : roots) ids.computeIfAbsent(r, k -> "n" + ids.size());
        for (Edge e : edges) {
            ids.computeIfAbsent(e.from(), k -> "n" + ids.size());
            ids.computeIfAbsent(e.to(), k -> "n" + ids.size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Call graph (").append("in".equalsIgnoreCase(direction) ? "callers of" : "calls from").append(" ")
                .append(roots.size() == 1 ? Reports.methodLabel(roots.get(0)) : roots.size() + " methods").append(")\n\n");
        sb.append("Edges: ").append(edges.size()).append(includeJdk ? "" : " (JDK, Jakarta/javax and bundled-library calls hidden)").append("\n\n");
        sb.append("```mermaid\nflowchart LR\n");
        for (var en : ids.entrySet()) {
            String label = Reports.methodLabel(en.getKey()).replace("\"", "'");
            sb.append("  ").append(en.getValue()).append("[\"").append(label).append("\"]\n");
        }
        for (String r : roots) sb.append("  style ").append(ids.get(r)).append(" stroke-width:3px\n");
        for (Edge e : edges) {
            String arrow = e.via().equals("call") ? " --> " : " -.->|" + e.via().replace("|", "/") + "| ";
            sb.append("  ").append(ids.get(e.from())).append(arrow).append(ids.get(e.to())).append("\n");
        }
        sb.append("```\n\n## Edge list\n\n");
        for (Edge e : edges) {
            sb.append("- ").append(Reports.methodLabel(e.from())).append(" → ").append(Reports.methodLabel(e.to()));
            if (!e.via().equals("call")) sb.append(" _(").append(e.via()).append(")_");
            sb.append("\n");
        }
        return sb.toString();
    }
}
