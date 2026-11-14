package dev.truthseeker;

import dev.truthseeker.archive.ClassRepository;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class TestSupport {
    private TestSupport() {}

    /** The compiled fixtures (src/test/java/fixtures, plus src/test/java21 on JDK 21+). */
    static Path fixtures() {
        return Path.of("target", "test-classes").toAbsolutePath();
    }

    static ClassRepository repo() throws Exception {
        return ClassRepository.open(fixtures().toString());
    }

    static String run(String tool, Object... kv) {
        Map<String, Object> args = new HashMap<>();
        args.put("path", fixtures().toString());
        for (int i = 0; i + 1 < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        Tools.ToolDef def = Tools.all().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        return Tools.text(def.handler().apply(args));
    }

    static String testClasspath() {
        String cp = System.getProperty("surefire.test.class.path");
        if (cp == null || cp.isBlank()) cp = System.getProperty("java.class.path");
        return cp;
    }

    static boolean hasJava21Fixtures() {
        return new File(fixtures().toFile(), "fixtures/modern/Shapes.class").exists();
    }
}
