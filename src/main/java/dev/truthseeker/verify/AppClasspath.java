package dev.truthseeker.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Turns an application archive into a classpath a forked JVM can use:
 * Spring Boot fat jar → BOOT-INF/classes + BOOT-INF/lib; WAR → WEB-INF/classes + WEB-INF/lib;
 * EAR → module jars, lib/ and the WARs' contents; plain jar / classes dir → as is.
 * Extractions are cached per archive (path + size + timestamp).
 */
final class AppClasspath {
    /** Test frameworks the archive may bundle; the bundled test kit's versions win. */
    private static final Pattern TEST_LIBS = Pattern.compile(
            "(?i).*[/\\\\](junit|junit-jupiter|junit-platform|junit-vintage|mockito|byte-buddy|objenesis|opentest4j|apiguardian|hamcrest|assertj)[^/\\\\]*\\.jar$");

    private AppClasspath() {}

    record Resolved(List<Path> entries, List<String> notes) {}

    static Resolved resolve(String pathSpec) throws IOException {
        List<Path> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String part : pathSpec.split(File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path p = Path.of(part.trim()).toAbsolutePath().normalize();
            if (!Files.exists(p)) throw new IllegalArgumentException("Path does not exist: " + p);
            if (Files.isDirectory(p)) fromDirectory(p, out);
            else fromArchive(p, out, notes);
        }
        List<Path> filtered = new ArrayList<>();
        int dropped = 0;
        for (Path e : out) {
            if (TEST_LIBS.matcher(e.toString()).matches()) dropped++;
            else filtered.add(e);
        }
        if (dropped > 0) notes.add(dropped + " test-framework jar(s) from the application were left out in favour of the bundled JUnit/Mockito.");
        return new Resolved(filtered, notes);
    }

    private static void fromDirectory(Path dir, List<Path> out) throws IOException {
        Path webClasses = dir.resolve("WEB-INF/classes");
        Path bootClasses = dir.resolve("BOOT-INF/classes");
        if (Files.isDirectory(webClasses)) {
            out.add(webClasses);
            addJars(dir.resolve("WEB-INF/lib"), out);
        } else if (Files.isDirectory(bootClasses)) {
            out.add(bootClasses);
            addJars(dir.resolve("BOOT-INF/lib"), out);
        } else {
            out.add(dir);
        }
    }

    private static void addJars(Path dir, List<Path> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().forEach(out::add);
        }
    }

    private static void fromArchive(Path archive, List<Path> out, List<String> notes) throws IOException {
        String kind;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("BOOT-INF/classes/") != null || hasPrefix(zip, "BOOT-INF/")) kind = "boot";
            else if (hasPrefix(zip, "WEB-INF/")) kind = "war";
            else if (archive.toString().toLowerCase(Locale.ROOT).endsWith(".ear") || hasPrefix(zip, "META-INF/application.xml")) kind = "ear";
            else kind = "jar";
        }
        if (kind.equals("jar")) {
            out.add(archive);
            return;
        }
        Path target = Cache.root().resolve("app-" + Cache.hash(archive + "|" + Files.size(archive) + "|" + Files.getLastModifiedTime(archive).toMillis()));
        Path marker = target.resolve(".complete");
        if (!Files.exists(marker)) {
            deleteRecursively(target);
            Files.createDirectories(target);
            try (InputStream in = Files.newInputStream(archive)) {
                extract(in, kind, target, "");
            }
            Files.writeString(marker, archive.toString());
        }
        notes.add("Classpath from " + kind.toUpperCase(Locale.ROOT) + " layout, extracted to " + target);
        try (Stream<Path> s = Files.list(target)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("classes")) out.add(p);
            }
        }
        addJars(target.resolve("lib"), out);
    }

    private static boolean hasPrefix(ZipFile zip, String prefix) {
        return zip.stream().anyMatch(e -> e.getName().startsWith(prefix));
    }

    /** Extracts classes and library jars of one archive layout; nested WARs inside an EAR are expanded too. */
    private static void extract(InputStream in, String kind, Path target, String suffix) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry e;
        Path classes = target.resolve("classes" + suffix);
        Path lib = target.resolve("lib");
        while ((e = zip.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = e.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            Path dest = null;
            switch (kind) {
                case "boot" -> {
                    if (name.startsWith("BOOT-INF/classes/")) dest = safe(classes, name.substring("BOOT-INF/classes/".length()));
                    else if (name.startsWith("BOOT-INF/lib/") && lower.endsWith(".jar")) dest = safe(lib, name.substring("BOOT-INF/lib/".length()));
                }
                case "war" -> {
                    if (name.startsWith("WEB-INF/classes/")) dest = safe(classes, name.substring("WEB-INF/classes/".length()));
                    else if (name.startsWith("WEB-INF/lib/") && lower.endsWith(".jar")) dest = safe(lib, name.substring("WEB-INF/lib/".length()));
                }
                case "ear" -> {
                    if (lower.endsWith(".war")) {
                        extract(new NonClosingInputStream(zip), "war", target, "-" + name.replaceAll("[^A-Za-z0-9.]", "_"));
                        continue;
                    }
                    if (lower.endsWith(".jar")) dest = safe(lib, name.replace('/', '_'));
                }
                default -> {}
            }
            if (dest == null) continue;
            Files.createDirectories(dest.getParent());
            Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Guards against zip-slip entries ("../../x"). */
    private static Path safe(Path base, String rel) throws IOException {
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base.normalize())) throw new IOException("Refusing suspicious archive entry: " + rel);
        return p;
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    /** Lets a nested archive be read from the enclosing stream without closing it. */
    private static final class NonClosingInputStream extends java.io.FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // keep the outer stream open
        }
    }
}
