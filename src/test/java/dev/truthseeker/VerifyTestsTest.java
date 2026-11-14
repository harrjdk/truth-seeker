package dev.truthseeker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The generate → verify loop through the tool layer, as an MCP client would drive it. */
class VerifyTestsTest {

    @Test
    void generateThenVerifyWithBrokenAndFailingFiles(@TempDir Path tmp) throws Exception {
        String gen = TestSupport.run("generate_unit_tests",
                "classNames", List.of("fixtures.order.OrderService", "fixtures.clinic.PetLookupService", "fixtures.shipping.Delivery"),
                "outputDir", tmp.toString());
        assertTrue(gen.contains("# Generated tests"), gen);
        assertTrue(gen.contains("Wrote 3 file(s) under `"), gen);
        assertFalse(gen.contains("```java"), "source should not be echoed when outputDir is set:\n" + gen);

        // a file that does not compile, and a test that fails: both must be reported without hiding the rest
        Path pkg = Files.createDirectories(tmp.resolve("extra"));
        Files.writeString(pkg.resolve("BrokenTest.java"), """
                package extra;
                class BrokenTest { void x() { int y = "not an int"; } }
                """);
        Files.writeString(pkg.resolve("FailingTest.java"), """
                package extra;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class FailingTest {
                    @Test void wrongExpectation() throws Exception { assertEquals(2, fixtures.rules.Eligibility.discountPercent(5, true)); }
                }
                """);

        String out = TestSupport.run("verify_tests", "testDir", tmp.toString(), "timeoutSeconds", 240);
        assertTrue(out.contains("# Test verification"), out);
        assertTrue(out.contains("1 did not"), out);
        assertTrue(out.contains("## Did not compile") && out.contains("BrokenTest.java"), out);
        assertTrue(out.contains("## Failures (grouped by cause)") && out.contains("FailingTest.wrongExpectation"), out);
        assertTrue(out.contains("AssertionFailedError: expected: <2> but was: <0>"), out);
        // everything generated passes; only the hand-written FailingTest fails
        assertTrue(out.matches("(?s).*Result: \\*\\*\\d+ passed\\*\\*, 1 failed, \\d+ disabled.*"), out);
    }

    @Test
    void missingTestDirIsAClearError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestSupport.run("verify_tests", "testDir", "does/not/exist"));
        assertTrue(e.getMessage().contains("testDir is not a directory"), e.getMessage());
    }
}
