package dev.truthseeker;

import dev.truthseeker.testgen.TestGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expected values rendered in the test's own terms instead of a bare assertNotNull. */
class ExpectedValuesTest {

    private static String generate(String cls) throws Exception {
        var repo = TestSupport.repo();
        return TestGenerator.generate(repo, repo.require(cls), new TestGenerator.Options(List.of(), 64, 24, false));
    }

    @Test
    void concatenationsAndHashesUseTheValuesTheTestSets() throws Exception {
        String code = generate("fixtures.legacy.Deadline");
        // the field is set to a known date, then the format is rebuilt from it
        assertTrue(code.contains("assertEquals(\"Deadline \" + LocalDate.of(2024, 1, 15) + \" (\" + LocalDate.of(2024, 1, 15).getDayOfWeek() + \")\", result);"), code);
        // javac's varargs array is spread back into the call
        assertTrue(code.contains("assertEquals(Objects.hash(LocalDate.of(2024, 1, 15), \"deadline\"), result);"), code);
    }

    @Test
    void stubbedCollaboratorValuesFlowIntoTheExpectedString() throws Exception {
        String code = generate("fixtures.legacy.CustomerSearch");
        // "redirect:/customers/" + only.getId(), with getId() stubbed on the element the iterator returns
        assertTrue(code.contains("assertEquals(\"redirect:/customers/\" + 1, result);"), code);
        assertTrue(!code.contains("TODO: expected `\"redirect:/customers/\""), code);
    }

    @Test
    void aStringBuilderKeptInALocalCollectsEveryAppend() throws Exception {
        String code = generate("fixtures.legacy.ShipmentEvent");
        // appended to again after the branch, through the local variable
        assertTrue(code.contains("assertEquals(\"value\" + \": \" + Type.LOAD + \" by \" + \"value\", result);"), code);
        assertTrue(code.contains("assertEquals(\"value\" + \": \" + Type.LOAD, result);"), code);
    }

    @Test
    void objectsTheMethodBuildsAreCheckedThroughTheirGetters() throws Exception {
        String code = generate("fixtures.legacy.ShipmentEventFactory");
        // constructor arguments come back from the getters; a field no constructor set keeps its default
        assertTrue(code.contains("assertSame(trackingId, result.getTrackingId());"), code);
        assertTrue(code.contains("assertSame(carrier, result.getCarrier());"), code);
        assertTrue(code.contains("assertNull(result.getCarrier());"), code);
        // a bean filled through setters, with a field initialised where it is declared
        assertTrue(code.contains("assertSame(reference, result.getReference());"), code);
        assertTrue(code.contains("assertEquals(1, result.getPriority());") && code.contains("assertEquals(0, result.getPriority());"), code);
        assertTrue(code.contains("assertNull(result.getId());") && code.contains("assertEquals(new ArrayList<>(), result.getStops());"), code);
        assertTrue(!code.contains("TODO: expected `new "), code);
    }
}
