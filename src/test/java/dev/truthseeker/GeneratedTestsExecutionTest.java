package dev.truthseeker;

import dev.truthseeker.archive.ClassRepository;
import dev.truthseeker.testgen.TestGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * End-to-end proof for generate_unit_tests: the generated sources must compile against the fixtures
 * and every generated test must pass when run on the JUnit Platform.
 */
class GeneratedTestsExecutionTest {

    @Test
    void generatedTestsCompileAndPass(@TempDir Path tmp) throws Exception {
        ClassRepository repo = TestSupport.repo();
        List<String> classes = new ArrayList<>(List.of(
                "fixtures.order.OrderService", "fixtures.rules.Eligibility", "fixtures.door.Door", "fixtures.door.TrafficLight",
                // PetClinic-shaped regressions: Optional.orElseThrow, overloaded getPet(..), Object-typed param cast, loops
                "fixtures.clinic.PetLookupService", "fixtures.clinic.PetValidator", "fixtures.clinic.Owner",
                // Cargo Tracker-shaped regressions: validated constructors, no no-arg ctor, JUL Logger collaborator
                "fixtures.shipping.HandlingEvent", "fixtures.shipping.Delivery", "fixtures.shipping.HandlingService",
                // self-call inlining, for-each loops driven with real collections, precondition helpers
                "fixtures.rules.ShippingFee",
                // try/catch: thenThrow/doThrow on collaborators, parser input, throws inside inlined helpers
                "fixtures.legacy.AccountService",
                // constructors of other classes and rules held by enum constants
                "fixtures.legacy.ShipmentEventFactory", "fixtures.legacy.ShipmentEvent", "fixtures.legacy.Waybill", "fixtures.legacy.CustomerController", "fixtures.legacy.Roster", "fixtures.legacy.BatchJob",
                // typed list elements, null elements, stream pipelines, date ordering, paged results
                "fixtures.legacy.Schedule", "fixtures.legacy.History", "fixtures.legacy.Deadline", "fixtures.legacy.CustomerSearch",
                // JPetStore-shaped: iterator loops, map fields, objects built on the path, session attributes
                "fixtures.shop.Basket", "fixtures.shop.BasketController", "fixtures.shop.OrderLine", "fixtures.shop.Shelf"));
        if (TestSupport.hasJava21Fixtures()) classes.add("fixtures.modern.Shapes");

        Path src = tmp.resolve("src");
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        List<String> files = new ArrayList<>();
        List<String> testClassNames = new ArrayList<>();
        for (String c : classes) {
            String code = TestGenerator.generate(repo, repo.require(c), new TestGenerator.Options(List.of(), 64, 24, false));
            String simple = c.substring(c.lastIndexOf('.') + 1);
            Path file = src.resolve(c.substring(0, c.lastIndexOf('.')).replace('.', '/')).resolve(simple + "GeneratedTest.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, code);
            files.add(file.toString());
            testClassNames.add(c + "GeneratedTest");
        }

        // compile
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", TestSupport.testClasspath(), "-proc:none", "-Xlint:none"));
        args.addAll(files);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, err, err, args.toArray(String[]::new));
        assertEquals(0, rc, () -> "Generated tests do not compile:\n" + err + "\n" + dump(files));

        // run
        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader())) {
            Thread t = Thread.currentThread();
            ClassLoader previous = t.getContextClassLoader();
            t.setContextClassLoader(loader);
            try {
                LauncherDiscoveryRequestBuilder req = LauncherDiscoveryRequestBuilder.request();
                for (String n : testClassNames) req.selectors(selectClass(loader.loadClass(n)));
                LauncherDiscoveryRequest request = req.build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.execute(request, listener);
                TestExecutionSummary s = listener.getSummary();
                StringWriter sw = new StringWriter();
                s.printFailuresTo(new PrintWriter(sw), 15);
                assertTrue(s.getTestsFoundCount() >= 30, "expected generated tests to run, found " + s.getTestsFoundCount());
                assertEquals(0, s.getTotalFailureCount(), () -> "Generated test failures:\n" + sw + "\n" + dump(files));
                // tests with unresolved preconditions are emitted @Disabled; keep that a small minority
                assertEquals(s.getTestsFoundCount(), s.getTestsSucceededCount() + s.getTestsSkippedCount());
                assertTrue(s.getTestsSkippedCount() * 10 <= s.getTestsFoundCount(),
                        "too many disabled tests: " + s.getTestsSkippedCount() + " of " + s.getTestsFoundCount());
            } finally {
                t.setContextClassLoader(previous);
            }
        }
    }

    private static String dump(List<String> files) {
        StringBuilder sb = new StringBuilder();
        for (String f : files) {
            try {
                sb.append("==== ").append(f).append('\n').append(Files.readString(Path.of(f))).append('\n');
            } catch (Exception e) {
                sb.append(e);
            }
        }
        return sb.toString();
    }
}
