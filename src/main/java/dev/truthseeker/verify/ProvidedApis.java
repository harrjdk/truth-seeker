package dev.truthseeker.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Container APIs a WAR/EAR compiles against but never ships ({@code provided} scope: servlet, JPA, JAX-RS, EJB, CDI...).
 * When generated tests fail to compile for want of one of their packages, the matching API jar is looked up in the
 * local Maven repository, so verify_tests works on legacy archives without the caller assembling a classpath.
 */
final class ProvidedApis {
    private ProvidedApis() {}

    /** Package prefix → Maven groupId/artifactId directories to look in, most specific first. */
    private static final Map<String, List<String>> ARTIFACTS = new LinkedHashMap<>();

    static {
        ARTIFACTS.put("javax.servlet.jsp", List.of("javax/servlet/jsp/javax.servlet.jsp-api", "javax/servlet/jsp/jsp-api"));
        ARTIFACTS.put("javax.servlet", List.of("javax/servlet/javax.servlet-api", "javax/servlet/servlet-api"));
        ARTIFACTS.put("jakarta.servlet.jsp", List.of("jakarta/servlet/jsp/jakarta.servlet.jsp-api"));
        ARTIFACTS.put("jakarta.servlet", List.of("jakarta/servlet/jakarta.servlet-api"));
        ARTIFACTS.put("javax.persistence", List.of("javax/persistence/javax.persistence-api", "org/hibernate/javax/persistence/hibernate-jpa-2.1-api"));
        ARTIFACTS.put("jakarta.persistence", List.of("jakarta/persistence/jakarta.persistence-api"));
        ARTIFACTS.put("javax.ws.rs", List.of("javax/ws/rs/javax.ws.rs-api"));
        ARTIFACTS.put("jakarta.ws.rs", List.of("jakarta/ws/rs/jakarta.ws.rs-api"));
        ARTIFACTS.put("javax.ejb", List.of("javax/ejb/javax.ejb-api"));
        ARTIFACTS.put("jakarta.ejb", List.of("jakarta/ejb/jakarta.ejb-api"));
        ARTIFACTS.put("javax.inject", List.of("javax/inject/javax.inject"));
        ARTIFACTS.put("jakarta.inject", List.of("jakarta/inject/jakarta.inject-api"));
        ARTIFACTS.put("javax.enterprise", List.of("javax/enterprise/cdi-api"));
        ARTIFACTS.put("jakarta.enterprise", List.of("jakarta/enterprise/jakarta.enterprise.cdi-api"));
        ARTIFACTS.put("javax.jms", List.of("javax/jms/javax.jms-api"));
        ARTIFACTS.put("jakarta.jms", List.of("jakarta/jms/jakarta.jms-api"));
        ARTIFACTS.put("javax.transaction", List.of("javax/transaction/javax.transaction-api"));
        ARTIFACTS.put("jakarta.transaction", List.of("jakarta/transaction/jakarta.transaction-api"));
        ARTIFACTS.put("javax.faces", List.of("javax/faces/javax.faces-api"));
        ARTIFACTS.put("jakarta.faces", List.of("jakarta/faces/jakarta.faces-api"));
        // umbrella APIs: anything else under javax.* / jakarta.*
        ARTIFACTS.put("javax", List.of("javax/javaee-api", "javax/javaee-web-api"));
        ARTIFACTS.put("jakarta", List.of("jakarta/platform/jakarta.jakartaee-api", "jakarta/platform/jakarta.jakartaee-web-api"));
    }

    private static final Pattern MISSING = Pattern.compile("package ((?:javax|jakarta)\\.[\\w.]+) does not exist");

    /** A supertype's class file is missing: {@code cannot access javax.batch.api.chunk.ItemProcessor}. */
    private static final Pattern INACCESSIBLE = Pattern.compile("(?:cannot access|class file for) ((?:javax|jakarta)(?:\\.[a-z]\\w*)+)\\.[A-Z]");

    /** Packages under javax.* / jakarta.* the compiler could not find. */
    static Set<String> missingPackages(Collection<String> compileErrors) {
        Set<String> out = new TreeSet<>();
        for (String e : compileErrors) {
            Matcher m = MISSING.matcher(e);
            while (m.find()) out.add(m.group(1));
            Matcher c = INACCESSIBLE.matcher(e);
            while (c.find()) out.add(c.group(1));
        }
        return out;
    }

    /** API jars from the local Maven repository that provide {@code packages}; empty when none are found. */
    static List<Path> find(Set<String> packages) {
        Path repo = localRepository();
        if (repo == null) return List.of();
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String pkg : packages) {
            for (var e : ARTIFACTS.entrySet()) {
                if (!(pkg.equals(e.getKey()) || pkg.startsWith(e.getKey() + "."))) continue;
                Path jar = null;
                for (String dir : e.getValue()) { // the jar must really hold the package (cdi-api has no javax.enterprise.concurrent)
                    Path candidate = newestJar(repo.resolve(dir));
                    if (candidate != null && holdsPackage(candidate, pkg)) {
                        jar = candidate;
                        break;
                    }
                }
                if (jar != null) {
                    out.add(jar);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    static boolean holdsPackage(Path jar, String pkg) {
        String prefix = pkg.replace('.', '/') + "/";
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            return z.stream().anyMatch(en -> en.getName().startsWith(prefix) && en.getName().endsWith(".class"));
        } catch (IOException ex) {
            return false;
        }
    }

    private static Path localRepository() {
        String prop = System.getProperty("maven.repo.local");
        if (prop != null && Files.isDirectory(Path.of(prop))) return Path.of(prop);
        Path home = Path.of(System.getProperty("user.home"), ".m2", "repository");
        return Files.isDirectory(home) ? home : null;
    }

    /** The jar of the highest version under an artifact directory (repo/group/artifact/version/artifact-version.jar). */
    private static Path newestJar(Path artifactDir) {
        if (!Files.isDirectory(artifactDir)) return null;
        String artifact = artifactDir.getFileName().toString();
        try (Stream<Path> versions = Files.list(artifactDir)) {
            return versions.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path v) -> v.getFileName().toString(), ProvidedApis::compareVersions).reversed())
                    .map(v -> v.resolve(artifact + "-" + v.getFileName() + ".jar"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return a.compareTo(b);
    }
}
