package dev.truthseeker.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JUnit Platform + Jupiter + Mockito jars bundled inside truth-seeker (see the pom's bundle-testkit execution),
 * extracted once to a local cache. They are only ever put on the classpath of the forked test JVM.
 */
final class TestKit {
    private static final String DIR = "truthseeker-testkit/";
    private static List<Path> cached;

    private TestKit() {}

    static synchronized List<Path> jars() throws IOException {
        if (cached != null) return cached;
        Path source;
        try {
            source = Path.of(TestKit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Cannot locate truth-seeker's own code source", e);
        }
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(source)) { // running from target/classes (tests, IDE)
            Path dir = source.resolve(DIR);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(out::add);
                }
            }
        } else { // running from the shaded jar: extract next to other caches
            Path target = Cache.root().resolve("testkit-" + Files.size(source) + "-" + Files.getLastModifiedTime(source).toMillis());
            Path marker = target.resolve(".complete");
            try (ZipFile zip = new ZipFile(source.toFile())) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(DIR) || !e.getName().endsWith(".jar")) continue;
                    Path dest = target.resolve(e.getName().substring(DIR.length()));
                    if (!Files.exists(marker)) {
                        Files.createDirectories(dest.getParent());
                        try (InputStream in = zip.getInputStream(e)) {
                            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    out.add(dest);
                }
            }
            if (!out.isEmpty() && !Files.exists(marker)) Files.writeString(marker, "ok");
        }
        if (out.isEmpty()) {
            throw new IOException("Bundled test kit (JUnit/Mockito) not found in " + source + "; rebuild truth-seeker with ./mvnw package");
        }
        cached = List.copyOf(out);
        return cached;
    }
}
