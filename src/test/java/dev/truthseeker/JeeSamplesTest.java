package dev.truthseeker;

import dev.truthseeker.testgen.TestGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Patterns found by running truth-seeker against the Java EE 7 samples. */
class JeeSamplesTest {

    private static String generate(String cls) throws Exception {
        var repo = TestSupport.repo();
        return TestGenerator.generate(repo, repo.require(cls), new TestGenerator.Options(List.of(), 64, 24, false));
    }

    @Test
    void containerStylePatternsAreArranged() throws Exception {
        String code = generate("fixtures.legacy.BatchJob");
        // only the path where the second sleep of a loop throws stays disabled: the first one must not
        assertTrue(code.split("@Disabled\\(").length == 2 && code.contains("make `Thread.sleep(1L)` throw InterruptedException"), code);
        // a string switch: the case label is passed, the default gets a string hashing to no case
        assertTrue(code.contains("String code = \"en\";") && code.contains("String code = \"de\";") && code.contains("String code = \"value\";"), code);
        // Thread.sleep throwing InterruptedException: the interrupt flag, cleared after the call
        assertTrue(code.contains("Thread.currentThread().interrupt();") && code.contains("Thread.interrupted();"), code);
        // a polling loop answered in turn
        assertTrue(code.contains(".isDone()).thenReturn(false, true);"), code);
        // the writer dereferenced before the loop is never stubbed as null afterwards
        assertFalse(code.contains("getWriter()).thenReturn(null)"), code);
        // an exception handed to a listener is a real one; a cast parameter is a mock of the cast type
        assertTrue(code.contains("Exception e = new Exception(\"value\");"), code);
        assertTrue(code.contains("Record item = mock(Record.class"), code);
        assertTrue(code.contains("assertEquals((1 + 1), ((Record) result).getId());"), code);
        assertTrue(code.contains("assertEquals(\"skipped: \" + e.getMessage(), result);"), code);
        // a collaborator the container sets after construction
        assertTrue(code.contains("setField(subject, \"handler\", handler);"), code);
        // getClass() != o.getClass(): an argument of another class; an Integer id's hashCode
        assertTrue(code.contains("Object o = new Object();"), code);
        assertTrue(code.contains("((Integer) 1).hashCode()"), code);
        // arrays compare by content; a local BufferedWriter is not assumed to fail
        assertTrue(code.contains("assertArrayEquals(new Class[]{String.class, Integer.class}, result);"), code);
        assertFalse(code.contains("writer.write(text) throws"), code);
    }

    @Test
    void preCollectionsAndSharedStateAreArranged() throws Exception {
        String code = generate("fixtures.legacy.MessageBoard");
        assertFalse(code.contains("@Disabled("), code);
        // an Enumeration the loop takes elements from is a real one
        assertTrue(code.contains("Collections.enumeration(List.of("), code);
        // one that is only polled answers in turn, so the loop ends
        assertTrue(code.contains(".hasMoreElements()).thenReturn(true, false);"), code);
        // a mutable static is set for the test and restored right after the call
        assertTrue(code.contains("setStaticField(MessageBoard.class, \"retries\", 3);") && code.contains("// restore shared state"), code);
        // java.util.Date ordering
        assertTrue(code.contains("new Date(1705314600000L)") && code.contains("new Date(1705401000000L)") && code.contains("new Date(1705228200000L)"), code);
        // id-based equals with a second instance holding the same id
        assertTrue(code.contains("setField(o, \"id\", 1L);"), code);
        // a String element of a driven Enumeration loop gets the value each decision needs
        assertTrue(code.contains("Collections.enumeration(List.of(\"grant\"))") && code.contains("Collections.enumeration(List.of(\"\"))"), code);
    }
}
