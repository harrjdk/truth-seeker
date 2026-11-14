package dev.truthseeker.archive;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * An in-memory index of every class file reachable from a directory, JAR, WAR, EAR or Spring Boot
 * fat jar (nested archives included). Classes are only ever <em>parsed</em> with ASM, never loaded,
 * so static initializers and other application code never execute.
 */
public final class ClassRepository {

    /** Where a class came from and whether it is application code or a bundled library. */
    public record ClassEntry(String internalName, byte[] bytes, String origin, boolean library) {
        public int majorVersion() {
            return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        }
    }

    private static final Map<String, CachedRepo> CACHE = new ConcurrentHashMap<>();

    private record CachedRepo(long stamp, ClassRepository repo) {}

    private final Path root;
    private final Map<String, ClassEntry> classes = new LinkedHashMap<>();
    private final Map<String, ClassNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, String> descriptors = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int duplicates;

    private ClassRepository(Path root) {
        this.root = root;
    }

    /** Opens (or returns a cached index of) the given path. Several paths may be joined with the OS path separator. */
    public static ClassRepository open(String pathSpec) throws IOException {
        if (pathSpec == null || pathSpec.isBlank()) {
            throw new IllegalArgumentException("'path' is required: a .jar/.war/.ear file or a classes directory");
        }
        String[] parts = pathSpec.split(java.io.File.pathSeparator);
        long stamp = 0;
        List<Path> paths = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            Path path = Path.of(p.trim()).toAbsolutePath().normalize();
            if (!Files.exists(path)) throw new IllegalArgumentException("Path does not exist: " + path);
            paths.add(path);
            stamp = stamp * 31 + lastModifiedDeep(path);
        }
        String key = String.join(java.io.File.pathSeparator, paths.stream().map(Path::toString).toList());
        CachedRepo cached = CACHE.get(key);
        if (cached != null && cached.stamp == stamp) return cached.repo;

        ClassRepository repo = new ClassRepository(paths.get(0));
        for (Path path : paths) repo.load(path);
        CACHE.put(key, new CachedRepo(stamp, repo));
        return repo;
    }

    private static long lastModifiedDeep(Path path) throws IOException {
        if (!Files.isDirectory(path)) return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
        try (Stream<Path> s = Files.walk(path)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0;
                }
            }).max().orElse(0) ^ countFiles(path);
        }
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.count();
        }
    }

    private void load(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.walk(path)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String rel = path.relativize(p).toString().replace('\\', '/');
                    String lower = rel.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".class")) {
                        addClass(Files.readAllBytes(p), rel, isLibraryPath(rel));
                    } else if (!isLibraryPath(rel) && isDescriptor(lower) && Files.size(p) < 2_000_000) {
                        descriptors.put(rel, Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
                    } else if (isArchive(lower)) {
                        try (InputStream in = Files.newInputStream(p)) {
                            loadZip(in, rel, isLibraryPath(rel));
                        }
                    }
                }
            }
        } else {
            try (InputStream in = Files.newInputStream(path)) {
                loadZip(in, path.getFileName().toString(), false);
            }
        }
    }

    private void loadZip(InputStream in, String prefix, boolean library) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = e.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean lib = library || isLibraryPath(name);
            if (lower.endsWith(".class")) {
                // multi-release jars: only index the base version to avoid ambiguity
                if (lower.startsWith("meta-inf/versions/")) continue;
                addClass(zip.readAllBytes(), prefix + "!/" + name, lib);
            } else if (!lib && isDescriptor(lower)) {
                byte[] data = zip.readAllBytes();
                if (data.length < 2_000_000) descriptors.put(prefix + "!/" + name, new String(data, java.nio.charset.StandardCharsets.UTF_8));
            } else if (isArchive(lower)) {
                byte[] nested = zip.readAllBytes();
                try {
                    loadZip(new ByteArrayInputStream(nested), prefix + "!/" + name, lib);
                } catch (IOException ex) {
                    warnings.add("Could not read nested archive " + prefix + "!/" + name + ": " + ex.getMessage());
                }
            }
        }
    }

    /** Deployment descriptors and wiring files that legacy apps rely on instead of annotations. */
    private static boolean isDescriptor(String lower) {
        String file = lower.substring(lower.lastIndexOf('/') + 1);
        if (file.endsWith(".properties") || file.endsWith(".yml") || file.endsWith(".yaml")) {
            return file.startsWith("application") || file.startsWith("bootstrap");
        }
        if (!file.endsWith(".xml")) return false;
        if (lower.contains("/maven/") || lower.startsWith("meta-inf/maven/")) return false;
        return lower.startsWith("web-inf/") || lower.startsWith("meta-inf/") || lower.contains("/web-inf/")
                || lower.contains("/meta-inf/") || file.contains("context") || file.contains("spring")
                || file.contains("beans") || file.contains("struts") || file.contains("servlet");
    }

    private static boolean isArchive(String lower) {
        return lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".ear") || lower.endsWith(".rar");
    }

    /** WEB-INF/lib, BOOT-INF/lib, EAR lib/ and APP-INF/lib (WebLogic) hold third-party jars. */
    private static boolean isLibraryPath(String name) {
        String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        return n.startsWith("web-inf/lib/") || n.startsWith("boot-inf/lib/") || n.startsWith("lib/")
                || n.startsWith("app-inf/lib/") || n.contains("/web-inf/lib/") || n.contains("/boot-inf/lib/");
    }

    private void addClass(byte[] bytes, String origin, boolean library) {
        if (bytes.length < 10 || (bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE) {
            warnings.add("Not a class file: " + origin);
            return;
        }
        String name;
        try {
            name = new ClassReader(bytes).getClassName();
        } catch (RuntimeException ex) {
            warnings.add("Unparseable class " + origin + ": " + ex);
            return;
        }
        if (name.equals("module-info") || name.endsWith("/package-info")) return;
        if (classes.containsKey(name)) {
            duplicates++;
            // prefer application code over a library copy of the same class
            if (!classes.get(name).library() || library) return;
        }
        classes.put(name, new ClassEntry(name, bytes, origin, library));
    }

    // ---------------------------------------------------------------------------------------------

    public Path root() {
        return root;
    }

    public Collection<ClassEntry> entries() {
        return Collections.unmodifiableCollection(classes.values());
    }

    /** XML descriptors / Spring config files from application (non-library) code, keyed by origin path. */
    public Map<String, String> descriptors() {
        return Collections.unmodifiableMap(descriptors);
    }

    public List<String> warnings() {
        return warnings;
    }

    public int duplicates() {
        return duplicates;
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    public Optional<ClassEntry> entry(String internalName) {
        return Optional.ofNullable(classes.get(internalName));
    }

    /** Parsed tree for a class, or {@code null} if it is not in the repository. */
    public ClassNode node(String internalName) {
        ClassEntry e = classes.get(internalName);
        if (e == null) return null;
        return nodes.computeIfAbsent(internalName, n -> {
            ClassNode cn = new ClassNode();
            new ClassReader(e.bytes()).accept(cn, ClassReader.SKIP_FRAMES);
            return cn;
        });
    }

    /**
     * Resolves a user supplied class reference: fully-qualified ({@code com.acme.Foo}), internal
     * ({@code com/acme/Foo}), nested ({@code com.acme.Foo$Bar}) or a unique simple name ({@code Foo}).
     */
    public ClassNode require(String className) {
        if (className == null || className.isBlank()) throw new IllegalArgumentException("'className' is required");
        String internal = className.trim().replace('.', '/');
        if (internal.endsWith("/class")) internal = internal.substring(0, internal.length() - 6);
        ClassNode cn = node(internal);
        if (cn != null) return cn;
        // try nested class written with dots: com.acme.Outer.Inner
        String nested = internal;
        while (nested.contains("/")) {
            int i = nested.lastIndexOf('/');
            nested = nested.substring(0, i) + "$" + nested.substring(i + 1);
            cn = node(nested);
            if (cn != null) return cn;
        }
        String simple = internal.substring(internal.lastIndexOf('/') + 1);
        List<String> matches = classes.keySet().stream()
                .filter(n -> n.endsWith("/" + simple) || n.equals(simple) || n.endsWith("$" + simple))
                .sorted(Comparator.comparing((String n) -> classes.get(n).library()).thenComparing(n -> n))
                .toList();
        List<String> appMatches = matches.stream().filter(n -> !classes.get(n).library()).toList();
        if (appMatches.size() == 1) return node(appMatches.get(0));
        if (matches.size() == 1) return node(matches.get(0));
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Class not found: " + className + " (indexed " + classes.size() + " classes)");
        }
        throw new IllegalArgumentException("Ambiguous class name '" + className + "', candidates: "
                + matches.stream().limit(15).map(n -> n.replace('/', '.')).toList());
    }

    /** Selects methods by name, optionally narrowed by descriptor or a parameter-list hint like "(String,int)". */
    public static List<MethodNode> findMethods(ClassNode cn, String method, String descriptor) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("'method' is required");
        String m = method.trim();
        String desc = descriptor;
        int paren = m.indexOf('(');
        if (paren > 0 && (desc == null || desc.isBlank())) {
            desc = m.substring(paren);
            m = m.substring(0, paren);
        }
        String name = m;
        List<MethodNode> byName = cn.methods.stream().filter(mn -> mn.name.equals(name)).toList();
        if (byName.isEmpty()) {
            throw new IllegalArgumentException("No method '" + name + "' in " + cn.name.replace('/', '.') + ". Methods: "
                    + cn.methods.stream().map(mn -> mn.name).distinct().toList());
        }
        if (desc == null || desc.isBlank()) return byName;
        String d = desc.trim();
        List<MethodNode> exact = byName.stream().filter(mn -> mn.desc.equals(d)).toList();
        if (!exact.isEmpty()) return exact;
        // loose match on simple parameter type names: "(String,int)" or "String, int"
        String hint = d.replaceAll("[()\\s]", "");
        List<MethodNode> loose = byName.stream()
                .filter(mn -> String.join(",", Names.paramSimpleNames(mn.desc)).equalsIgnoreCase(hint))
                .toList();
        if (!loose.isEmpty()) return loose;
        throw new IllegalArgumentException("No overload of '" + name + "' matches '" + desc + "'. Overloads: "
                + byName.stream().map(mn -> Names.methodSignature(mn.name, mn.desc)).toList());
    }
}
