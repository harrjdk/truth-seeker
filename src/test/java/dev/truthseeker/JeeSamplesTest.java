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

    @Test
    void validatorsAreCheckedForWhatTheyRejectAndWhatNot() throws Exception {
        String code = generate("fixtures.legacy.SignupValidator");
        // a parameter mocked by default is verified like any other mock
        assertTrue(code.contains("verify(errors, atLeastOnce()).rejectValue(eq(\"email\"), eq(\"required\"));"), code);
        // what other paths reject, this one must not
        assertTrue(code.contains("verify(errors, never()).rejectValue(eq(\"age\"), eq(\"tooYoung\"));"), code);
        assertTrue(code.contains("verify(errors, never()).rejectValue(eq(\"email\"), eq(\"required\"));"), code);
        // a varargs call is matched element by element
        assertTrue(code.contains("verify(binder, atLeastOnce()).setDisallowedFields(eq(\"id\"), eq(\"*.id\"));"), code);
    }

    @Test
    void errorPathsAreTaken() throws Exception {
        String code = generate("fixtures.legacy.AccountDesk");
        // orElseThrow(supplier): a test where the lookup finds nothing and the supplier's exception is thrown
        assertTrue(code.contains("thenReturn(Optional.empty());"), code);
        assertTrue(code.contains("assertThrows(NotFoundException.class, () -> subject.load(id));"), code);
        assertTrue(code.contains("assertThrows(IllegalStateException.class, () -> subject.loadOrFail(id));"), code);
        // the same rejection made before the save and again in its catch block is counted
        assertTrue(code.contains("verify(errors, times(2)).rejectValue(eq(\"name\"), eq(\"required\"));"), code);
    }

    @Test
    void ownStateAndFoundMembersAreChecked() throws Exception {
        String code = generate("fixtures.legacy.Kennel");
        // a constant message is asserted, for JDK exceptions and ones that pass it on unchanged
        assertTrue(code.contains("assertEquals(\"No dog to admit\", thrown.getMessage());"), code);
        assertTrue(code.contains("assertEquals(\"Portion must be positive\", thrown.getMessage());"), code);
        // the subject's own list afterwards: added to, or left as it was
        // (already there: unchanged; not there yet: added)
        String unchangedOrAdded = "assertEquals(List.of(dog), getField(subject, \"dogs\"));";
        assertTrue(code.indexOf(unchangedOrAdded) != code.lastIndexOf(unchangedOrAdded), code);
        // the member a loop found on the first try gets the call
        assertTrue(code.contains("verify(dog1, atLeastOnce()).feed(eq(grams));") || code.contains("verify(dog1, atLeastOnce()).feed(eq(1));"), code);
        // a getter that changes nothing does not assert the list
        assertFalse(code.substring(code.indexOf("void size_")).split("@Test")[0].contains("getField(subject, \"dogs\")"), code);
    }

    @Test
    void fieldArithmeticIsChecked() throws Exception {
        String code = generate("fixtures.legacy.Ledger");
        // a running total and a counter: a known value first, then the sum
        assertTrue(code.contains("setField(subject, \"balance\", 1L);") && code.contains("assertEquals((1L + amount), getField(subject, \"balance\"));"), code);
        assertTrue(code.contains("assertEquals((1 + 1), getField(subject, \"postings\"));"), code);
        // the entry built and put in the subject's list: numbered from the list's size; a field its own method sets is left out
        assertTrue(code.contains("assertEquals((0 + 1), storedEntry.getNumber());"), code);
        assertFalse(code.contains("storedEntry.getFee()"), code);
        // values stored as they are get something other than the type's default
        assertTrue(code.contains("boolean credit = true;") && code.contains("int amount = 1;") && code.contains("boolean frozen = true;"), code);
    }
}
